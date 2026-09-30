package dev.turboism.adapter.cubism.optimization.uploadelision;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
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
 * Test-only skipped-frame upload elision for the persistent-VBO wrappers
 * {@code mesh/a/b} and {@code mesh/a/c}. Inside the reviewed private upload
 * method {@code b(GL2ES2,int)} the transformer rewrites exactly three sites:
 *
 * <ul>
 *   <li>the entry of the {@code glBufferData} block (reallocate path, gated by
 *   the second {@code ILOAD 3; IFEQ} pair) and the entry of the
 *   {@code glBufferSubData} block (dirty path, gated by {@code k()Z; IFEQ}) each
 *   gain a loader-neutral {@code BiPredicate} consult; {@code true} jumps to the
 *   post-upload join. Both blocks contain only the upload call and its pure
 *   argument evaluation — binding calls, the {@code glDeleteBuffers}/
 *   {@code glGenBuffers} regeneration block, dirty-flag clearing and the
 *   trailing {@code a(gl)} bookkeeping are untouched;</li>
 *   <li>each upload call is wrapped in a {@code Throwable} handler that
 *   notifies a {@code Runnable} slot and rethrows;</li>
 *   <li>the {@code glGenBuffers} site notifies a lifecycle {@code Runnable}
 *   slot so a regenerated buffer clears the tracker.</li>
 * </ul>
 *
 * <p>Every consult goes through {@link System#getProperties()} slots holding
 * JDK functional interfaces, exactly like the model-update-skip transformer;
 * absent or mistyped slots and any callback failure fall through to the
 * unmodified host path. The reviewed method shape must equal the official
 * artifact's reference and the injection inventory is asserted per class —
 * any drift leaves the class untouched (fail-closed).</p>
 */
public final class SkippedFrameUploadElisionTransformer implements ClassFileTransformer {

    /** Opt-in system property; the experiment is off by default. */
    public static final String ENABLE_PROPERTY = "turboism.validation.skippedFrameUploadElision";

    private static final String GL = "com/jogamp/opengl/GL2ES2";
    private static final String SUBDATA = "glBufferSubData";
    private static final String SUBDATA_DESC = "(IJJLjava/nio/Buffer;)V";
    private static final String BUFFERDATA = "glBufferData";
    private static final String BUFFERDATA_DESC = "(IJLjava/nio/Buffer;I)V";
    private static final String GENBUFFERS = "glGenBuffers";
    private static final String GENBUFFERS_DESC = "(ILjava/nio/IntBuffer;)V";
    private static final String DIRTY_METHOD = "k";
    private static final String DIRTY_DESC = "()Z";

    private final ClassLoader loader;
    private final Path artifact;
    private final String owner;
    private final List<String> shape;
    private volatile String beforeSha256, failure;
    private volatile int matches, guarded;

    /**
     * @param loader the host class loader admitted for the artifact
     * @param artifact official host JAR path, attested at every transform
     * @param reference official {@link ClassReader#EXPAND_FRAMES} bytes of {@code owner}
     * @param owner one of {@link SkippedFrameUploadElisionTarget#OWNERS}
     */
    public SkippedFrameUploadElisionTransformer(
            final ClassLoader loader, final Path artifact, final byte[] reference, final String owner) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        if (!SkippedFrameUploadElisionTarget.OWNERS.contains(Objects.requireNonNull(owner, "owner"))) {
            throw new IllegalArgumentException("unreviewed upload-elision owner: " + owner);
        }
        this.owner = owner;
        shape = ReviewedMethodShape.read(
                reference, owner, SkippedFrameUploadElisionTarget.METHOD, SkippedFrameUploadElisionTarget.DESCRIPTOR);
        if (shape == null) {
            throw new IllegalArgumentException("reviewed upload method absent: " + owner);
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
    /** Returns the guarded upload call-site count so far. */
    public int guarded() {
        return guarded;
    }
    /** Returns the original full class digest for restoration verification. */
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
        if (actualLoader != loader || !owner.equals(name) || bytes == null) return null;
        try {
            if (domain == null
                    || domain.getCodeSource() == null
                    || !artifact.equals(
                            Path.of(domain.getCodeSource().getLocation().toURI())
                                    .toAbsolutePath()
                                    .normalize())) {
                failure = "upload elision source mismatch: " + owner;
                return null;
            }
            if (!shape.equals(ReviewedMethodShape.read(
                    bytes,
                    owner,
                    SkippedFrameUploadElisionTarget.METHOD,
                    SkippedFrameUploadElisionTarget.DESCRIPTOR))) {
                failure = "upload method shape changed: " + owner;
                return null;
            }
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() {
                    return loader;
                }
            };
            final UploadVisitor[] applied = {null};
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
                            if (!SkippedFrameUploadElisionTarget.METHOD.equals(method)
                                    || !SkippedFrameUploadElisionTarget.DESCRIPTOR.equals(descriptor)) {
                                return visitor;
                            }
                            applied[0] = new UploadVisitor(visitor);
                            return applied[0];
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (applied[0] == null || !applied[0].complete()) {
                failure = "upload elision anchor drift on " + owner + ": "
                        + (applied[0] == null ? "method absent" : applied[0].describe());
                return null;
            }
            final byte[] result = writer.toByteArray();
            if (beforeSha256 == null) {
                beforeSha256 = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            }
            matches++;
            guarded += applied[0].wrappedCalls;
            return result;
        } catch (Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure = rejected + " at " + (trace.length == 0 ? "<no frames>" : trace[0].toString());
            return null;
        }
    }

    /**
     * Injects the guards/notifications into the reviewed {@code b(GL2ES2,int)}
     * body. Anchors (identical on both reviewed artifacts):
     * {@code ILOAD 3; IFEQ} occurrence 2 enters the {@code glBufferData} block;
     * {@code invokevirtual this.k()Z; IFEQ join} gates the
     * {@code glBufferSubData} block; both consults jump to the join label that
     * leads to the dirty-flag clearing, so only the upload block is skipped.
     */
    private final class UploadVisitor extends MethodVisitor {
        private final Label deferredJoin = new Label();
        private final Label uploadHandler = new Label();
        private Label joinLabel;
        private boolean joinVisited;
        /** 0 none, 1 = previous instruction was {@code ILOAD 3}, 2 = {@code invokevirtual k()Z}. */
        private int pending;

        private int iload3Ifeq, guards, wrappedCalls, genNotifies, maxVar = 3;

        UploadVisitor(final MethodVisitor visitor) {
            super(Opcodes.ASM9, visitor);
        }

        private int consumePending() {
            final int was = pending;
            pending = 0;
            return was;
        }

        @Override
        public void visitVarInsn(final int opcode, final int variable) {
            if (variable > maxVar) maxVar = variable;
            consumePending();
            super.visitVarInsn(opcode, variable);
            if (opcode == Opcodes.ILOAD && variable == 3) pending = 1;
        }

        @Override
        public void visitIincInsn(final int variable, final int increment) {
            if (variable > maxVar) maxVar = variable;
            consumePending();
            super.visitIincInsn(variable, increment);
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String methodOwner,
                final String method,
                final String descriptor,
                final boolean itf) {
            consumePending();
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && !itf
                    && owner.equals(methodOwner)
                    && DIRTY_METHOD.equals(method)
                    && DIRTY_DESC.equals(descriptor)) {
                super.visitMethodInsn(opcode, methodOwner, method, descriptor, itf);
                pending = 2;
                return;
            }
            if (opcode == Opcodes.INVOKEINTERFACE && itf && GL.equals(methodOwner)) {
                if (GENBUFFERS.equals(method) && GENBUFFERS_DESC.equals(descriptor)) {
                    super.visitMethodInsn(opcode, methodOwner, method, descriptor, itf);
                    emitRunnableConsult(SkippedFrameUploadElisionBridge.LIFECYCLE_PROPERTY);
                    genNotifies++;
                    return;
                }
                if ((SUBDATA.equals(method) && SUBDATA_DESC.equals(descriptor))
                        || (BUFFERDATA.equals(method) && BUFFERDATA_DESC.equals(descriptor))) {
                    final Label callStart = new Label(), callEnd = new Label();
                    super.visitTryCatchBlock(callStart, callEnd, uploadHandler, "java/lang/Throwable");
                    super.visitLabel(callStart);
                    super.visitMethodInsn(opcode, methodOwner, method, descriptor, itf);
                    super.visitLabel(callEnd);
                    wrappedCalls++;
                    return;
                }
            }
            super.visitMethodInsn(opcode, methodOwner, method, descriptor, itf);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            final int was = consumePending();
            if (opcode == Opcodes.IFEQ && was == 1) {
                iload3Ifeq++;
                super.visitJumpInsn(opcode, label);
                if (iload3Ifeq == 2) {
                    emitElisionGuard(deferredJoin);
                    guards++;
                }
            } else if (opcode == Opcodes.IFEQ && was == 2) {
                joinLabel = label;
                super.visitJumpInsn(opcode, label);
                emitElisionGuard(joinLabel);
                guards++;
            } else {
                super.visitJumpInsn(opcode, label);
            }
        }

        @Override
        public void visitLabel(final Label label) {
            if (label == joinLabel && !joinVisited) {
                joinVisited = true;
                super.visitLabel(deferredJoin);
            }
            super.visitLabel(label);
        }

        @Override
        public void visitInsn(final int opcode) {
            consumePending();
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            consumePending();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitFieldInsn(
                final int opcode, final String fieldOwner, final String name, final String descriptor) {
            consumePending();
            super.visitFieldInsn(opcode, fieldOwner, name, descriptor);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            consumePending();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            consumePending();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name,
                final String descriptor,
                final org.objectweb.asm.Handle bootstrap,
                final Object... args) {
            consumePending();
            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, args);
        }

        @Override
        public void visitMaxs(final int stack, final int locals) {
            if (wrappedCalls > 0) emitUploadHandler();
            super.visitMaxs(stack, locals);
        }

        boolean complete() {
            return joinVisited && iload3Ifeq == 2 && guards == 2 && wrappedCalls == 2 && genNotifies == 1;
        }

        String describe() {
            return "iload3Ifeq=" + iload3Ifeq + " guards=" + guards + " calls=" + wrappedCalls + " gen=" + genNotifies
                    + " join=" + joinVisited;
        }

        /**
         * Emits {@code if (predicate.test(this, gl)) goto skipTarget} inside a
         * {@code Throwable} guard that always falls through to the native path.
         * Both anchors sit at a basic-block boundary with an empty operand
         * stack, so every exit is frame-consistent.
         */
        private void emitElisionGuard(final Label skipTarget) {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label discard = new Label(), resume = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
            super.visitLdcInsn(SkippedFrameUploadElisionBridge.PREDICATE_PROPERTY);
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
            super.visitJumpInsn(Opcodes.IFEQ, resume);
            super.visitJumpInsn(Opcodes.GOTO, skipTarget);
            super.visitLabel(end);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, resume);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(resume);
        }

        /** Emits {@code slot.run()} inside the same always-fall-through guard. */
        private void emitRunnableConsult(final String property) {
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
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Runnable");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Runnable");
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
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
         * Shared {@code Throwable} handler for the wrapped upload calls: notify
         * the failure slot (itself guarded) and rethrow so host semantics are
         * unchanged. Emitted after the method body; the temporary local sits
         * one slot past every local seen while visiting.
         */
        private void emitUploadHandler() {
            final int tmp = maxVar + 1;
            final Label start = new Label(), end = new Label(), inner = new Label();
            final Label discard = new Label(), done = new Label();
            super.visitLabel(uploadHandler);
            super.visitVarInsn(Opcodes.ASTORE, tmp);
            super.visitTryCatchBlock(start, end, inner, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
            super.visitLdcInsn(SkippedFrameUploadElisionBridge.FAILURE_PROPERTY);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/util/Properties",
                    "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Runnable");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Runnable");
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(inner);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
            super.visitVarInsn(Opcodes.ALOAD, tmp);
            super.visitInsn(Opcodes.ATHROW);
        }
    }
}
