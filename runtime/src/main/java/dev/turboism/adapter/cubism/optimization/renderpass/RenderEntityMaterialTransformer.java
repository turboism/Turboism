package dev.turboism.adapter.cubism.optimization.renderpass;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.SlotBridgeEmitter;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * reviewed Cubism releases, replaces intermediate list snapshots in {@code GMaterial.clearValues}
 * with in-place {@code Iterator.remove()}, and reuses canonical immutable {@code kotlin.Pair} keys
 * in {@code GMaterial} uniform accessors on Cubism 5.3.x.
 */
public final class RenderEntityMaterialTransformer implements ClassFileTransformer {

    /** Internal name of {@code GMaterial}. */
    public static final String MATERIAL_OWNER = "com/live2d/graphics3d/material/GMaterial";

    /** Reviewed {@code GMaterial.clearValues} method name. */
    public static final String CLEAR_VALUES_METHOD = "clearValues";

    /** Descriptor of {@code GMaterial.clearValues(Function2)}. */
    public static final String CLEAR_VALUES_DESCRIPTOR = "(Lkotlin/jvm/functions/Function2;)V";

    /** Reviewed {@code GMaterial.setVector4} method name on Cubism 5.3.x. */
    public static final String SET_VECTOR4_METHOD = "setVector4";

    /** Descriptor of {@code GMaterial.setVector4(String, GVector4, Integer)}. */
    public static final String SET_VECTOR4_DESCRIPTOR =
            "(Ljava/lang/String;Lcom/live2d/graphics3d/type/GVector4;Ljava/lang/Integer;)V";

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
    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final String PAIR_INIT_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String PAIR_GET_FIRST = "getFirst";
    private static final String PAIR_GET_SECOND = "getSecond";
    private static final String FUNCTION2_OWNER = "kotlin/jvm/functions/Function2";
    private static final String FUNCTION2_INVOKE = "invoke";
    private static final String FUNCTION2_INVOKE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String BI_FUNCTION_OWNER = "java/util/function/BiFunction";
    private static final String BI_FUNCTION_APPLY = "apply";
    private static final String BI_FUNCTION_APPLY_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String BOOLEAN_OWNER = "java/lang/Boolean";
    private static final String BOOLEAN_VALUE = "booleanValue";
    private static final String BOOLEAN_VALUE_DESC = "()Z";

    private static final int PAIR_NAME_LOCAL = 1;
    private static final int GETTER_ID_LOCAL = 2;
    private static final int SETTER_ID_LOCAL = 3;
    private static final Map<MethodKey, Integer> PAIR_KEY_METHODS = buildPairKeyMethods();
    private static final int EXPECTED_PAIR_SITES = PAIR_KEY_METHODS.size();
    private static final int EXPECTED_ENTITY_EQUALITY_SITES = 1;
    private static final int CLEAR_VALUES_IT_LOCAL = 2;
    private static final int CLEAR_VALUES_PAIR_LOCAL = 3;

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> entityRendererShape;
    private final List<String> materialClearValuesShape;
    private final Map<MethodKey, List<String>> materialPairShapes;
    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Captures exact reference method shapes for {@code GEntity.getRenderer()} and optionally
     * {@code GMaterial} uniform-key and clear methods on Cubism 5.3.x.
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
            materialPairShapes = readPairMethodShapes(materialReference);
            verifyPairKeyPatterns(materialReference);
        } else {
            materialClearValuesShape = null;
            materialPairShapes = Map.of();
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
     * Returns the number of rewritten target classes whose sites verified and completed.
     *
     * @return verified rewritten target class count
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
        final List<String> observedClear =
                ReviewedMethodShape.read(bytes, MATERIAL_OWNER, CLEAR_VALUES_METHOD, CLEAR_VALUES_DESCRIPTOR);
        if (!materialClearValuesShape.equals(observedClear)) {
            throw new IllegalArgumentException("GMaterial.clearValues shape mismatch");
        }
        for (final Map.Entry<MethodKey, List<String>> expected : materialPairShapes.entrySet()) {
            final MethodKey key = expected.getKey();
            final List<String> observed = ReviewedMethodShape.read(bytes, MATERIAL_OWNER, key.name(), key.descriptor());
            if (!expected.getValue().equals(observed)) {
                throw new IllegalArgumentException("GMaterial." + key.name() + " shape mismatch");
            }
        }
        final ClassReader reader = HostClassWriters.newClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final int[] appliedClear = {0};
        final int[] appliedPairs = {0};
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
                        if (CLEAR_VALUES_METHOD.equals(name) && CLEAR_VALUES_DESCRIPTOR.equals(descriptor)) {
                            return new InPlaceClearValuesRewriter(downstream, appliedClear);
                        }
                        final Integer idLocal = PAIR_KEY_METHODS.get(new MethodKey(name, descriptor));
                        if (idLocal != null) {
                            return new PairKeyRewriter(downstream, idLocal, appliedPairs);
                        }
                        return downstream;
                    }
                }),
                ClassReader.EXPAND_FRAMES);
        if (appliedClear[0] != 1 || appliedPairs[0] != EXPECTED_PAIR_SITES) {
            throw new IllegalStateException(
                    "GMaterial rewrite sites clear=" + appliedClear[0] + " pairs=" + appliedPairs[0]);
        }
        matches++;
        sites++;
        return writer.toByteArray();
    }

    private static Map<MethodKey, Integer> buildPairKeyMethods() {
        final Map<MethodKey, Integer> methods = new LinkedHashMap<>();
        methods.put(
                new MethodKey("getFloat", "(Ljava/lang/String;Ljava/lang/Integer;)Ljava/lang/Float;"), GETTER_ID_LOCAL);
        methods.put(new MethodKey("setFloat", "(Ljava/lang/String;FLjava/lang/Integer;)V"), SETTER_ID_LOCAL);
        methods.put(new MethodKey("setInt", "(Ljava/lang/String;ILjava/lang/Integer;)V"), SETTER_ID_LOCAL);
        methods.put(
                new MethodKey("setColor", "(Ljava/lang/String;Lcom/live2d/type/CColor;Ljava/lang/Integer;)V"),
                SETTER_ID_LOCAL);
        methods.put(
                new MethodKey("getColor", "(Ljava/lang/String;Ljava/lang/Integer;)Lcom/live2d/type/CColor;"),
                GETTER_ID_LOCAL);
        methods.put(
                new MethodKey(
                        "setMatrix", "(Ljava/lang/String;Lcom/live2d/graphics3d/type/GMatrix44;Ljava/lang/Integer;)V"),
                SETTER_ID_LOCAL);
        methods.put(new MethodKey(SET_VECTOR4_METHOD, SET_VECTOR4_DESCRIPTOR), SETTER_ID_LOCAL);
        methods.put(
                new MethodKey(
                        "getVector4", "(Ljava/lang/String;Ljava/lang/Integer;)Lcom/live2d/graphics3d/type/GVector4;"),
                GETTER_ID_LOCAL);
        methods.put(
                new MethodKey(
                        "setVector3", "(Ljava/lang/String;Lcom/live2d/graphics3d/type/GVector3;Ljava/lang/Integer;)V"),
                SETTER_ID_LOCAL);
        methods.put(
                new MethodKey(
                        "setVector2", "(Ljava/lang/String;Lcom/live2d/graphics3d/type/GVector2;Ljava/lang/Integer;)V"),
                SETTER_ID_LOCAL);
        return Map.copyOf(methods);
    }

    private static Map<MethodKey, List<String>> readPairMethodShapes(final byte[] materialReference) {
        final Map<MethodKey, List<String>> shapes = new LinkedHashMap<>();
        for (final MethodKey key : PAIR_KEY_METHODS.keySet()) {
            final List<String> shape =
                    ReviewedMethodShape.read(materialReference, MATERIAL_OWNER, key.name(), key.descriptor());
            if (shape == null) {
                throw new IllegalArgumentException("reviewed GMaterial." + key.name() + " method absent");
            }
            shapes.put(key, shape);
        }
        return Map.copyOf(shapes);
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

    private static void verifyPairKeyPatterns(final byte[] bytes) {
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
                                final Integer idLocal = PAIR_KEY_METHODS.get(new MethodKey(name, descriptor));
                                if (idLocal == null) {
                                    return null;
                                }
                                return new PairPatternDetector(idLocal, count);
                            }
                        },
                        ClassReader.SKIP_DEBUG);
        if (count[0] != EXPECTED_PAIR_SITES) {
            throw new IllegalArgumentException("unexpected GMaterial Pair key site count: " + count[0]);
        }
    }

    private static boolean isIntrinsicsAreEqual(
            final int opcode, final String owner, final String name, final String descriptor) {
        return opcode == Opcodes.INVOKESTATIC
                && INTRINSICS_OWNER.equals(owner)
                && ARE_EQUAL_METHOD.equals(name)
                && ARE_EQUAL_DESCRIPTOR.equals(descriptor);
    }

    private record MethodKey(String name, String descriptor) {}

    private static class PairPatternDetector extends MethodVisitor {
        final int idLocal;
        final int[] matches;
        int stage;

        PairPatternDetector(final int idLocal, final int[] matches) {
            super(Opcodes.ASM9);
            this.idLocal = idLocal;
            this.matches = matches;
        }

        PairPatternDetector(final MethodVisitor downstream, final int idLocal, final int[] matches) {
            super(Opcodes.ASM9, downstream);
            this.idLocal = idLocal;
            this.matches = matches;
        }

        void onPatternMatched() {
            matches[0]++;
        }

        void flushPending() {
            stage = 0;
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            if (stage == 0 && opcode == Opcodes.NEW && PAIR_OWNER.equals(type)) {
                stage = 1;
                return;
            }
            flushPending();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (stage == 1 && opcode == Opcodes.DUP) {
                stage = 2;
                return;
            }
            flushPending();
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (stage == 2 && opcode == Opcodes.ALOAD && varIndex == PAIR_NAME_LOCAL) {
                stage = 3;
                return;
            }
            if (stage == 3 && opcode == Opcodes.ALOAD && varIndex == idLocal) {
                stage = 4;
                return;
            }
            flushPending();
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            if (stage == 4
                    && opcode == Opcodes.INVOKESPECIAL
                    && !itf
                    && PAIR_OWNER.equals(owner)
                    && CONSTRUCTOR_NAME.equals(name)
                    && PAIR_INIT_DESC.equals(descriptor)) {
                stage = 0;
                onPatternMatched();
                return;
            }
            flushPending();
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            flushPending();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            flushPending();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            flushPending();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            flushPending();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitIincInsn(final int varIndex, final int increment) {
            flushPending();
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitLabel(final Label label) {
            flushPending();
            super.visitLabel(label);
        }

        @Override
        public void visitFrame(
                final int type, final int numLocal, final Object[] local, final int numStack, final Object[] stack) {
            flushPending();
            super.visitFrame(type, numLocal, local, numStack, stack);
        }

        @Override
        public void visitEnd() {
            flushPending();
            super.visitEnd();
        }
    }

    private static final class PairKeyRewriter extends PairPatternDetector {

        PairKeyRewriter(final MethodVisitor downstream, final int idLocal, final int[] applied) {
            super(downstream, idLocal, applied);
        }

        @Override
        void flushPending() {
            if (stage == 0) {
                return;
            }
            if (stage >= 1) {
                mv.visitTypeInsn(Opcodes.NEW, PAIR_OWNER);
            }
            if (stage >= 2) {
                mv.visitInsn(Opcodes.DUP);
            }
            if (stage >= 3) {
                mv.visitVarInsn(Opcodes.ALOAD, PAIR_NAME_LOCAL);
            }
            if (stage >= 4) {
                mv.visitVarInsn(Opcodes.ALOAD, idLocal);
            }
            stage = 0;
        }

        @Override
        void onPatternMatched() {
            final Label fallback = new Label();
            final Label ready = new Label();
            SlotBridgeEmitter.emitGetSlot(mv, RenderPassTraversalBridge.UNIFORM_PAIR_PROPERTY);
            mv.visitInsn(Opcodes.DUP);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, BI_FUNCTION_OWNER);
            mv.visitJumpInsn(Opcodes.IFEQ, fallback);
            mv.visitTypeInsn(Opcodes.CHECKCAST, BI_FUNCTION_OWNER);
            mv.visitVarInsn(Opcodes.ALOAD, PAIR_NAME_LOCAL);
            mv.visitVarInsn(Opcodes.ALOAD, idLocal);
            mv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, BI_FUNCTION_OWNER, BI_FUNCTION_APPLY, BI_FUNCTION_APPLY_DESC, true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, PAIR_OWNER);
            mv.visitJumpInsn(Opcodes.GOTO, ready);
            mv.visitLabel(fallback);
            mv.visitInsn(Opcodes.POP);
            mv.visitTypeInsn(Opcodes.NEW, PAIR_OWNER);
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ALOAD, PAIR_NAME_LOCAL);
            mv.visitVarInsn(Opcodes.ALOAD, idLocal);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PAIR_OWNER, CONSTRUCTOR_NAME, PAIR_INIT_DESC, false);
            mv.visitLabel(ready);
            matches[0]++;
        }

        @Override
        public void visitLineNumber(final int line, final Label start) {
            if (stage == 0) {
                super.visitLineNumber(line, start);
            }
        }
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
