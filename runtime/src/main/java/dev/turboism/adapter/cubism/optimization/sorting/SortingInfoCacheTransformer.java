package dev.turboism.adapter.cubism.optimization.sorting;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Reuses the last computed sorting information on skipped model-update frames.
 *
 * <p>{@code com.live2d.graphics3d.component.a.b.a(GCamera)} runs for every mesh renderer
 * while the scene is sorted. It rewrites the layer/order bits, asks the renderer for
 * {@code getSortingZOrder__testImpl(camera)} and reads the relative draw order even when
 * the camera argument is {@code null} and the model was not updated this frame. The
 * transformer injects a guard at method entry:</p>
 *
 * <pre>
 *   if (camera == null
 *       &amp;&amp; this.c != 0L
 *       &amp;&amp; this.e &gt;= 0
 *       &amp;&amp; System.getProperties()
 *              .get("turboism.model-update-skip.frame-skipped") instanceof AtomicBoolean skipped
 *       &amp;&amp; skipped.get()) {
 *       return; // c, d and e are already current for this skipped frame
 *   }
 * </pre>
 *
 * <p>The guard is loader-neutral: it reads a JDK {@link java.util.concurrent.atomic.AtomicBoolean}
 * from the system-properties slot published by {@link ModelUpdateSkipBridge}. An absent,
 * mistyped or inaccessible slot, and any exception while consulting it, fall through to
 * the unmodified host body. The reviewed method shape must match the reference bytes and
 * the loaded class must originate from the exact reviewed artifact; otherwise the class is
 * left untouched.</p>
 */
public final class SortingInfoCacheTransformer implements ClassFileTransformer {

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> shape;
    private volatile String beforeSha256, failure;
    private volatile int matches;

    /**
     * @param loader the host loader this instance is allowed to rewrite
     * @param artifact official artifact, compared to each candidate's code source
     * @param reference official {@link ClassReader#EXPAND_FRAMES} reference bytes of the owner
     * @param target reviewed target for this artifact
     * @throws IllegalArgumentException when the reviewed sorting method is absent from the reference
     */
    public SortingInfoCacheTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] reference,
            final SortingInfoCacheTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reference, "reference");
        shape = ReviewedMethodShape.read(
                reference,
                SortingInfoCacheTarget.SORTING_OWNER,
                SortingInfoCacheTarget.SORTING_METHOD,
                SortingInfoCacheTarget.SORTING_DESCRIPTOR);
        if (shape == null) {
            throw new IllegalArgumentException("reviewed sorting-info method absent");
        }
    }

    /** SHA-256 of the first rewritten class bytes for restoration evidence. */
    public String beforeSha256() {
        return beforeSha256;
    }

    /** Most recent rejection, or null when none has occurred. */
    public String failure() {
        return failure;
    }

    /** Rewritten classes observed so far. */
    public int matches() {
        return matches;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (actualLoader != loader || !SortingInfoCacheTarget.SORTING_OWNER.equals(name) || bytes == null) {
            return null;
        }
        try {
            if (domain == null
                    || domain.getCodeSource() == null
                    || domain.getCodeSource().getLocation() == null
                    || !artifact.equals(
                            Path.of(domain.getCodeSource().getLocation().toURI())
                                    .toAbsolutePath()
                                    .normalize())) {
                failure = "sorting-info source is not the reviewed artifact";
                return null;
            }
            if (!shape.equals(ReviewedMethodShape.read(
                    bytes,
                    SortingInfoCacheTarget.SORTING_OWNER,
                    SortingInfoCacheTarget.SORTING_METHOD,
                    SortingInfoCacheTarget.SORTING_DESCRIPTOR))) {
                failure = "sorting-info method shape mismatch";
                return null;
            }
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() {
                    return loader;
                }
            };
            final boolean[] applied = {false};
            reader.accept(new SortingVisitor(writer, applied), ClassReader.EXPAND_FRAMES);
            if (!applied[0]) {
                failure = "sorting-info guard was not applied";
                return null;
            }
            if (beforeSha256 == null) {
                beforeSha256 = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            }
            matches++;
            return writer.toByteArray();
        } catch (Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure = rejected + " at " + (trace.length == 0 ? "<no frames>" : trace[0].toString());
            return null;
        }
    }

    /**
     * Route the reviewed method to {@link EntryVisitor}. Try/catch blocks are buffered
     * until the end so the guard handler precedes any host handler.
     */
    private static final class SortingVisitor extends ClassVisitor {
        private final boolean[] applied;

        SortingVisitor(final ClassVisitor delegate, final boolean[] applied) {
            super(Opcodes.ASM9, delegate);
            this.applied = applied;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String method,
                final String descriptor,
                final String signature,
                final String[] exceptions) {
            final MethodVisitor visitor = super.visitMethod(access, method, descriptor, signature, exceptions);
            if (!SortingInfoCacheTarget.SORTING_METHOD.equals(method)
                    || !SortingInfoCacheTarget.SORTING_DESCRIPTOR.equals(descriptor)) {
                return visitor;
            }
            applied[0] = true;
            return new EntryVisitor(visitor);
        }
    }

    /** Emits the fail-closed entry guard in front of the untouched host body. */
    private static final class EntryVisitor extends MethodVisitor {
        private final List<Handler> handlers = new ArrayList<>();

        EntryVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
            handlers.add(new Handler(start, end, handler, type));
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitGuard();
        }

        @Override
        public void visitMaxs(final int stack, final int locals) {
            for (final Handler handler : handlers) {
                super.visitTryCatchBlock(handler.start(), handler.end(), handler.target(), handler.type());
            }
            super.visitMaxs(stack, locals);
        }

        private void emitGuard() {
            final Label start = new Label();
            final Label end = new Label();
            final Label handler = new Label();
            final Label discard = new Label();
            final Label nativePath = new Label();

            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);

            // camera != null -> native
            super.visitVarInsn(Opcodes.ALOAD, 1);
            super.visitJumpInsn(Opcodes.IFNONNULL, nativePath);

            // this.c == 0 -> cache never populated -> native
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, SortingInfoCacheTarget.SORTING_OWNER, "c", "J");
            super.visitInsn(Opcodes.LCONST_0);
            super.visitInsn(Opcodes.LCMP);
            super.visitJumpInsn(Opcodes.IFEQ, nativePath);

            // this.e < 0 -> non-model entity -> native
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, SortingInfoCacheTarget.SORTING_OWNER, "e", "I");
            super.visitJumpInsn(Opcodes.IFLT, nativePath);

            // slot = System.getProperties().get(SKIPPED_FRAME_PROPERTY)
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
            super.visitLdcInsn(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/util/Properties",
                    "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/concurrent/atomic/AtomicBoolean");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/concurrent/atomic/AtomicBoolean");
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicBoolean", "get", "()Z", false);
            // false -> native; true falls through to the return
            super.visitJumpInsn(Opcodes.IFEQ, nativePath);
            super.visitLabel(end);
            super.visitInsn(Opcodes.RETURN);

            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, nativePath);

            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(nativePath);
        }
    }

    private record Handler(Label start, Label end, Label target, String type) {}
}
