package dev.turboism.adapter.cubism.optimization.lineupload;

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
 * Rewrites {@code GLineRenderer.updateMeshData()} with a guarded entry consult so the
 * selected buffer wrappers can be marked dirty from their actual content instead of
 * unconditionally marking positions, colors and indices.
 *
 * <p>The woven code only references JDK functional interfaces and
 * {@link System#getProperties()}; no Turboism class is visible to the host loader.
 * Any absent slot, shape drift, extraction failure or thrown callback falls through
 * to the untouched native body, which marks all three buffers as before.</p>
 */
public final class LineRendererUploadSplitTransformer implements ClassFileTransformer {

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> shape;
    private volatile String beforeSha256, failure;
    private volatile int matches;

    /**
     * @param loader the admitted host class loader
     * @param artifact the exact reviewed JAR
     * @param reference the official {@code GLineRenderer} bytes
     */
    public LineRendererUploadSplitTransformer(final ClassLoader loader, final Path artifact, final byte[] reference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(reference, "reference");
        shape = ReviewedMethodShape.read(
                reference,
                LineRendererUploadSplitTarget.OWNER,
                LineRendererUploadSplitTarget.METHOD,
                LineRendererUploadSplitTarget.DESCRIPTOR);
        if (shape == null) {
            throw new IllegalArgumentException("reviewed line-renderer update method absent");
        }
    }

    /** Returns the latest rejection, or null. */
    public String failure() {
        return failure;
    }

    /** Returns the number of rewritten classes. */
    public int matches() {
        return matches;
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
        if (actualLoader != loader || !LineRendererUploadSplitTarget.OWNER.equals(name) || bytes == null) {
            return null;
        }
        try {
            if (domain == null
                    || domain.getCodeSource() == null
                    || !artifact.equals(
                            Path.of(domain.getCodeSource().getLocation().toURI())
                                    .toAbsolutePath()
                                    .normalize())) {
                failure = "line-renderer source mismatch: " + name;
                return null;
            }
            if (!shape.equals(ReviewedMethodShape.read(
                    bytes, name, LineRendererUploadSplitTarget.METHOD, LineRendererUploadSplitTarget.DESCRIPTOR))) {
                failure = "GLineRenderer.updateMeshData shape changed";
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
                            if (!LineRendererUploadSplitTarget.METHOD.equals(method)
                                    || !LineRendererUploadSplitTarget.DESCRIPTOR.equals(descriptor)) {
                                return visitor;
                            }
                            applied[0] = true;
                            return new MethodVisitor(Opcodes.ASM9, visitor) {
                                @Override
                                public void visitCode() {
                                    super.visitCode();
                                    emitEntryGuard();
                                }

                                private void emitEntryGuard() {
                                    final Label start = new Label(), end = new Label(), handler = new Label();
                                    final Label discard = new Label(), nativePath = new Label();
                                    visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
                                    visitLabel(start);
                                    visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            "java/lang/System",
                                            "getProperties",
                                            "()Ljava/util/Properties;",
                                            false);
                                    visitLdcInsn(LineRendererUploadSplitBridge.PREDICATE_PROPERTY);
                                    visitMethodInsn(
                                            Opcodes.INVOKEVIRTUAL,
                                            "java/util/Properties",
                                            "get",
                                            "(Ljava/lang/Object;)Ljava/lang/Object;",
                                            false);
                                    visitInsn(Opcodes.DUP);
                                    visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Predicate");
                                    visitJumpInsn(Opcodes.IFEQ, discard);
                                    visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate");
                                    visitVarInsn(Opcodes.ALOAD, 0);
                                    visitMethodInsn(
                                            Opcodes.INVOKEINTERFACE,
                                            "java/util/function/Predicate",
                                            "test",
                                            "(Ljava/lang/Object;)Z",
                                            true);
                                    visitJumpInsn(Opcodes.IFEQ, nativePath);
                                    visitInsn(Opcodes.RETURN);
                                    visitLabel(end);
                                    visitLabel(discard);
                                    visitInsn(Opcodes.POP);
                                    visitJumpInsn(Opcodes.GOTO, nativePath);
                                    visitLabel(handler);
                                    visitInsn(Opcodes.POP);
                                    visitLabel(nativePath);
                                }
                            };
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (!applied[0]) {
                failure = "GLineRenderer.updateMeshData absent";
                return null;
            }
            final byte[] result = writer.toByteArray();
            if (beforeSha256 == null) {
                beforeSha256 = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            }
            matches++;
            return result;
        } catch (Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure = rejected + " at " + (trace.length == 0 ? "<no frames>" : trace[0].toString());
            return null;
        }
    }
}
