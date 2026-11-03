package dev.turboism.adapter.cubism.optimization.meshsetup;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
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
 * Rewrites {@code GMesh2.setup(GFrame)} so a skipped-frame consult can return
 * before the nine persistent-buffer refreshes.
 *
 * <p>Three guarded sites are injected through JDK functional-interface slots in
 * {@link System#getProperties()}, exactly like the model-update-skip and
 * upload-elision transforms:</p>
 *
 * <ul>
 *   <li>at method entry a {@code BiPredicate<GMesh2, GFrame>} consult can
 *   return immediately; the bridge only answers {@code true} for a mesh whose
 *   buffers are already initialized and clean in the current context;</li>
 *   <li>before the single {@code RETURN} a {@code Consumer<GMesh2>} records a
 *   successful native setup and its context generation;</li>
 *   <li>a whole-body {@code Throwable} handler notifies a failure
 *   {@code Consumer<GMesh2>} and rethrows unchanged.</li>
 * </ul>
 *
 * <p>Every lookup and callback is itself guarded; absent, mistyped or failing
 * slots fall through to the untouched native body. The reviewed method shape
 * must equal the official artifact's reference and the injection inventory is
 * asserted per class - any drift leaves the class untouched (fail-closed).</p>
 */
public final class SkippedFrameMeshSetupElisionTransformer implements ClassFileTransformer {

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> shape;
    private volatile String beforeSha256, failure;
    private volatile int matches, guarded;

    /**
     * @param loader the admitted host class loader
     * @param artifact official host JAR path, attested at every transform
     * @param reference official {@link ClassReader#EXPAND_FRAMES} bytes of
     *                  {@link SkippedFrameMeshSetupElisionTarget#OWNER}
     */
    public SkippedFrameMeshSetupElisionTransformer(
            final ClassLoader loader, final Path artifact, final byte[] reference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(reference, "reference");
        shape = ReviewedMethodShape.read(
                reference,
                SkippedFrameMeshSetupElisionTarget.OWNER,
                SkippedFrameMeshSetupElisionTarget.METHOD,
                SkippedFrameMeshSetupElisionTarget.DESCRIPTOR);
        if (shape == null) {
            throw new IllegalArgumentException("reviewed GMesh2.setup absent");
        }
    }

    /** Returns the latest rejection, or null. */
    public String failure() {
        return failure;
    }

    /** Returns the successful class transform count. */
    public int matches() {
        return matches;
    }

    /** Returns the number of rewritten setup entry guards. */
    public int guarded() {
        return guarded;
    }

    /** Returns the original class digest for restoration verification. */
    public String beforeSha256() {
        return beforeSha256;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (actualLoader != loader || !SkippedFrameMeshSetupElisionTarget.OWNER.equals(name) || bytes == null) {
            return null;
        }
        try {
            if (domain == null
                    || domain.getCodeSource() == null
                    || !artifact.equals(
                            Path.of(domain.getCodeSource().getLocation().toURI())
                                    .toAbsolutePath()
                                    .normalize())) {
                failure = "mesh setup elision source mismatch: " + name;
                return null;
            }
            if (!shape.equals(ReviewedMethodShape.read(
                    bytes,
                    name,
                    SkippedFrameMeshSetupElisionTarget.METHOD,
                    SkippedFrameMeshSetupElisionTarget.DESCRIPTOR))) {
                failure = "GMesh2.setup shape changed: " + name;
                return null;
            }
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() {
                    return loader;
                }
            };
            final SetupVisitor[] applied = {null};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String method,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor visitor =
                                    super.visitMethod(access, method, descriptor, signature, exceptions);
                            if (!SkippedFrameMeshSetupElisionTarget.METHOD.equals(method)
                                    || !SkippedFrameMeshSetupElisionTarget.DESCRIPTOR.equals(descriptor)) {
                                return visitor;
                            }
                            applied[0] = new SetupVisitor(visitor);
                            return applied[0];
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (applied[0] == null || !applied[0].complete()) {
                failure = "mesh setup elision anchor drift on " + name + ": "
                        + (applied[0] == null ? "method absent" : applied[0].describe());
                return null;
            }
            final byte[] result = writer.toByteArray();
            if (beforeSha256 == null) {
                beforeSha256 = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            }
            matches++;
            guarded += applied[0].guardCount;
            return result;
        } catch (Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure = rejected + " at " + (trace.length == 0 ? "<no frames>" : trace[0].toString());
            return null;
        }
    }

    /**
     * Injects the entry/return/failure sites into the reviewed body. The
     * reviewed method contains exactly one {@code RETURN} and no original
     * exception handler; either change fails the transform closed.
     */
    private final class SetupVisitor extends MethodVisitor {
        private final Label bodyStart = new Label();
        private final Label bodyEnd = new Label();
        private final Label failureHandler = new Label();
        private final List<Handler> handlers = new ArrayList<>();
        private boolean bodyEndVisited, failureHandlerEmitted;
        private int guardCount, returnCount, maxVar = 1;

        SetupVisitor(final MethodVisitor visitor) {
            super(Opcodes.ASM9, visitor);
        }

        @Override
        public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
            handlers.add(new Handler(start, end, handler, type));
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitEntryGuard();
            super.visitLabel(bodyStart);
            guardCount++;
        }

        @Override
        public void visitVarInsn(final int opcode, final int variable) {
            if (variable > maxVar) maxVar = variable;
            super.visitVarInsn(opcode, variable);
        }

        @Override
        public void visitIincInsn(final int variable, final int increment) {
            if (variable > maxVar) maxVar = variable;
            super.visitIincInsn(variable, increment);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == Opcodes.RETURN) {
                emitConsumer(SkippedFrameMeshSetupElisionBridge.COMPLETE_PROPERTY);
                returnCount++;
                if (!bodyEndVisited) {
                    bodyEndVisited = true;
                    super.visitLabel(bodyEnd);
                }
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(final int stack, final int locals) {
            for (final Handler handler : handlers) {
                super.visitTryCatchBlock(handler.start(), handler.end(), handler.target(), handler.type());
            }
            if (bodyEndVisited) {
                super.visitTryCatchBlock(bodyStart, bodyEnd, failureHandler, "java/lang/Throwable");
                emitFailureHandler();
                failureHandlerEmitted = true;
            }
            super.visitMaxs(stack, locals);
        }

        boolean complete() {
            return guardCount == 1 && returnCount == 1 && bodyEndVisited && failureHandlerEmitted && handlers.isEmpty();
        }

        String describe() {
            return "guards=" + guardCount + " returns=" + returnCount + " handlers=" + handlers.size() + " bodyEnd="
                    + bodyEndVisited;
        }

        /**
         * Emits {@code if (predicate.test(this, frame)) return;} inside a
         * {@code Throwable} guard that always falls through to the native body.
         */
        private void emitEntryGuard() {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label discard = new Label(), nativePath = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
            super.visitLdcInsn(SkippedFrameMeshSetupElisionBridge.PREDICATE_PROPERTY);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/util/Properties",
                    "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/BiPredicate");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiPredicate");
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, 1);
            super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/function/BiPredicate",
                    "test",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                    true);
            super.visitJumpInsn(Opcodes.IFEQ, nativePath);
            super.visitInsn(Opcodes.RETURN);
            super.visitLabel(end);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, nativePath);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(nativePath);
        }

        /**
         * Emits {@code slot.accept(this)} for a property holding a
         * {@link java.util.function.Consumer}, inside an always-fall-through
         * {@code Throwable} guard.
         */
        private void emitConsumer(final String property) {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label discard = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
            super.visitLdcInsn(property);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/util/Properties",
                    "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Consumer");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }

        /**
         * Emits the whole-body {@code Throwable} handler: notify the failure
         * slot (itself guarded), then rethrow unchanged. The temporary local
         * sits one slot past every local seen while visiting.
         */
        private void emitFailureHandler() {
            final int tmp = maxVar + 1;
            super.visitLabel(failureHandler);
            super.visitVarInsn(Opcodes.ASTORE, tmp);
            emitConsumer(SkippedFrameMeshSetupElisionBridge.FAILURE_PROPERTY);
            super.visitVarInsn(Opcodes.ALOAD, tmp);
            super.visitInsn(Opcodes.ATHROW);
        }
    }

    private record Handler(Label start, Label end, Label target, String type) {}
}
