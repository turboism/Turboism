package dev.turboism.adapter.cubism.optimization.renderpass;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Eliminates Kotlin structural equality overhead in {@code GEntity.getRenderer()} across all
 * reviewed Cubism releases and replaces intermediate list snapshots in
 * {@code GMaterial.clearValues} with in-place {@code Iterator.remove()} on Cubism 5.3.x.
 */
public final class RenderEntityMaterialTransformer implements ClassFileTransformer {

    /** Internal name of {@code GMaterial}. */
    public static final String MATERIAL_OWNER = "com/live2d/graphics3d/material/GMaterial";

    /** Reviewed {@code GMaterial.clearValues} method name. */
    public static final String CLEAR_VALUES_METHOD = "clearValues";

    /** Descriptor of {@code GMaterial.clearValues(Function2)}. */
    public static final String CLEAR_VALUES_DESCRIPTOR = "(Lkotlin/jvm/functions/Function2;)V";

    private static final String GET_RENDERER_METHOD = "getRenderer";
    private static final String GET_RENDERER_DESCRIPTOR = "()Lcom/live2d/graphics3d/component/AGRenderer;";
    private static final String INTRINSICS_OWNER = "kotlin/jvm/internal/Intrinsics";
    private static final String ARE_EQUAL_METHOD = "areEqual";
    private static final String ARE_EQUAL_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String CHECK_NOT_NULL_PARAMETER_METHOD = "checkNotNullParameter";
    private static final String CHECK_NOT_NULL_PARAMETER_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/String;)V";

    private static final String COLLECTIONS_KT_OWNER = "kotlin/collections/CollectionsKt";
    private static final String TO_LIST_METHOD = "toList";
    private static final String TO_LIST_DESCRIPTOR = "(Ljava/lang/Iterable;)Ljava/util/List;";
    private static final String HASH_MAP_OWNER = "java/util/HashMap";
    private static final String HASH_MAP_DESC = "Ljava/util/HashMap;";
    private static final String FIELD_UNIFORM_MAP = "uniformMap";
    private static final String HASH_MAP_KEY_SET = "keySet";
    private static final String HASH_MAP_KEY_SET_DESC = "()Ljava/util/Set;";
    private static final String HASH_MAP_REMOVE = "remove";
    private static final String HASH_MAP_REMOVE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String SET_OWNER = "java/util/Set";
    private static final String ITERATOR_METHOD = "iterator";
    private static final String ITERATOR_METHOD_DESC = "()Ljava/util/Iterator;";
    private static final String ITERATOR_OWNER = "java/util/Iterator";
    private static final String HAS_NEXT_METHOD = "hasNext";
    private static final String HAS_NEXT_DESC = "()Z";
    private static final String NEXT_METHOD = "next";
    private static final String NEXT_DESC = "()Ljava/lang/Object;";
    private static final String ITERATOR_REMOVE_METHOD = "remove";
    private static final String VOID_DESC = "()V";
    private static final String PAIR_OWNER = "kotlin/Pair";
    private static final String PAIR_GET_FIRST = "getFirst";
    private static final String PAIR_GET_SECOND = "getSecond";
    private static final String FUNCTION2_OWNER = "kotlin/jvm/functions/Function2";
    private static final String FUNCTION2_INVOKE = "invoke";
    private static final String FUNCTION2_INVOKE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String BOOLEAN_OWNER = "java/lang/Boolean";
    private static final String BOOLEAN_VALUE = "booleanValue";
    private static final String BOOLEAN_VALUE_DESC = "()Z";

    private static final int EXPECTED_ENTITY_EQUALITY_SITES = 1;
    private static final int CLEAR_VALUES_IT_LOCAL = 2;
    private static final int CLEAR_VALUES_PAIR_LOCAL = 3;

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> entityRendererShape;
    private final List<String> materialClearValuesShape;
    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Captures exact reference method shapes for {@code GEntity.getRenderer()} and optionally
     * {@code GMaterial.clearValues(Function2)}.
     *
     * @param loader admitted host class loader
     * @param artifact expected host JAR archive
     * @param entityReference reference bytes of {@code GEntity}
     * @param materialReference reference bytes of {@code GMaterial}, or {@code null} when {@code clearValues} is absent
     * @throws IllegalArgumentException when a required reviewed method shape is missing or unexpected
     */
    public RenderEntityMaterialTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] entityReference,
            final byte[] materialReference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(entityReference, "entityReference");

        entityRendererShape = ReviewedMethodShape.read(
                entityReference, RenderPassTraversalTarget.ENTITY_OWNER, GET_RENDERER_METHOD, GET_RENDERER_DESCRIPTOR);
        if (entityRendererShape == null) {
            throw new IllegalArgumentException("reviewed GEntity.getRenderer method absent");
        }
        if (countEntitySentinelChecks(entityReference) != EXPECTED_ENTITY_EQUALITY_SITES) {
            throw new IllegalArgumentException("unexpected Intrinsics.areEqual site count in GEntity.getRenderer");
        }

        if (materialReference != null) {
            materialClearValuesShape = ReviewedMethodShape.read(
                    materialReference, MATERIAL_OWNER, CLEAR_VALUES_METHOD, CLEAR_VALUES_DESCRIPTOR);
            if (materialClearValuesShape == null) {
                throw new IllegalArgumentException("reviewed GMaterial.clearValues method absent");
            }
            verifyClearValuesPattern(materialReference);
        } else {
            materialClearValuesShape = null;
        }
    }

    /**
     * Returns the latest fail-closed rejection reason, or {@code null}.
     *
     * @return failure detail or {@code null}
     */
    public String failure() {
        return failure;
    }

    /**
     * Returns the number of rewritten classes observed so far.
     *
     * @return matched class count
     */
    public int matches() {
        return matches;
    }

    /**
     * Returns the number of rewritten methods observed so far.
     *
     * @return rewritten method count
     */
    public int sites() {
        return sites;
    }

    /**
     * Returns the expected rewritten class count for this host generation.
     *
     * @return {@code 2} when {@code GMaterial.clearValues} exists, otherwise {@code 1}
     */
    public int expectedMatches() {
        return materialClearValuesShape == null ? 1 : 2;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        final boolean isEntity = RenderPassTraversalTarget.ENTITY_OWNER.equals(name);
        final boolean isMaterial = materialClearValuesShape != null && MATERIAL_OWNER.equals(name);
        if (!isEntity && !isMaterial) {
            return null;
        }
        if (actualLoader != loader || !HostCodeSource.matchesArtifact(domain, artifact)) {
            failure = "unexpected loader or code source for " + name;
            return null;
        }
        try {
            return isEntity ? transformEntity(bytes) : transformMaterial(bytes);
        } catch (final Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private byte[] transformEntity(final byte[] bytes) {
        final List<String> observed = ReviewedMethodShape.read(
                bytes, RenderPassTraversalTarget.ENTITY_OWNER, GET_RENDERER_METHOD, GET_RENDERER_DESCRIPTOR);
        if (!entityRendererShape.equals(observed)) {
            throw new IllegalArgumentException("GEntity.getRenderer shape mismatch");
        }
        final ClassReader reader = HostClassWriters.newClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final int[] applied = {0};
        reader.accept(
                HostClassWriters.preservingClassVersion(bytes, new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor downstream =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!GET_RENDERER_METHOD.equals(name) || !GET_RENDERER_DESCRIPTOR.equals(descriptor)) {
                            return downstream;
                        }
                        return new IdentitySentinelRewriter(downstream, applied);
                    }
                }),
                ClassReader.EXPAND_FRAMES);
        if (applied[0] != EXPECTED_ENTITY_EQUALITY_SITES) {
            throw new IllegalStateException("GEntity.getRenderer rewrite sites != 1: " + applied[0]);
        }
        matches++;
        sites += applied[0];
        return writer.toByteArray();
    }

    private byte[] transformMaterial(final byte[] bytes) {
        final List<String> observed =
                ReviewedMethodShape.read(bytes, MATERIAL_OWNER, CLEAR_VALUES_METHOD, CLEAR_VALUES_DESCRIPTOR);
        if (!materialClearValuesShape.equals(observed)) {
            throw new IllegalArgumentException("GMaterial.clearValues shape mismatch");
        }
        final ClassReader reader = HostClassWriters.newClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final int[] applied = {0};
        reader.accept(
                HostClassWriters.preservingClassVersion(bytes, new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor downstream =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!CLEAR_VALUES_METHOD.equals(name) || !CLEAR_VALUES_DESCRIPTOR.equals(descriptor)) {
                            return downstream;
                        }
                        return new InPlaceClearValuesRewriter(downstream, applied);
                    }
                }),
                ClassReader.EXPAND_FRAMES);
        if (applied[0] != 1) {
            throw new IllegalStateException("GMaterial.clearValues rewrite sites != 1: " + applied[0]);
        }
        matches++;
        sites += applied[0];
        return writer.toByteArray();
    }

    private static int countEntitySentinelChecks(final byte[] bytes) {
        final int[] count = {0};
        HostClassWriters.newClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!GET_RENDERER_METHOD.equals(name) || !GET_RENDERER_DESCRIPTOR.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    private boolean previousAreEqual;

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String called,
                                            final String desc,
                                            final boolean itf) {
                                        if (previousAreEqual) {
                                            throw new IllegalStateException("unexpected method after areEqual");
                                        }
                                        previousAreEqual = isIntrinsicsAreEqual(opcode, owner, called, desc);
                                    }

                                    @Override
                                    public void visitJumpInsn(final int opcode, final Label label) {
                                        if (previousAreEqual) {
                                            if (opcode != Opcodes.IFEQ) {
                                                throw new IllegalStateException("expected IFEQ after areEqual");
                                            }
                                            count[0]++;
                                            previousAreEqual = false;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG);
        return count[0];
    }

    private static void verifyClearValuesPattern(final byte[] bytes) {
        final int[] counts = new int[2];
        HostClassWriters.newClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!CLEAR_VALUES_METHOD.equals(name) || !CLEAR_VALUES_DESCRIPTOR.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String called,
                                            final String desc,
                                            final boolean itf) {
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && COLLECTIONS_KT_OWNER.equals(owner)
                                                && TO_LIST_METHOD.equals(called)
                                                && TO_LIST_DESCRIPTOR.equals(desc)) {
                                            counts[0]++;
                                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                                && HASH_MAP_OWNER.equals(owner)
                                                && HASH_MAP_REMOVE.equals(called)
                                                && HASH_MAP_REMOVE_DESC.equals(desc)) {
                                            counts[1]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG);
        if (counts[0] != 1 || counts[1] != 1) {
            throw new IllegalArgumentException(
                    "unexpected GMaterial.clearValues pattern toList=" + counts[0] + " remove=" + counts[1]);
        }
    }

    private static boolean isIntrinsicsAreEqual(
            final int opcode, final String owner, final String name, final String descriptor) {
        return opcode == Opcodes.INVOKESTATIC
                && INTRINSICS_OWNER.equals(owner)
                && ARE_EQUAL_METHOD.equals(name)
                && ARE_EQUAL_DESCRIPTOR.equals(descriptor);
    }

    private static final class IdentitySentinelRewriter extends MethodVisitor {

        private final int[] applied;
        private boolean pendingAreEqual;

        IdentitySentinelRewriter(final MethodVisitor downstream, final int[] applied) {
            super(Opcodes.ASM9, downstream);
            this.applied = applied;
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            rejectPending();
            if (isIntrinsicsAreEqual(opcode, owner, name, descriptor)) {
                pendingAreEqual = true;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            if (pendingAreEqual) {
                if (opcode != Opcodes.IFEQ) {
                    throw new IllegalStateException("expected IFEQ after Intrinsics.areEqual");
                }
                // AGRenderer subclasses never override equals, so sentinel comparison is exact reference inequality.
                super.visitJumpInsn(Opcodes.IF_ACMPNE, label);
                pendingAreEqual = false;
                applied[0]++;
                return;
            }
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitInsn(final int opcode) {
            rejectPending();
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            rejectPending();
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitEnd() {
            rejectPending();
            super.visitEnd();
        }

        private void rejectPending() {
            if (pendingAreEqual) {
                throw new IllegalStateException("unpaired Intrinsics.areEqual in GEntity.getRenderer");
            }
        }
    }

    private static final class InPlaceClearValuesRewriter extends MethodVisitor {

        private final MethodVisitor target;
        private final int[] applied;

        InPlaceClearValuesRewriter(final MethodVisitor downstream, final int[] applied) {
            super(Opcodes.ASM9, null);
            this.target = downstream;
            this.applied = applied;
        }

        @Override
        public void visitAnnotableParameterCount(final int parameterCount, final boolean visible) {
            if (target != null) {
                target.visitAnnotableParameterCount(parameterCount, visible);
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
            return target == null ? null : target.visitAnnotation(descriptor, visible);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(
                final int parameter, final String descriptor, final boolean visible) {
            return target == null ? null : target.visitParameterAnnotation(parameter, descriptor, visible);
        }

        @Override
        public void visitEnd() {
            if (target == null) {
                return;
            }
            target.visitCode();
            target.visitVarInsn(Opcodes.ALOAD, 1);
            target.visitLdcInsn("");
            target.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    INTRINSICS_OWNER,
                    CHECK_NOT_NULL_PARAMETER_METHOD,
                    CHECK_NOT_NULL_PARAMETER_DESCRIPTOR,
                    false);

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, MATERIAL_OWNER, FIELD_UNIFORM_MAP, HASH_MAP_DESC);
            target.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, HASH_MAP_OWNER, HASH_MAP_KEY_SET, HASH_MAP_KEY_SET_DESC, false);
            target.visitMethodInsn(Opcodes.INVOKEINTERFACE, SET_OWNER, ITERATOR_METHOD, ITERATOR_METHOD_DESC, true);
            target.visitVarInsn(Opcodes.ASTORE, CLEAR_VALUES_IT_LOCAL);

            final Label loopHead = new Label();
            final Label loopDone = new Label();
            target.visitLabel(loopHead);
            target.visitVarInsn(Opcodes.ALOAD, CLEAR_VALUES_IT_LOCAL);
            target.visitMethodInsn(Opcodes.INVOKEINTERFACE, ITERATOR_OWNER, HAS_NEXT_METHOD, HAS_NEXT_DESC, true);
            target.visitJumpInsn(Opcodes.IFEQ, loopDone);

            target.visitVarInsn(Opcodes.ALOAD, CLEAR_VALUES_IT_LOCAL);
            target.visitMethodInsn(Opcodes.INVOKEINTERFACE, ITERATOR_OWNER, NEXT_METHOD, NEXT_DESC, true);
            target.visitTypeInsn(Opcodes.CHECKCAST, PAIR_OWNER);
            target.visitVarInsn(Opcodes.ASTORE, CLEAR_VALUES_PAIR_LOCAL);

            target.visitVarInsn(Opcodes.ALOAD, 1);
            target.visitVarInsn(Opcodes.ALOAD, CLEAR_VALUES_PAIR_LOCAL);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PAIR_OWNER, PAIR_GET_FIRST, NEXT_DESC, false);
            target.visitVarInsn(Opcodes.ALOAD, CLEAR_VALUES_PAIR_LOCAL);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PAIR_OWNER, PAIR_GET_SECOND, NEXT_DESC, false);
            target.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, FUNCTION2_OWNER, FUNCTION2_INVOKE, FUNCTION2_INVOKE_DESC, true);
            target.visitTypeInsn(Opcodes.CHECKCAST, BOOLEAN_OWNER);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BOOLEAN_OWNER, BOOLEAN_VALUE, BOOLEAN_VALUE_DESC, false);
            target.visitJumpInsn(Opcodes.IFEQ, loopHead);

            target.visitVarInsn(Opcodes.ALOAD, CLEAR_VALUES_IT_LOCAL);
            target.visitMethodInsn(Opcodes.INVOKEINTERFACE, ITERATOR_OWNER, ITERATOR_REMOVE_METHOD, VOID_DESC, true);
            target.visitJumpInsn(Opcodes.GOTO, loopHead);

            target.visitLabel(loopDone);
            target.visitInsn(Opcodes.RETURN);
            target.visitMaxs(0, 0);
            target.visitEnd();
            applied[0]++;
        }
    }
}
