package dev.turboism.adapter.cubism.optimization.scenehandler;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.warpalt.WarpAltMirrorHostProfile;
import dev.turboism.adapter.cubism.warpalt.WarpAltMirrorNativeMethodTransformer;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites {@code CEViewContext}, {@code Entities}, and {@code Components} to reuse cached
 * depth-first mouse-handler lists and selectively invalidate on mouse-handler mutations.
 */
public final class SceneHandlerCacheTransformer implements ClassFileTransformer {

    private static final String FUNCTION = "java/util/function/Function";
    private static final String BIFUNCTION = "java/util/function/BiFunction";
    private static final String CONSUMER = "java/util/function/Consumer";
    private static final String JAVA_LIST = "java/util/List";
    private static final String COLLECTIONS_KT = "kotlin/collections/CollectionsKt";
    private static final String ADD_ALL_METHOD = "addAll";
    private static final String ADD_ALL_DESCRIPTOR = "(Ljava/util/Collection;Ljava/lang/Iterable;)Z";

    private static final int EXPECTED_VIEW_SITES = 1;
    private static final int EXPECTED_ENTITIES_SITES = 5;
    private static final int EXPECTED_COMPONENTS_SITES = 2;

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> inputEventShape;
    private final List<String> composedInputEventShape;
    private final List<String> entitiesAddShape;
    private final List<String> entitiesRemoveShape;
    private final List<String> entitiesRemoveAllShape;
    private final List<String> entitiesRemoveAtShape;
    private final List<String> entitiesRemoveIfShape;
    private final List<String> componentsAddShape;
    private final List<String> componentsRemoveShape;

    private final int handlerListSlot;
    private final int scratchSceneSlot;
    private final int scratchListSlot;

    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Captures reference method shapes and locates local variable slots from the official artifact.
     *
     * @param loader admitted host class loader
     * @param artifact official host JAR path
     * @param target reviewed host target metadata
     * @param viewReference reference bytes of {@code CEViewContext}
     * @param entitiesReference reference bytes of {@code Entities}
     * @param componentsReference reference bytes of {@code Components}
     */
    public SceneHandlerCacheTransformer(
            final ClassLoader loader,
            final Path artifact,
            final SceneHandlerCacheTarget target,
            final byte[] viewReference,
            final byte[] entitiesReference,
            final byte[] componentsReference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(viewReference, "viewReference");
        Objects.requireNonNull(entitiesReference, "entitiesReference");
        Objects.requireNonNull(componentsReference, "componentsReference");

        inputEventShape = ReviewedMethodShape.read(
                viewReference,
                SceneHandlerCacheTarget.VIEW_CONTEXT_OWNER,
                SceneHandlerCacheTarget.INPUT_EVENT_METHOD,
                SceneHandlerCacheTarget.INPUT_EVENT_DESCRIPTOR);
        composedInputEventShape = WarpAltMirrorHostProfile.forReviewedVersion(target.version())
                .map(profile ->
                        WarpAltMirrorNativeMethodTransformer.composedInputIngressShape(profile, viewReference, loader))
                .orElse(null);
        entitiesAddShape = ReviewedMethodShape.read(
                entitiesReference,
                SceneHandlerCacheTarget.ENTITIES_OWNER,
                SceneHandlerCacheTarget.ENTITIES_ADD_METHOD,
                SceneHandlerCacheTarget.ENTITIES_ADD_DESCRIPTOR);
        entitiesRemoveShape = ReviewedMethodShape.read(
                entitiesReference,
                SceneHandlerCacheTarget.ENTITIES_OWNER,
                SceneHandlerCacheTarget.ENTITIES_REMOVE_METHOD,
                SceneHandlerCacheTarget.ENTITIES_REMOVE_DESCRIPTOR);
        entitiesRemoveAllShape = ReviewedMethodShape.read(
                entitiesReference,
                SceneHandlerCacheTarget.ENTITIES_OWNER,
                SceneHandlerCacheTarget.ENTITIES_REMOVE_ALL_METHOD,
                SceneHandlerCacheTarget.ENTITIES_REMOVE_ALL_DESCRIPTOR);
        entitiesRemoveAtShape = ReviewedMethodShape.read(
                entitiesReference,
                SceneHandlerCacheTarget.ENTITIES_OWNER,
                SceneHandlerCacheTarget.ENTITIES_REMOVE_AT_METHOD,
                SceneHandlerCacheTarget.ENTITIES_REMOVE_AT_DESCRIPTOR);
        entitiesRemoveIfShape = ReviewedMethodShape.read(
                entitiesReference,
                SceneHandlerCacheTarget.ENTITIES_OWNER,
                SceneHandlerCacheTarget.ENTITIES_REMOVE_IF_METHOD,
                SceneHandlerCacheTarget.ENTITIES_REMOVE_IF_DESCRIPTOR);
        componentsAddShape = ReviewedMethodShape.read(
                componentsReference,
                SceneHandlerCacheTarget.COMPONENTS_OWNER,
                SceneHandlerCacheTarget.COMPONENTS_METHOD,
                SceneHandlerCacheTarget.COMPONENTS_ADD_DESCRIPTOR);
        componentsRemoveShape = ReviewedMethodShape.read(
                componentsReference,
                SceneHandlerCacheTarget.COMPONENTS_OWNER,
                SceneHandlerCacheTarget.COMPONENTS_METHOD,
                SceneHandlerCacheTarget.COMPONENTS_REMOVE_DESCRIPTOR);

        if (inputEventShape == null
                || entitiesAddShape == null
                || entitiesRemoveShape == null
                || entitiesRemoveAllShape == null
                || entitiesRemoveAtShape == null
                || entitiesRemoveIfShape == null
                || componentsAddShape == null
                || componentsRemoveShape == null) {
            throw new IllegalArgumentException("reviewed scene-handler cache methods absent");
        }

        final int[] slots = inspectInputEventSlots(viewReference);
        handlerListSlot = slots[0];
        scratchSceneSlot = slots[1];
        scratchListSlot = slots[1] + 1;
    }

    /** Returns the latest fail-closed rejection reason, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns the number of rewritten classes. */
    public int matches() {
        return matches;
    }

    /** Returns the total number of injected sites across all three owners. */
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
        if (bytes == null
                || (!SceneHandlerCacheTarget.VIEW_CONTEXT_OWNER.equals(name)
                        && !SceneHandlerCacheTarget.ENTITIES_OWNER.equals(name)
                        && !SceneHandlerCacheTarget.COMPONENTS_OWNER.equals(name))) {
            return null;
        }
        if (actualLoader != loader || !matchesArtifactSource(domain)) {
            failure = "unexpected loader or code source for " + name;
            return null;
        }
        try {
            if (SceneHandlerCacheTarget.VIEW_CONTEXT_OWNER.equals(name)) {
                return rewriteViewContext(bytes);
            }
            if (SceneHandlerCacheTarget.ENTITIES_OWNER.equals(name)) {
                return rewriteEntities(bytes);
            }
            return rewriteComponents(bytes);
        } catch (final Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private boolean matchesArtifactSource(final ProtectionDomain domain) {
        if (domain == null
                || domain.getCodeSource() == null
                || domain.getCodeSource().getLocation() == null) {
            return false;
        }
        try {
            return Path.of(domain.getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize()
                    .equals(artifact);
        } catch (final Exception ignored) {
            return false;
        }
    }

    private byte[] rewriteViewContext(final byte[] bytes) {
        final List<String> observed = ReviewedMethodShape.read(
                bytes,
                SceneHandlerCacheTarget.VIEW_CONTEXT_OWNER,
                SceneHandlerCacheTarget.INPUT_EVENT_METHOD,
                SceneHandlerCacheTarget.INPUT_EVENT_DESCRIPTOR);
        if (!inputEventShape.equals(observed)
                && (composedInputEventShape == null || !composedInputEventShape.equals(observed))) {
            failure = "CEViewContext.onInputEvent_exe method shape mismatch";
            return null;
        }
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = hostWriter(reader);
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
                        if (!SceneHandlerCacheTarget.INPUT_EVENT_METHOD.equals(methodName)
                                || !SceneHandlerCacheTarget.INPUT_EVENT_DESCRIPTOR.equals(descriptor)) {
                            return mv;
                        }
                        return wrapInputEvent(mv, localSites);
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (localSites[0] != EXPECTED_VIEW_SITES) {
            failure = "CEViewContext site count mismatch: " + localSites[0];
            return null;
        }
        final byte[] output = writer.toByteArray();
        matches++;
        sites += localSites[0];
        return output;
    }

    private MethodVisitor wrapInputEvent(final MethodVisitor downstream, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, downstream) {
            private boolean sawTraverse;
            private boolean sawAddAll;
            private boolean sawListCast;
            private boolean completed;
            private final Label fastStoreLabel = new Label();

            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean isInterface) {
                if (!sawTraverse
                        && opcode == Opcodes.INVOKEVIRTUAL
                        && SceneHandlerCacheTarget.ENTITY_OWNER.equals(owner)
                        && SceneHandlerCacheTarget.TRAVERSE_ALL_METHOD.equals(name)
                        && SceneHandlerCacheTarget.TRAVERSE_ALL_DESCRIPTOR.equals(descriptor)) {
                    sawTraverse = true;
                    super.visitInsn(Opcodes.DUP);
                    super.visitVarInsn(Opcodes.ASTORE, scratchSceneSlot);
                    emitPropertyLookup(mv, SceneHandlerCacheBridge.GET_PROPERTY);
                    super.visitInsn(Opcodes.DUP);
                    super.visitTypeInsn(Opcodes.INSTANCEOF, FUNCTION);
                    final Label noFunction = new Label();
                    final Label cacheMiss = new Label();
                    final Label invokeTraverse = new Label();
                    super.visitJumpInsn(Opcodes.IFEQ, noFunction);
                    super.visitTypeInsn(Opcodes.CHECKCAST, FUNCTION);
                    super.visitVarInsn(Opcodes.ALOAD, scratchSceneSlot);
                    super.visitMethodInsn(
                            Opcodes.INVOKEINTERFACE, FUNCTION, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                    super.visitInsn(Opcodes.DUP);
                    super.visitTypeInsn(Opcodes.INSTANCEOF, JAVA_LIST);
                    super.visitJumpInsn(Opcodes.IFEQ, cacheMiss);
                    super.visitTypeInsn(Opcodes.CHECKCAST, JAVA_LIST);
                    super.visitInsn(Opcodes.SWAP);
                    super.visitInsn(Opcodes.POP);
                    super.visitJumpInsn(Opcodes.GOTO, fastStoreLabel);
                    super.visitLabel(cacheMiss);
                    super.visitInsn(Opcodes.POP);
                    super.visitJumpInsn(Opcodes.GOTO, invokeTraverse);
                    super.visitLabel(noFunction);
                    super.visitInsn(Opcodes.POP);
                    super.visitLabel(invokeTraverse);
                } else if (sawTraverse
                        && !sawAddAll
                        && opcode == Opcodes.INVOKESTATIC
                        && COLLECTIONS_KT.equals(owner)
                        && ADD_ALL_METHOD.equals(name)
                        && ADD_ALL_DESCRIPTOR.equals(descriptor)) {
                    sawAddAll = true;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }

            @Override
            public void visitTypeInsn(final int opcode, final String type) {
                super.visitTypeInsn(opcode, type);
                if (sawAddAll && !sawListCast && opcode == Opcodes.CHECKCAST && JAVA_LIST.equals(type)) {
                    sawListCast = true;
                }
            }

            @Override
            public void visitVarInsn(final int opcode, final int varIndex) {
                if (sawListCast && !completed && opcode == Opcodes.ASTORE && varIndex == handlerListSlot) {
                    completed = true;
                    localSites[0]++;
                    super.visitVarInsn(Opcodes.ASTORE, scratchListSlot);
                    emitPropertyLookup(mv, SceneHandlerCacheBridge.PUT_PROPERTY);
                    super.visitInsn(Opcodes.DUP);
                    super.visitTypeInsn(Opcodes.INSTANCEOF, BIFUNCTION);
                    final Label skipPut = new Label();
                    final Label reloadResult = new Label();
                    super.visitJumpInsn(Opcodes.IFEQ, skipPut);
                    super.visitTypeInsn(Opcodes.CHECKCAST, BIFUNCTION);
                    super.visitVarInsn(Opcodes.ALOAD, scratchSceneSlot);
                    super.visitVarInsn(Opcodes.ALOAD, scratchListSlot);
                    super.visitMethodInsn(
                            Opcodes.INVOKEINTERFACE,
                            BIFUNCTION,
                            "apply",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                            true);
                    super.visitInsn(Opcodes.POP);
                    super.visitJumpInsn(Opcodes.GOTO, reloadResult);
                    super.visitLabel(skipPut);
                    super.visitInsn(Opcodes.POP);
                    super.visitLabel(reloadResult);
                    super.visitVarInsn(Opcodes.ALOAD, scratchListSlot);
                    super.visitLabel(fastStoreLabel);
                }
                super.visitVarInsn(opcode, varIndex);
            }
        };
    }

    private byte[] rewriteEntities(final byte[] bytes) {
        if (!entitiesAddShape.equals(ReviewedMethodShape.read(
                        bytes,
                        SceneHandlerCacheTarget.ENTITIES_OWNER,
                        SceneHandlerCacheTarget.ENTITIES_ADD_METHOD,
                        SceneHandlerCacheTarget.ENTITIES_ADD_DESCRIPTOR))
                || !entitiesRemoveShape.equals(ReviewedMethodShape.read(
                        bytes,
                        SceneHandlerCacheTarget.ENTITIES_OWNER,
                        SceneHandlerCacheTarget.ENTITIES_REMOVE_METHOD,
                        SceneHandlerCacheTarget.ENTITIES_REMOVE_DESCRIPTOR))
                || !entitiesRemoveAllShape.equals(ReviewedMethodShape.read(
                        bytes,
                        SceneHandlerCacheTarget.ENTITIES_OWNER,
                        SceneHandlerCacheTarget.ENTITIES_REMOVE_ALL_METHOD,
                        SceneHandlerCacheTarget.ENTITIES_REMOVE_ALL_DESCRIPTOR))
                || !entitiesRemoveAtShape.equals(ReviewedMethodShape.read(
                        bytes,
                        SceneHandlerCacheTarget.ENTITIES_OWNER,
                        SceneHandlerCacheTarget.ENTITIES_REMOVE_AT_METHOD,
                        SceneHandlerCacheTarget.ENTITIES_REMOVE_AT_DESCRIPTOR))
                || !entitiesRemoveIfShape.equals(ReviewedMethodShape.read(
                        bytes,
                        SceneHandlerCacheTarget.ENTITIES_OWNER,
                        SceneHandlerCacheTarget.ENTITIES_REMOVE_IF_METHOD,
                        SceneHandlerCacheTarget.ENTITIES_REMOVE_IF_DESCRIPTOR))) {
            failure = "Entities method shape mismatch";
            return null;
        }
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = hostWriter(reader);
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
                        if ((access & Opcodes.ACC_STATIC) != 0) {
                            return mv;
                        }
                        if ((SceneHandlerCacheTarget.ENTITIES_ADD_METHOD.equals(methodName)
                                        && SceneHandlerCacheTarget.ENTITIES_ADD_DESCRIPTOR.equals(descriptor))
                                || (SceneHandlerCacheTarget.ENTITIES_REMOVE_METHOD.equals(methodName)
                                        && SceneHandlerCacheTarget.ENTITIES_REMOVE_DESCRIPTOR.equals(descriptor))) {
                            localSites[0]++;
                            return wrapInvalidator(mv, 1);
                        }
                        if ((SceneHandlerCacheTarget.ENTITIES_REMOVE_ALL_METHOD.equals(methodName)
                                        && SceneHandlerCacheTarget.ENTITIES_REMOVE_ALL_DESCRIPTOR.equals(descriptor))
                                || (SceneHandlerCacheTarget.ENTITIES_REMOVE_AT_METHOD.equals(methodName)
                                        && SceneHandlerCacheTarget.ENTITIES_REMOVE_AT_DESCRIPTOR.equals(descriptor))
                                || (SceneHandlerCacheTarget.ENTITIES_REMOVE_IF_METHOD.equals(methodName)
                                        && SceneHandlerCacheTarget.ENTITIES_REMOVE_IF_DESCRIPTOR.equals(descriptor))) {
                            localSites[0]++;
                            return wrapInvalidator(mv, 0);
                        }
                        return mv;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (localSites[0] != EXPECTED_ENTITIES_SITES) {
            failure = "Entities site count mismatch: " + localSites[0];
            return null;
        }
        final byte[] output = writer.toByteArray();
        matches++;
        sites += localSites[0];
        return output;
    }

    private byte[] rewriteComponents(final byte[] bytes) {
        if (!componentsAddShape.equals(ReviewedMethodShape.read(
                        bytes,
                        SceneHandlerCacheTarget.COMPONENTS_OWNER,
                        SceneHandlerCacheTarget.COMPONENTS_METHOD,
                        SceneHandlerCacheTarget.COMPONENTS_ADD_DESCRIPTOR))
                || !componentsRemoveShape.equals(ReviewedMethodShape.read(
                        bytes,
                        SceneHandlerCacheTarget.COMPONENTS_OWNER,
                        SceneHandlerCacheTarget.COMPONENTS_METHOD,
                        SceneHandlerCacheTarget.COMPONENTS_REMOVE_DESCRIPTOR))) {
            failure = "Components method shape mismatch";
            return null;
        }
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = hostWriter(reader);
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
                        if ((access & Opcodes.ACC_STATIC) != 0
                                || !SceneHandlerCacheTarget.COMPONENTS_METHOD.equals(methodName)) {
                            return mv;
                        }
                        if (SceneHandlerCacheTarget.COMPONENTS_ADD_DESCRIPTOR.equals(descriptor)
                                || SceneHandlerCacheTarget.COMPONENTS_REMOVE_DESCRIPTOR.equals(descriptor)) {
                            localSites[0]++;
                            return wrapInvalidator(mv, 1);
                        }
                        return mv;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (localSites[0] != EXPECTED_COMPONENTS_SITES) {
            failure = "Components site count mismatch: " + localSites[0];
            return null;
        }
        final byte[] output = writer.toByteArray();
        matches++;
        sites += localSites[0];
        return output;
    }

    private ClassWriter hostWriter(final ClassReader reader) {
        return new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader == null ? super.getClassLoader() : loader;
            }

            @Override
            protected String getCommonSuperClass(final String left, final String right) {
                return HostClassWriters.commonSuperClass(loader, left, right);
            }
        };
    }

    private static MethodVisitor wrapInvalidator(final MethodVisitor downstream, final int argumentSlot) {
        return new MethodVisitor(Opcodes.ASM9, downstream) {
            @Override
            public void visitCode() {
                super.visitCode();
                emitPropertyLookup(mv, SceneHandlerCacheBridge.INVALIDATE_PROPERTY);
                super.visitInsn(Opcodes.DUP);
                super.visitTypeInsn(Opcodes.INSTANCEOF, CONSUMER);
                final Label skip = new Label();
                final Label done = new Label();
                super.visitJumpInsn(Opcodes.IFEQ, skip);
                super.visitTypeInsn(Opcodes.CHECKCAST, CONSUMER);
                super.visitVarInsn(Opcodes.ALOAD, argumentSlot);
                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONSUMER, "accept", "(Ljava/lang/Object;)V", true);
                super.visitJumpInsn(Opcodes.GOTO, done);
                super.visitLabel(skip);
                super.visitInsn(Opcodes.POP);
                super.visitLabel(done);
            }
        };
    }

    private static void emitPropertyLookup(final MethodVisitor mv, final String key) {
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
        mv.visitLdcInsn(key);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
    }

    private static int[] inspectInputEventSlots(final byte[] viewReference) {
        final int[] result = {-1, -1, 0, 0};
        new ClassReader(viewReference)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!SceneHandlerCacheTarget.INPUT_EVENT_METHOD.equals(name)
                                        || !SceneHandlerCacheTarget.INPUT_EVENT_DESCRIPTOR.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    private boolean afterAddAll;
                                    private boolean afterCastList;

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String methodName,
                                            final String methodDesc,
                                            final boolean isInterface) {
                                        if (opcode == Opcodes.INVOKEVIRTUAL
                                                && SceneHandlerCacheTarget.ENTITY_OWNER.equals(owner)
                                                && SceneHandlerCacheTarget.TRAVERSE_ALL_METHOD.equals(methodName)
                                                && SceneHandlerCacheTarget.TRAVERSE_ALL_DESCRIPTOR.equals(methodDesc)) {
                                            result[2]++;
                                        } else if (result[2] == 1
                                                && opcode == Opcodes.INVOKESTATIC
                                                && COLLECTIONS_KT.equals(owner)
                                                && ADD_ALL_METHOD.equals(methodName)
                                                && ADD_ALL_DESCRIPTOR.equals(methodDesc)) {
                                            afterAddAll = true;
                                            result[3]++;
                                        }
                                    }

                                    @Override
                                    public void visitTypeInsn(final int opcode, final String type) {
                                        if (afterAddAll
                                                && !afterCastList
                                                && opcode == Opcodes.CHECKCAST
                                                && JAVA_LIST.equals(type)) {
                                            afterCastList = true;
                                        }
                                    }

                                    @Override
                                    public void visitVarInsn(final int opcode, final int varIndex) {
                                        if (afterCastList && result[0] < 0 && opcode == Opcodes.ASTORE) {
                                            result[0] = varIndex;
                                        }
                                    }

                                    @Override
                                    public void visitMaxs(final int maxStack, final int maxLocals) {
                                        result[1] = maxLocals;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG);
        if (result[2] != 1 || result[3] != 1 || result[0] < 0 || result[1] < 2) {
            throw new IllegalArgumentException("unexpected CEViewContext.onInputEvent_exe traversal layout: listSlot="
                    + result[0] + " maxLocals=" + result[1]
                    + " traverseCount=" + result[2] + " addAllCount=" + result[3]);
        }
        return result;
    }
}
