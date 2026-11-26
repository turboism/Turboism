package dev.turboism.adapter.cubism.optimization.paramrow;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.SlotBridgeEmitter;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites the Parameter Palette controller update method to gate full-palette row syncs and
 * repaints behind incremental per-row state checks, memoize morph-target key extraction per model
 * epoch, and invalidate cached snapshots on structure or morph-target edits.
 */
public final class ParameterRowRepaintTransformer implements ClassFileTransformer {

    /** Total number of controller instrumentation sites required in a valid rewrite. */
    public static final int EXPECTED_SITES = 3;

    private static final int CONTROLLER_EXPECTED_SITES = 3;
    private static final int MORPH_INFO_EXPECTED_SITES = 1;
    private static final int MORPH_SET_EXPECTED_SITES = 7;

    private static final String SYSTEM_OWNER = "java/lang/System";
    private static final String GET_PROPERTIES_METHOD = "getProperties";
    private static final String GET_PROPERTIES_DESCRIPTOR = "()Ljava/util/Properties;";
    private static final String PROPERTIES_OWNER = "java/util/Properties";
    private static final String GET_PROPERTY_METHOD = "get";
    private static final String GET_PROPERTY_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String PREDICATE_OWNER = "java/util/function/Predicate";
    private static final String TEST_METHOD = "test";
    private static final String TEST_DESCRIPTOR = "(Ljava/lang/Object;)Z";
    private static final String CONSUMER_OWNER = "java/util/function/Consumer";
    private static final String ACCEPT_METHOD = "accept";
    private static final String ACCEPT_DESCRIPTOR = "(Ljava/lang/Object;)V";
    private static final String OBJECT_OWNER = "java/lang/Object";
    private static final String LOGGER_METHOD_NAME = "a";
    private static final String LOGGER_METHOD_DESCRIPTOR = "(Ljava/lang/String;)V";
    private static final String METHOD_HANDLE_OWNER = "java/lang/invoke/MethodHandle";
    private static final String INVOKE_EXACT_METHOD = "invokeExact";
    private static final String MORPH_KEYS_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String VOID_METHOD_DESCRIPTOR = "()V";

    private static final Set<String> MORPH_SET_MUTATORS = Set.of(
            "add(Lcom/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget;)V",
            "addAll(Ljava/lang/Iterable;)V",
            "remove(Lcom/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget;)V",
            "remove(Ljava/lang/Iterable;)V",
            "clear()V",
            "replaceParameter(Lcom/live2d/type/CParameterGuid;Lcom/live2d/type/CParameterGuid;)V",
            "reinit(Lcom/live2d/core/ICopyable;Lcom/live2d/core/a;)V");

    private static final Set<String> MORPH_TARGET_LEGACY_MUTATORS = Set.of(
            "bind(Lcom/live2d/type/CParameterGuid;Ljava/lang/Float;Lcom/live2d/cubism/doc/model/ACForm;)V",
            "reinit(Lcom/live2d/core/ICopyable;Lcom/live2d/core/a;)V",
            "deserialize(Lorg/jdom/Element;Lcom/live2d/serialize/XmlReader;)V",
            "access$setParameterGuid$p(Lcom/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget;"
                    + "Lcom/live2d/type/CParameterGuid;)V");

    private static final Set<String> MORPH_TARGET_MODERN_MUTATORS = Set.of(
            "setParameterAndKeyValue(Lcom/live2d/type/CParameterGuid;Ljava/lang/Float;)V",
            "setParameter(Lcom/live2d/type/CParameterGuid;)V",
            "reinit(Lcom/live2d/core/ICopyable;Lcom/live2d/core/a;)V",
            "deserialize(Lorg/jdom/Element;Lcom/live2d/serialize/XmlReader;)V",
            "access$setParameterGuid$p(Lcom/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget;"
                    + "Lcom/live2d/type/CParameterGuid;)V");

    private static final int CONTROLLER_LOCAL_SLOT = 0;
    private static final int MODEL_LOCAL_SLOT = 3;
    private static final int DOC_LOCAL_SLOT = 4;
    private static final int MORPH_PARAM_LOCAL_SLOT = 2;
    private static final int MORPH_MODEL_LOCAL_SLOT = 1;
    private static final int UPDATE_ARG_COUNT = 3;
    private static final int ARG_CONTROLLER_INDEX = 0;
    private static final int ARG_DOC_INDEX = 1;
    private static final int ARG_MODEL_INDEX = 2;

    private final ClassLoader loader;
    private final Path artifact;
    private final ParameterRowRepaintTarget target;
    private final Set<String> allowedOwners;
    private final String morphRowInfoOwner;
    private final List<String> updateShape;
    private final Set<String> transformedOwners = ConcurrentHashMap.newKeySet();

    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Captures the reference method shape of the reviewed palette controller update method.
     *
     * @param loader admitted host class loader
     * @param artifact official host JAR path
     * @param reference reference bytes of the reviewed palette controller class
     * @param target reviewed version target descriptors
     */
    public ParameterRowRepaintTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] reference,
            final ParameterRowRepaintTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(reference, "reference");
        this.target = Objects.requireNonNull(target, "target");
        this.allowedOwners = Set.copyOf(target.rewrittenOwners());
        this.morphRowInfoOwner = target.morphRowInfoOwner();
        updateShape = ReviewedMethodShape.read(
                reference,
                target.controllerOwner(),
                ParameterRowRepaintTarget.UPDATE_METHOD,
                ParameterRowRepaintTarget.UPDATE_DESCRIPTOR);
        if (updateShape == null) {
            throw new IllegalArgumentException(target.controllerOwner() + ".a(Z)V absent from reference");
        }
    }

    /** Returns the latest fail-closed rejection reason, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns the number of rewritten classes. */
    public int matches() {
        return matches;
    }

    /** Returns {@code 1} when {@code internalName} has already been woven, else {@code 0}. */
    public int matchesFor(final String internalName) {
        return transformedOwners.contains(internalName) ? 1 : 0;
    }

    /** Returns the total expected weave site count across all four owners for this host version. */
    public int expectedTotalSites() {
        return target.expectedSites();
    }

    /** Returns the total count of instrumented selection, structure, and morph-target sites. */
    public int sites() {
        return sites;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (bytes == null || name == null || !allowedOwners.contains(name)) {
            return null;
        }
        if (actualLoader != loader || !HostCodeSource.matchesArtifact(domain, artifact)) {
            failure = "unexpected loader or code source for " + name;
            return null;
        }
        final int expectedSites = expectedSitesForOwner(name);
        if (target.controllerOwner().equals(name)
                && !updateShape.equals(ReviewedMethodShape.read(
                        bytes,
                        target.controllerOwner(),
                        ParameterRowRepaintTarget.UPDATE_METHOD,
                        ParameterRowRepaintTarget.UPDATE_DESCRIPTOR))) {
            failure = target.controllerOwner() + ".a(Z)V method shape mismatch";
            return null;
        }
        try {
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
            final int[] localSites = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String methodName,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor mv =
                                    super.visitMethod(access, methodName, descriptor, signature, exceptions);
                            return selectMethodVisitor(name, methodName, descriptor, mv, localSites);
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (localSites[0] != expectedSites) {
                failure = name + " site count mismatch: " + localSites[0] + " != " + expectedSites;
                return null;
            }
            final byte[] output = writer.toByteArray();
            if (transformedOwners.add(name)) {
                matches++;
                sites += localSites[0];
            }
            return output;
        } catch (final Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private int expectedSitesForOwner(final String name) {
        if (target.controllerOwner().equals(name)) {
            return CONTROLLER_EXPECTED_SITES;
        }
        if (morphRowInfoOwner.equals(name)) {
            return MORPH_INFO_EXPECTED_SITES;
        }
        if (ParameterRowRepaintTarget.MORPH_TARGET_SET_IMPL_OWNER.equals(name)) {
            return MORPH_SET_EXPECTED_SITES;
        }
        return target.morphTargetExpectedSites();
    }

    private MethodVisitor selectMethodVisitor(
            final String owner,
            final String methodName,
            final String descriptor,
            final MethodVisitor delegate,
            final int[] localSites) {
        if (delegate == null) {
            return null;
        }
        if (target.controllerOwner().equals(owner)) {
            if (ParameterRowRepaintTarget.UPDATE_METHOD.equals(methodName)
                    && ParameterRowRepaintTarget.UPDATE_DESCRIPTOR.equals(descriptor)) {
                return wrapUpdateMethod(delegate, localSites);
            }
            return delegate;
        }
        if (morphRowInfoOwner.equals(owner)) {
            if (ParameterRowRepaintTarget.MORPH_ROW_INFO_METHOD.equals(methodName)
                    && ParameterRowRepaintTarget.MORPH_ROW_INFO_DESCRIPTOR.equals(descriptor)) {
                return wrapMorphRowInfoMethod(delegate, localSites);
            }
            return delegate;
        }
        final String key = methodName + descriptor;
        if (ParameterRowRepaintTarget.MORPH_TARGET_SET_IMPL_OWNER.equals(owner)) {
            return MORPH_SET_MUTATORS.contains(key) ? wrapMorphInvalidateMethod(delegate, localSites) : delegate;
        }
        if (ParameterRowRepaintTarget.MORPH_TARGET_IMPL_OWNER.equals(owner)) {
            final Set<String> mutators =
                    target.usesLegacyMorphTargetBind() ? MORPH_TARGET_LEGACY_MUTATORS : MORPH_TARGET_MODERN_MUTATORS;
            return mutators.contains(key) ? wrapMorphInvalidateMethod(delegate, localSites) : delegate;
        }
        return delegate;
    }

    private static MethodVisitor wrapMorphRowInfoMethod(final MethodVisitor delegate, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                final Label skipPop = new Label();
                final Label originalBody = new Label();
                SlotBridgeEmitter.emitMethodHandleConsult(mv, ParameterRowRepaintBridge.MORPH_KEYS_PROPERTY, skipPop);
                mv.visitVarInsn(Opcodes.ALOAD, CONTROLLER_LOCAL_SLOT);
                mv.visitVarInsn(Opcodes.ALOAD, MORPH_MODEL_LOCAL_SLOT);
                mv.visitVarInsn(Opcodes.ALOAD, MORPH_PARAM_LOCAL_SLOT);
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, MORPH_KEYS_DESCRIPTOR, false);
                mv.visitJumpInsn(Opcodes.IFEQ, originalBody);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitLabel(skipPop);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(originalBody);
                localSites[0]++;
            }
        };
    }

    private static MethodVisitor wrapMorphInvalidateMethod(final MethodVisitor delegate, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            private boolean recorded;

            @Override
            public void visitInsn(final int opcode) {
                if (opcode == Opcodes.RETURN) {
                    final Label skipPop = new Label();
                    final Label returnDone = new Label();
                    SlotBridgeEmitter.emitMethodHandleConsult(
                            mv, ParameterRowRepaintBridge.MORPH_INVALIDATE_PROPERTY, skipPop);
                    mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            METHOD_HANDLE_OWNER,
                            INVOKE_EXACT_METHOD,
                            VOID_METHOD_DESCRIPTOR,
                            false);
                    mv.visitJumpInsn(Opcodes.GOTO, returnDone);
                    mv.visitLabel(skipPop);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitLabel(returnDone);
                    if (!recorded) {
                        recorded = true;
                        localSites[0]++;
                    }
                }
                super.visitInsn(opcode);
            }
        };
    }

    private MethodVisitor wrapUpdateMethod(final MethodVisitor delegate, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            private final Label selectionEnd = new Label();
            private String pendingMarker;
            private boolean selectionStartSeen;
            private boolean selectionEndPlaced;
            private boolean invalidateSeen;

            @Override
            public void visitLdcInsn(final Object value) {
                super.visitLdcInsn(value);
                if (ParameterRowRepaintTarget.SELECTION_LOG_MARKER.equals(value)
                        || ParameterRowRepaintTarget.STRUCTURE_LOG_MARKER.equals(value)) {
                    pendingMarker = (String) value;
                } else {
                    pendingMarker = null;
                }
            }

            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean isInterface) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && LOGGER_METHOD_NAME.equals(name)
                        && LOGGER_METHOD_DESCRIPTOR.equals(descriptor)
                        && pendingMarker != null) {
                    final String marker = pendingMarker;
                    pendingMarker = null;
                    if (ParameterRowRepaintTarget.SELECTION_LOG_MARKER.equals(marker) && !selectionStartSeen) {
                        emitSelectionGate(mv, selectionEnd);
                        selectionStartSeen = true;
                        localSites[0]++;
                    } else if (ParameterRowRepaintTarget.STRUCTURE_LOG_MARKER.equals(marker) && !invalidateSeen) {
                        emitInvalidateCall(mv);
                        invalidateSeen = true;
                        localSites[0]++;
                    }
                    return;
                }
                pendingMarker = null;
                if (selectionStartSeen
                        && !selectionEndPlaced
                        && opcode == Opcodes.INVOKEVIRTUAL
                        && target.controllerOwner().equals(owner)
                        && ParameterRowRepaintTarget.VALUE_SYNC_METHOD.equals(name)
                        && ParameterRowRepaintTarget.VALUE_SYNC_DESCRIPTOR.equals(descriptor)) {
                    mv.visitLabel(selectionEnd);
                    selectionEndPlaced = true;
                    localSites[0]++;
                }
            }

            @Override
            public void visitInsn(final int opcode) {
                pendingMarker = null;
                super.visitInsn(opcode);
            }

            @Override
            public void visitIntInsn(final int opcode, final int operand) {
                pendingMarker = null;
                super.visitIntInsn(opcode, operand);
            }

            @Override
            public void visitVarInsn(final int opcode, final int varIndex) {
                pendingMarker = null;
                super.visitVarInsn(opcode, varIndex);
            }

            @Override
            public void visitTypeInsn(final int opcode, final String type) {
                pendingMarker = null;
                super.visitTypeInsn(opcode, type);
            }

            @Override
            public void visitFieldInsn(
                    final int opcode, final String owner, final String name, final String descriptor) {
                pendingMarker = null;
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }

            @Override
            public void visitLabel(final Label label) {
                pendingMarker = null;
                super.visitLabel(label);
            }

            @Override
            public void visitJumpInsn(final int opcode, final Label label) {
                pendingMarker = null;
                super.visitJumpInsn(opcode, label);
            }
        };
    }

    private static void emitPropertyLookup(
            final MethodVisitor mv, final String propertyKey, final String targetInterface, final Label skipLabel) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SYSTEM_OWNER, GET_PROPERTIES_METHOD, GET_PROPERTIES_DESCRIPTOR, false);
        mv.visitLdcInsn(propertyKey);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PROPERTIES_OWNER, GET_PROPERTY_METHOD, GET_PROPERTY_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, targetInterface);
        mv.visitJumpInsn(Opcodes.IFEQ, skipLabel);
        mv.visitTypeInsn(Opcodes.CHECKCAST, targetInterface);
    }

    private static void emitSelectionGate(final MethodVisitor mv, final Label selectionEnd) {
        final Label skipBridge = new Label();
        final Label gateDone = new Label();
        emitPropertyLookup(mv, ParameterRowRepaintBridge.SELECTION_UPDATE_PROPERTY, PREDICATE_OWNER, skipBridge);
        mv.visitIntInsn(Opcodes.BIPUSH, UPDATE_ARG_COUNT);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, OBJECT_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0 + ARG_CONTROLLER_INDEX);
        mv.visitVarInsn(Opcodes.ALOAD, CONTROLLER_LOCAL_SLOT);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0 + ARG_DOC_INDEX);
        mv.visitVarInsn(Opcodes.ALOAD, DOC_LOCAL_SLOT);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0 + ARG_MODEL_INDEX);
        mv.visitVarInsn(Opcodes.ALOAD, MODEL_LOCAL_SLOT);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PREDICATE_OWNER, TEST_METHOD, TEST_DESCRIPTOR, true);
        mv.visitJumpInsn(Opcodes.IFNE, selectionEnd);
        mv.visitJumpInsn(Opcodes.GOTO, gateDone);
        mv.visitLabel(skipBridge);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(gateDone);
    }

    private static void emitInvalidateCall(final MethodVisitor mv) {
        final Label skipInvalidate = new Label();
        final Label invalidateDone = new Label();
        emitPropertyLookup(mv, ParameterRowRepaintBridge.INVALIDATE_PROPERTY, CONSUMER_OWNER, skipInvalidate);
        mv.visitVarInsn(Opcodes.ALOAD, CONTROLLER_LOCAL_SLOT);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONSUMER_OWNER, ACCEPT_METHOD, ACCEPT_DESCRIPTOR, true);
        mv.visitJumpInsn(Opcodes.GOTO, invalidateDone);
        mv.visitLabel(skipInvalidate);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(invalidateDone);
    }
}
