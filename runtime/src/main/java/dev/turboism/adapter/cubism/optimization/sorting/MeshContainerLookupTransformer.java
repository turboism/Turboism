package dev.turboism.adapter.cubism.optimization.sorting;

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
 * Replaces the iterator-based {@code GMeshRenderer.getMeshContainer()} with an indexed
 * scan over the backing {@link java.util.List}.
 *
 * <p>The host method is a Kotlin {@code firstOrNull { it is GMeshContainer }} over
 * {@code entity.getComponents()}. {@code Components} implements {@link Iterable}, so the
 * host allocates one {@link java.util.Iterator} per call — roughly 7,500 per camera frame
 * on a heavy model. {@code Components.a()} exposes the same elements as a
 * {@link java.util.List}, allowing an allocation-free indexed loop with identical
 * first-match semantics and null fallback:</p>
 *
 * <pre>
 *   List&lt;?&gt; components = getEntity().getComponents().a();
 *   for (int i = 0, n = components.size(); i &lt; n; i++) {
 *       Object component = components.get(i);
 *       if (component instanceof GMeshContainer container) return container;
 *   }
 *   return null;
 * </pre>
 *
 * <p>The reviewed method shape must match the reference bytes and the loaded class must
 * originate from the exact reviewed artifact; otherwise the class is left untouched.</p>
 */
public final class MeshContainerLookupTransformer implements ClassFileTransformer {

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> shape;
    private volatile String beforeSha256, failure;
    private volatile int matches;

    /**
     * @param loader the host loader this instance is allowed to rewrite
     * @param artifact official artifact, compared to each candidate's code source
     * @param reference official {@link ClassReader#EXPAND_FRAMES} reference bytes of the owner
     * @throws IllegalArgumentException when the reviewed method is absent from the reference
     */
    public MeshContainerLookupTransformer(final ClassLoader loader, final Path artifact, final byte[] reference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(reference, "reference");
        shape = ReviewedMethodShape.read(
                reference,
                SortingInfoCacheTarget.MESH_RENDERER_OWNER,
                SortingInfoCacheTarget.MESH_CONTAINER_METHOD,
                SortingInfoCacheTarget.MESH_CONTAINER_DESCRIPTOR);
        if (shape == null) {
            throw new IllegalArgumentException("reviewed mesh-container method absent");
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
        if (actualLoader != loader || !SortingInfoCacheTarget.MESH_RENDERER_OWNER.equals(name) || bytes == null) {
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
                failure = "mesh-container source is not the reviewed artifact";
                return null;
            }
            if (!shape.equals(ReviewedMethodShape.read(
                    bytes,
                    SortingInfoCacheTarget.MESH_RENDERER_OWNER,
                    SortingInfoCacheTarget.MESH_CONTAINER_METHOD,
                    SortingInfoCacheTarget.MESH_CONTAINER_DESCRIPTOR))) {
                failure = "mesh-container method shape mismatch";
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
                            if (!SortingInfoCacheTarget.MESH_CONTAINER_METHOD.equals(method)
                                    || !SortingInfoCacheTarget.MESH_CONTAINER_DESCRIPTOR.equals(descriptor)) {
                                return visitor;
                            }
                            applied[0] = true;
                            return new BodyReplacementVisitor(visitor);
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (!applied[0]) {
                failure = "mesh-container method was not replaced";
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
     * Suppresses the original method body and emits the indexed scan. Code metadata
     * (line numbers, local variables and try/catch entries) is not copied because none of
     * it can refer to the new body; annotations outside the code attribute are forwarded.
     */
    private static final class BodyReplacementVisitor extends MethodVisitor {

        BodyReplacementVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitIndexedLookup();
        }

        @Override
        public void visitMaxs(final int stack, final int locals) {
            super.visitMaxs(0, 0);
        }

        @Override
        public void visitInsn(final int opcode) {}

        @Override
        public void visitIntInsn(final int opcode, final int operand) {}

        @Override
        public void visitVarInsn(final int opcode, final int variable) {}

        @Override
        public void visitTypeInsn(final int opcode, final String type) {}

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {}

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {}

        @Override
        public void visitInvokeDynamicInsn(
                final String name,
                final String descriptor,
                final org.objectweb.asm.Handle bootstrapMethodHandle,
                final Object... bootstrapMethodArguments) {}

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {}

        @Override
        public void visitLdcInsn(final Object value) {}

        @Override
        public void visitIincInsn(final int variable, final int increment) {}

        @Override
        public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {}

        @Override
        public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {}

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {}

        @Override
        public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {}

        @Override
        public void visitLabel(final Label label) {}

        @Override
        public void visitLineNumber(final int line, final Label start) {}

        @Override
        public void visitLocalVariable(
                final String name,
                final String descriptor,
                final String signature,
                final Label start,
                final Label end,
                final int index) {}

        @Override
        public void visitFrame(
                final int type, final int numLocal, final Object[] local, final int numStack, final Object[] stack) {}

        @Override
        public org.objectweb.asm.AnnotationVisitor visitInsnAnnotation(
                final int typeRef,
                final org.objectweb.asm.TypePath typePath,
                final String descriptor,
                final boolean visible) {
            return null;
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitTryCatchAnnotation(
                final int typeRef,
                final org.objectweb.asm.TypePath typePath,
                final String descriptor,
                final boolean visible) {
            return null;
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitLocalVariableAnnotation(
                final int typeRef,
                final org.objectweb.asm.TypePath typePath,
                final Label[] start,
                final Label[] end,
                final int[] index,
                final String descriptor,
                final boolean visible) {
            return null;
        }

        private void emitIndexedLookup() {
            final Label loop = new Label();
            final Label next = new Label();
            final Label done = new Label();

            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    SortingInfoCacheTarget.MESH_RENDERER_OWNER,
                    "getEntity",
                    "()Lcom/live2d/graphics3d/entity/GEntity;",
                    false);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    SortingInfoCacheTarget.ENTITY_OWNER,
                    "getComponents",
                    "()Lcom/live2d/graphics3d/entity/Components;",
                    false);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    SortingInfoCacheTarget.COMPONENTS_OWNER,
                    SortingInfoCacheTarget.COMPONENTS_LIST_METHOD,
                    SortingInfoCacheTarget.COMPONENTS_LIST_DESCRIPTOR,
                    false);
            super.visitVarInsn(Opcodes.ASTORE, 1);

            super.visitInsn(Opcodes.ICONST_0);
            super.visitVarInsn(Opcodes.ISTORE, 2);
            super.visitVarInsn(Opcodes.ALOAD, 1);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true);
            super.visitVarInsn(Opcodes.ISTORE, 3);

            super.visitLabel(loop);
            super.visitVarInsn(Opcodes.ILOAD, 2);
            super.visitVarInsn(Opcodes.ILOAD, 3);
            super.visitJumpInsn(Opcodes.IF_ICMPGE, done);

            super.visitVarInsn(Opcodes.ALOAD, 1);
            super.visitVarInsn(Opcodes.ILOAD, 2);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
            super.visitVarInsn(Opcodes.ASTORE, 4);

            super.visitVarInsn(Opcodes.ALOAD, 4);
            super.visitTypeInsn(Opcodes.INSTANCEOF, SortingInfoCacheTarget.MESH_CONTAINER_OWNER);
            super.visitJumpInsn(Opcodes.IFEQ, next);
            super.visitVarInsn(Opcodes.ALOAD, 4);
            super.visitTypeInsn(Opcodes.CHECKCAST, SortingInfoCacheTarget.MESH_CONTAINER_OWNER);
            super.visitInsn(Opcodes.ARETURN);

            super.visitLabel(next);
            super.visitIincInsn(2, 1);
            super.visitJumpInsn(Opcodes.GOTO, loop);

            super.visitLabel(done);
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitInsn(Opcodes.ARETURN);
        }
    }
}
