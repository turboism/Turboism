package dev.turboism.adapter.cubism.optimization.clipmask;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Transforms the reviewed render system class for the zero-quality-loss
 * clipping-mask deduplication optimization.
 *
 * <p>Three methods on the render system ({@code rendering/e} or {@code rendering/d})
 * are modified:</p>
 * <ul>
 *   <li>{@code a(AGRenderer, a, CRect)V} (mask pass): entry consults the
 *       {@code BiPredicate<Object, Object>} slot and returns immediately when it
 *       reports a dedupe hit, skipping the mask FBO acquire, bind, clear and draws.
 *       On a miss the same call starts the scissor pass. The mask {@code glClear} runs the
 *       {@code Function<Object, Runnable>} slot before it, which returns the restore action
 *       that runs after {@code glClear} — a clear that needs no narrowing therefore reads no
 *       driver state at all. Every normal
 *       {@code RETURN} runs the {@code BiConsumer<Object, Object>} exit consult, which
 *       records the newly rendered mask and ends the scissor pass.</li>
 *   <li>{@code a(AGRenderer, a)V} (endClipping): entry consults
 *       {@code Predicate<Object>}. If {@code true} (lease held in dedupe cache),
 *       suppresses releasing to pool and returns immediately.</li>
 *   <li>{@code a(a, CRect, ...)V} (top-level render): exit invokes {@code Runnable}
 *       to release all held leases to the pool and drop the per-frame caches.</li>
 * </ul>
 *
 * <p>Each woven site resolves its slot with a single {@code Properties.get}: the value is
 * kept on the operand stack across the {@code instanceof} check instead of being looked up
 * a second time for the cast. Together with the merged entry and exit consults this takes
 * a mask pass from six slot lookups (twelve {@code Hashtable} reads) to three, which is
 * the whole hook cost the camera pan and zoom path pays per clipped drawable.</p>
 */
public final class ClipMaskDeduplicationTransformer implements ClassFileTransformer {

    private final ClassLoader loader;
    private final Path artifact;
    private final String renderSystemOwner;
    private final String renderMethodDescriptor;
    private final Map<String, byte[]> references;
    private final Map<String, String> beforeSha256 = new ConcurrentHashMap<>();
    private final Map<String, String> instrumentedSha256 = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private volatile int sites;

    public ClipMaskDeduplicationTransformer(
            final ClassLoader loader,
            final Path artifact,
            final String renderSystemOwner,
            final String renderMethodDescriptor,
            final Map<String, byte[]> references) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        this.renderSystemOwner = Objects.requireNonNull(renderSystemOwner, "renderSystemOwner");
        this.renderMethodDescriptor = Objects.requireNonNull(renderMethodDescriptor, "renderMethodDescriptor");
        this.references = Map.copyOf(references);
    }

    public int sites() {
        return sites;
    }

    public String failure() {
        if (failures.isEmpty()) return null;
        final StringBuilder text = new StringBuilder();
        for (final Map.Entry<String, String> entry : new java.util.TreeMap<>(failures).entrySet()) {
            if (!text.isEmpty()) text.append("; ");
            text.append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return text.toString();
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (bytes == null || actualLoader != loader || name == null) return null;
        if (!name.equals(renderSystemOwner)) return null;

        final byte[] reference = references.get(name);
        if (reference == null) {
            failures.put(name, "no reference bytes for " + name);
            return null;
        }

        try {
            if (domain == null
                    || domain.getCodeSource() == null
                    || !artifact.equals(
                            Path.of(domain.getCodeSource().getLocation().toURI())
                                    .toAbsolutePath()
                                    .normalize())) {
                failures.put(name, "source mismatch: " + name);
                return null;
            }

            checkShape(
                    reference,
                    bytes,
                    name,
                    ClipMaskDeduplicationTarget.MASK_PASS_METHOD,
                    ClipMaskDeduplicationTarget.MASK_PASS_DESCRIPTOR);
            checkShape(
                    reference,
                    bytes,
                    name,
                    ClipMaskDeduplicationTarget.END_CLIPPING_METHOD,
                    ClipMaskDeduplicationTarget.END_CLIPPING_DESCRIPTOR);
            checkShape(reference, bytes, name, ClipMaskDeduplicationTarget.RENDER_METHOD, renderMethodDescriptor);

            final ClassReader reader = new ClassReader(bytes);
            final int maskPassMaxLocals = maxLocals(
                    reader,
                    ClipMaskDeduplicationTarget.MASK_PASS_METHOD,
                    ClipMaskDeduplicationTarget.MASK_PASS_DESCRIPTOR);
            if (maskPassMaxLocals < 4) {
                failures.put(name, "mask pass locals unavailable: " + maskPassMaxLocals);
                return null;
            }
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() {
                    return loader;
                }
            };

            final int[] woven = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String method,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor delegate =
                                    super.visitMethod(access, method, descriptor, signature, exceptions);

                            if (method.equals(ClipMaskDeduplicationTarget.MASK_PASS_METHOD)
                                    && descriptor.equals(ClipMaskDeduplicationTarget.MASK_PASS_DESCRIPTOR)) {
                                woven[0]++;
                                return new MaskPassVisitor(delegate, maskPassMaxLocals);
                            }
                            if (method.equals(ClipMaskDeduplicationTarget.END_CLIPPING_METHOD)
                                    && descriptor.equals(ClipMaskDeduplicationTarget.END_CLIPPING_DESCRIPTOR)) {
                                woven[0]++;
                                return new EndClippingVisitor(delegate);
                            }
                            if (method.equals(ClipMaskDeduplicationTarget.RENDER_METHOD)
                                    && descriptor.equals(renderMethodDescriptor)) {
                                woven[0]++;
                                return new FrameBoundaryVisitor(delegate);
                            }
                            return delegate;
                        }
                    },
                    ClassReader.EXPAND_FRAMES);

            if (woven[0] != 3) {
                failures.put(name, "woven count mismatch: " + woven[0] + "/3");
                return null;
            }

            final byte[] result = writer.toByteArray();
            beforeSha256.putIfAbsent(name, sha256(bytes));
            instrumentedSha256.putIfAbsent(name, sha256(result));
            sites += woven[0];
            return result;
        } catch (Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failures.put(name, rejected + " at " + (trace.length == 0 ? "<no frames>" : trace[0].toString()));
            return null;
        }
    }

    private static int maxLocals(final ClassReader reader, final String methodName, final String descriptor) {
        final int[] result = {0};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String method,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (!methodName.equals(method) || !descriptor.equals(desc)) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMaxs(final int stack, final int locals) {
                                result[0] = locals;
                            }
                        };
                    }
                },
                ClassReader.SKIP_FRAMES);
        return result[0];
    }

    private void checkShape(
            final byte[] ref, final byte[] target, final String owner, final String method, final String desc) {
        final List<String> expected = ReviewedMethodShape.read(ref, owner, method, desc);
        if (expected == null) throw new IllegalArgumentException("reviewed selector absent: " + owner + "." + method);
        if (!expected.equals(ReviewedMethodShape.read(target, owner, method, desc))) {
            throw new IllegalArgumentException("shape changed: " + owner + "." + method);
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Emits a slot read and the guard that skips the call when the slot holds another type. */
    private static void emitSlot(final MethodVisitor visitor, final String property, final String interfaceType) {
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
        visitor.visitLdcInsn(property);
        visitor.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        // Keep the resolved value on the stack: one Hashtable read serves both the
        // instanceof guard and the cast, instead of reading the slot twice per site.
        visitor.visitInsn(Opcodes.DUP);
        visitor.visitTypeInsn(Opcodes.INSTANCEOF, interfaceType);
    }

    /**
     * Injects the dedupe/scissor entry consult at entry, the scissor bracket around the mask
     * clear, and the exit consult before RETURN.
     */
    private static final class MaskPassVisitor extends MethodVisitor {

        private final int tempGl;
        private final int tempMask;
        private final int tempRestore;

        MaskPassVisitor(final MethodVisitor delegate, final int maskPassMaxLocals) {
            super(Opcodes.ASM9, delegate);
            tempGl = maskPassMaxLocals;
            tempMask = maskPassMaxLocals + 1;
            tempRestore = maskPassMaxLocals + 2;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label start = new Label(), end = new Label(), handler = new Label(), skip = new Label();
            final Label proceed = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            emitSlot(this, ClipMaskDeduplicationBridge.DEDUPE_PROPERTY, "java/util/function/BiPredicate");
            super.visitJumpInsn(Opcodes.IFEQ, skip);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiPredicate");
            super.visitVarInsn(Opcodes.ALOAD, 0); // renderSystem
            super.visitVarInsn(Opcodes.ALOAD, 1); // renderer
            super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/function/BiPredicate",
                    "test",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                    true);
            super.visitJumpInsn(Opcodes.IFEQ, proceed);
            super.visitInsn(Opcodes.RETURN); // Dedupe hit! Skip pass.
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, proceed);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, proceed);
            super.visitLabel(skip);
            super.visitInsn(Opcodes.POP); // Drop the slot value that was not the expected type.
            super.visitLabel(proceed);
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {
            final boolean maskClear = opcode == Opcodes.INVOKEINTERFACE
                    && "com/jogamp/opengl/GL3".equals(owner)
                    && "glClear".equals(name)
                    && "(I)V".equals(descriptor);
            if (maskClear) {
                super.visitVarInsn(Opcodes.ISTORE, tempMask);
                super.visitVarInsn(Opcodes.ASTORE, tempGl);
                super.visitInsn(Opcodes.ACONST_NULL);
                super.visitVarInsn(Opcodes.ASTORE, tempRestore);
                emitBeforeClear();
                super.visitVarInsn(Opcodes.ALOAD, tempGl);
                super.visitVarInsn(Opcodes.ILOAD, tempMask);
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (maskClear) emitAfterClear();
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == Opcodes.RETURN) {
                final Label start = new Label(), end = new Label(), handler = new Label();
                final Label skip = new Label(), done = new Label();
                super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
                super.visitLabel(start);
                emitSlot(this, ClipMaskDeduplicationBridge.RECORD_PROPERTY, "java/util/function/BiConsumer");
                super.visitJumpInsn(Opcodes.IFEQ, skip);
                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiConsumer");
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitVarInsn(Opcodes.ALOAD, 1);
                super.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/function/BiConsumer",
                        "accept",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V",
                        true);
                super.visitLabel(end);
                super.visitJumpInsn(Opcodes.GOTO, done);
                super.visitLabel(handler);
                super.visitInsn(Opcodes.POP);
                super.visitJumpInsn(Opcodes.GOTO, done);
                super.visitLabel(skip);
                super.visitInsn(Opcodes.POP);
                super.visitLabel(done);
            }
            super.visitInsn(opcode);
        }

        /**
         * Runs the clear bracket before {@code glClear} and keeps the returned restore action in
         * {@code tempRestore}. A slot that is absent, holds another type, or throws leaves the
         * clear untouched.
         */
        private void emitBeforeClear() {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label skip = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            emitSlot(this, ClipMaskScissorBridge.CLEAR_PROPERTY, "java/util/function/Function");
            super.visitJumpInsn(Opcodes.IFEQ, skip);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function");
            super.visitVarInsn(Opcodes.ALOAD, 2); // render context
            super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/function/Function",
                    "apply",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    true);
            super.visitVarInsn(Opcodes.ASTORE, tempRestore);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(skip);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }

        /** Runs the restore action the clear bracket returned, right after {@code glClear}. */
        private void emitAfterClear() {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label skip = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitVarInsn(Opcodes.ALOAD, tempRestore);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Runnable");
            super.visitJumpInsn(Opcodes.IFEQ, skip);
            super.visitVarInsn(Opcodes.ALOAD, tempRestore);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Runnable");
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(skip);
            super.visitLabel(done);
        }
    }

    /**
     * Injects the release consult at entry of endClipping.
     */
    private static final class EndClippingVisitor extends MethodVisitor {

        EndClippingVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label skip = new Label(), proceed = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            emitSlot(this, ClipMaskDeduplicationBridge.RELEASE_PROPERTY, "java/util/function/Predicate");
            super.visitJumpInsn(Opcodes.IFEQ, skip);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate");
            super.visitVarInsn(Opcodes.ALOAD, 0); // renderSystem
            super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test", "(Ljava/lang/Object;)Z", true);
            super.visitJumpInsn(Opcodes.IFEQ, proceed);
            super.visitInsn(Opcodes.RETURN); // Lease held in dedupe cache! Suppress pool release.
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, proceed);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, proceed);
            super.visitLabel(skip);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(proceed);
        }
    }

    /**
     * Injects the frame boundary cleanup at exit of the top-level render method.
     */
    private static final class FrameBoundaryVisitor extends MethodVisitor {

        private final Label bodyStart = new Label();
        private final Label bodyEnd = new Label();
        private final Label throwableHandler = new Label();

        FrameBoundaryVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            visitLabel(bodyStart);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                emitFrameCleanup();
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(final int stack, final int locals) {
            visitLabel(bodyEnd);
            super.visitTryCatchBlock(bodyStart, bodyEnd, throwableHandler, null);
            visitLabel(throwableHandler);
            final int caught = locals;
            visitVarInsn(Opcodes.ASTORE, caught);
            emitFrameCleanup();
            visitVarInsn(Opcodes.ALOAD, caught);
            visitInsn(Opcodes.ATHROW);
            super.visitMaxs(stack + 2, locals + 1);
        }

        private void emitFrameCleanup() {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label skip = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            emitSlot(this, ClipMaskDeduplicationBridge.FRAME_PROPERTY, "java/lang/Runnable");
            super.visitJumpInsn(Opcodes.IFEQ, skip);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Runnable");
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(skip);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }
    }
}
