package dev.turboism.adapter.cubism.optimization.renderpass;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Eliminates per-call {@code PresetColorType.values()} enum array cloning in {@code GRenderTexture}
 * and intermediate {@code ArrayList} plus second-iterator overhead in the Cubism 5.3 texture pool.
 *
 * <p>{@code GRenderTexture.getPresetColorType()} is called on every texture-pool candidate check and
 * previously allocated a fresh 7-element {@code PresetColorType[]} clone each time. Replacing the
 * {@code values()} call with a {@link ConstantDynamic} constant caches the immutable array once per
 * class definition without adding fields or methods, preserving {@code Instrumentation.retransformClasses}
 * compatibility while honoring live {@code setInternalFormat}/{@code setFormat}/{@code setBufferType}
 * changes.</p>
 */
public final class RenderTextureColorPoolTransformer implements ClassFileTransformer {

    /** Internal name of {@code GRenderTexture}. */
    public static final String RENDER_TEXTURE_OWNER = "com/live2d/graphics3d/texture/GRenderTexture";

    /** Internal name of {@code GRenderTexture$PresetColorType}. */
    public static final String PRESET_COLOR_TYPE_OWNER = "com/live2d/graphics3d/texture/GRenderTexture$PresetColorType";

    /** Method name on {@code GRenderTexture} allocating enum arrays. */
    public static final String GET_PRESET_COLOR_TYPE_METHOD = "getPresetColorType";

    /** Descriptor of {@code GRenderTexture.getPresetColorType()}. */
    public static final String GET_PRESET_COLOR_TYPE_DESCRIPTOR = "()L" + PRESET_COLOR_TYPE_OWNER + ";";

    /** Internal name of the Cubism 5.3 clipping-mask render-texture pool manager. */
    public static final String POOL_LEASE_OWNER = "com/live2d/graphics3d/rendering/b";

    /** Lease acquisition method on {@code rendering/b}. */
    public static final String ACQUIRE_LEASE_METHOD = "a";

    /** Descriptor of {@code rendering/b.a(a, int, int, PresetColorType, boolean)}. */
    public static final String ACQUIRE_LEASE_DESCRIPTOR =
            "(Lcom/live2d/graphics3d/a;IIL" + PRESET_COLOR_TYPE_OWNER + ";Z)Lcom/live2d/graphics3d/rendering/a;";

    private static final String PRESET_ARRAY_DESCRIPTOR = "[L" + PRESET_COLOR_TYPE_OWNER + ";";
    private static final String VALUES_METHOD = "values";
    private static final String CONSTANT_BOOTSTRAPS_OWNER = "java/lang/invoke/ConstantBootstraps";
    private static final String CONSTANT_BOOTSTRAPS_INVOKE_METHOD = "invoke";
    private static final String CONSTANT_BOOTSTRAPS_INVOKE_DESCRIPTOR =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String CONDY_NAME = "presetColorTypes";

    private static final String POOL_ENTRY_OWNER = "com/live2d/graphics3d/rendering/b$a";
    private static final String LEASE_OWNER = "com/live2d/graphics3d/rendering/a";
    private static final String HASH_SET_OWNER = "java/util/HashSet";
    private static final String ITERATOR_OWNER = "java/util/Iterator";
    private static final String ARRAY_LIST_OWNER = "java/util/ArrayList";
    private static final String GET_FREE_SET_METHOD = "e";
    private static final String GET_FREE_SET_DESCRIPTOR = "()Ljava/util/HashSet;";
    private static final String GET_RENDER_TEXTURE_METHOD = "b";
    private static final String GET_RENDER_TEXTURE_DESCRIPTOR = "()L" + RENDER_TEXTURE_OWNER + ";";
    private static final String GET_WIDTH_METHOD = "getWidth";
    private static final String GET_HEIGHT_METHOD = "getHeight";
    private static final String GET_STENCIL_METHOD = "getUseStencilBuffer";

    private static final int POOL_WIDTH_PARAM = 2;
    private static final int POOL_HEIGHT_PARAM = 3;
    private static final int POOL_COLOR_PARAM = 4;
    private static final int POOL_STENCIL_PARAM = 5;
    private static final int POOL_SELECTED_LOCAL = 8;
    private static final int POOL_ENTRY_LOCAL = 13;
    private static final int POOL_BEST_EXCESS_LOCAL = 14;
    private static final int POOL_ITERATOR_LOCAL = 15;
    private static final int POOL_CANDIDATE_LOCAL = 16;
    private static final int POOL_TEXTURE_LOCAL = 17;
    private static final int POOL_CURRENT_EXCESS_LOCAL = 18;
    private static final int POOL_MIN_LOCALS = 19;

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> renderTextureShape;
    private final List<String> poolAcquireShape;

    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Captures and verifies official reference shapes for {@code GRenderTexture} and optionally the
     * Cubism 5.3 {@code rendering/b} lease pool.
     *
     * @param loader admitted host class loader
     * @param artifact official host JAR path
     * @param renderTextureReference reference class bytes of {@code GRenderTexture}
     * @param poolReference reference class bytes of {@code rendering/b} on Cubism 5.3.x, or {@code null}
     */
    public RenderTextureColorPoolTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] renderTextureReference,
            final byte[] poolReference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(renderTextureReference, "renderTextureReference");
        requireConstantDynamicSupport(renderTextureReference, RENDER_TEXTURE_OWNER);

        renderTextureShape = ReviewedMethodShape.read(
                renderTextureReference,
                RENDER_TEXTURE_OWNER,
                GET_PRESET_COLOR_TYPE_METHOD,
                GET_PRESET_COLOR_TYPE_DESCRIPTOR);
        if (renderTextureShape == null) {
            throw new IllegalArgumentException(
                    "reviewed color-type method absent: " + RENDER_TEXTURE_OWNER + "." + GET_PRESET_COLOR_TYPE_METHOD);
        }
        if (countValuesCalls(renderTextureReference) != 1) {
            throw new IllegalArgumentException("unexpected PresetColorType.values() call count");
        }

        if (poolReference != null) {
            poolAcquireShape = ReviewedMethodShape.read(
                    poolReference, POOL_LEASE_OWNER, ACQUIRE_LEASE_METHOD, ACQUIRE_LEASE_DESCRIPTOR);
            if (poolAcquireShape == null) {
                throw new IllegalArgumentException(
                        "reviewed pool acquire method absent: " + POOL_LEASE_OWNER + "." + ACQUIRE_LEASE_METHOD);
            }
            inspectPoolAcquireSlice(poolReference);
        } else {
            poolAcquireShape = null;
        }
    }

    /** Returns the latest fail-closed rejection detail, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns the number of classes rewritten so far. */
    public int matches() {
        return matches;
    }

    /** Returns the number of methods rewritten so far. */
    public int sites() {
        return sites;
    }

    /** Returns the expected matched class count across configured owners. */
    public int expectedMatches() {
        return poolAcquireShape == null ? 1 : 2;
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
        final boolean isRenderTexture = RENDER_TEXTURE_OWNER.equals(name);
        final boolean isPool = poolAcquireShape != null && POOL_LEASE_OWNER.equals(name);
        if (!isRenderTexture && !isPool) {
            return null;
        }
        if (actualLoader != loader || !HostCodeSource.matchesArtifact(domain, artifact)) {
            failure = "unexpected loader or code source for " + name;
            return null;
        }
        try {
            return isRenderTexture ? transformRenderTexture(bytes) : transformPool(bytes);
        } catch (final Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private byte[] transformRenderTexture(final byte[] bytes) {
        requireConstantDynamicSupport(bytes, RENDER_TEXTURE_OWNER);
        final List<String> observed = ReviewedMethodShape.read(
                bytes, RENDER_TEXTURE_OWNER, GET_PRESET_COLOR_TYPE_METHOD, GET_PRESET_COLOR_TYPE_DESCRIPTOR);
        if (!renderTextureShape.equals(observed)) {
            throw new IllegalArgumentException("GRenderTexture.getPresetColorType shape mismatch");
        }
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final int[] applied = {0};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor downstream =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!GET_PRESET_COLOR_TYPE_METHOD.equals(name)
                                || !GET_PRESET_COLOR_TYPE_DESCRIPTOR.equals(descriptor)) {
                            return downstream;
                        }
                        return new PresetColorTypeRewriter(downstream, applied);
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (applied[0] != 1) {
            throw new IllegalStateException("GRenderTexture rewrite sites != 1: " + applied[0]);
        }
        matches++;
        sites += applied[0];
        return writer.toByteArray();
    }

    private byte[] transformPool(final byte[] bytes) {
        final List<String> observed =
                ReviewedMethodShape.read(bytes, POOL_LEASE_OWNER, ACQUIRE_LEASE_METHOD, ACQUIRE_LEASE_DESCRIPTOR);
        if (!poolAcquireShape.equals(observed)) {
            throw new IllegalArgumentException("rendering/b lease acquisition shape mismatch");
        }
        final PoolSlice slice = inspectPoolAcquireSlice(bytes);
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final int[] applied = {0};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor downstream =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!ACQUIRE_LEASE_METHOD.equals(name) || !ACQUIRE_LEASE_DESCRIPTOR.equals(descriptor)) {
                            return downstream;
                        }
                        return new PoolLeaseRewriter(downstream, slice, applied);
                    }
                },
                ClassReader.EXPAND_FRAMES | ClassReader.SKIP_DEBUG);
        if (applied[0] != 1) {
            throw new IllegalStateException("rendering/b lease rewrite sites != 1: " + applied[0]);
        }
        matches++;
        sites += applied[0];
        return writer.toByteArray();
    }

    private static void requireConstantDynamicSupport(final byte[] classBytes, final String owner) {
        final int[] version = {0};
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(
                                    final int ver,
                                    final int access,
                                    final String name,
                                    final String signature,
                                    final String superName,
                                    final String[] interfaces) {
                                version[0] = ver & 0xFFFF;
                            }
                        },
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (version[0] < Opcodes.V11) {
            throw new IllegalArgumentException(owner + " classfile major version < 55: " + version[0]);
        }
    }

    private static int countValuesCalls(final byte[] bytes) {
        final int[] count = {0};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!GET_PRESET_COLOR_TYPE_METHOD.equals(name)
                                        || !GET_PRESET_COLOR_TYPE_DESCRIPTOR.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String invoked,
                                            final String desc,
                                            final boolean itf) {
                                        if (isPresetValuesCall(opcode, owner, invoked, desc, itf)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static boolean isPresetValuesCall(
            final int opcode, final String owner, final String method, final String descriptor, final boolean itf) {
        return opcode == Opcodes.INVOKESTATIC
                && !itf
                && PRESET_COLOR_TYPE_OWNER.equals(owner)
                && VALUES_METHOD.equals(method)
                && ("()" + PRESET_ARRAY_DESCRIPTOR).equals(descriptor);
    }

    private record PoolSlice(int startExclusiveInsn, int endInclusiveInsn) {}

    private static PoolSlice inspectPoolAcquireSlice(final byte[] bytes) {
        final int[] store13Ordinal = {-1};
        final int[] sliceBounds = {-1, -1};
        final int[] arrayListCount = {0};
        final Set<Label> skippedLabels = new HashSet<>();
        final Set<Label> externalTargets = new HashSet<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!ACQUIRE_LEASE_METHOD.equals(name)
                                        || !ACQUIRE_LEASE_DESCRIPTOR.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    int insnIndex;
                                    int lastCheckcastOrdinal = -1;
                                    int candidateStore8Ordinal = -1;
                                    boolean awaitingStore8Use;
                                    boolean awaitingIfNonNull;
                                    boolean inPoolFilter;

                                    private void clearCandidateAwait() {
                                        awaitingStore8Use = false;
                                        awaitingIfNonNull = false;
                                    }

                                    @Override
                                    public void visitLabel(final Label label) {
                                        if (sliceBounds[0] >= 0 && sliceBounds[1] < 0) {
                                            skippedLabels.add(label);
                                        }
                                    }

                                    @Override
                                    public void visitVarInsn(final int opcode, final int varIndex) {
                                        final int idx = insnIndex++;
                                        if (opcode == Opcodes.ASTORE && varIndex == POOL_ENTRY_LOCAL) {
                                            store13Ordinal[0] = idx;
                                            inPoolFilter = false;
                                        } else if (sliceBounds[0] == idx - 1
                                                && !(opcode == Opcodes.ALOAD && varIndex == POOL_ENTRY_LOCAL)) {
                                            // Expect ALOAD 13 immediately after ASTORE 13.
                                            sliceBounds[0] = -1;
                                        }
                                        if (opcode == Opcodes.ASTORE
                                                && varIndex == POOL_SELECTED_LOCAL
                                                && lastCheckcastOrdinal == idx - 1
                                                && sliceBounds[0] >= 0
                                                && sliceBounds[1] < 0) {
                                            candidateStore8Ordinal = idx;
                                            awaitingStore8Use = true;
                                            awaitingIfNonNull = false;
                                        } else if (awaitingStore8Use) {
                                            awaitingStore8Use = false;
                                            if (opcode == Opcodes.ALOAD && varIndex == POOL_SELECTED_LOCAL) {
                                                awaitingIfNonNull = true;
                                            } else {
                                                candidateStore8Ordinal = -1;
                                                awaitingIfNonNull = false;
                                            }
                                        } else {
                                            clearCandidateAwait();
                                        }
                                        if (store13Ordinal[0] == idx - 1
                                                && opcode == Opcodes.ALOAD
                                                && varIndex == POOL_ENTRY_LOCAL) {
                                            sliceBounds[0] = store13Ordinal[0];
                                            inPoolFilter = true;
                                        }
                                    }

                                    @Override
                                    public void visitTypeInsn(final int opcode, final String type) {
                                        final int idx = insnIndex++;
                                        clearCandidateAwait();
                                        if (opcode == Opcodes.NEW && ARRAY_LIST_OWNER.equals(type)) {
                                            arrayListCount[0]++;
                                        }
                                        if (opcode == Opcodes.CHECKCAST && LEASE_OWNER.equals(type)) {
                                            lastCheckcastOrdinal = idx;
                                        }
                                    }

                                    @Override
                                    public void visitJumpInsn(final int opcode, final Label label) {
                                        final int idx = insnIndex++;
                                        if (awaitingIfNonNull
                                                && opcode == Opcodes.IFNONNULL
                                                && candidateStore8Ordinal == idx - 2) {
                                            sliceBounds[1] = candidateStore8Ordinal;
                                        }
                                        clearCandidateAwait();
                                        if (sliceBounds[0] < 0 || sliceBounds[1] >= 0) {
                                            externalTargets.add(label);
                                        }
                                    }

                                    @Override
                                    public void visitTryCatchBlock(
                                            final Label start,
                                            final Label end,
                                            final Label handler,
                                            final String type) {
                                        externalTargets.add(start);
                                        externalTargets.add(end);
                                        externalTargets.add(handler);
                                    }

                                    @Override
                                    public void visitInsn(final int opcode) {
                                        insnIndex++;
                                        clearCandidateAwait();
                                    }

                                    @Override
                                    public void visitIntInsn(final int opcode, final int operand) {
                                        insnIndex++;
                                        clearCandidateAwait();
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            final int opcode,
                                            final String owner,
                                            final String name,
                                            final String desc) {
                                        insnIndex++;
                                        clearCandidateAwait();
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String name,
                                            final String desc,
                                            final boolean itf) {
                                        insnIndex++;
                                        clearCandidateAwait();
                                    }

                                    @Override
                                    public void visitLdcInsn(final Object value) {
                                        insnIndex++;
                                        clearCandidateAwait();
                                    }

                                    @Override
                                    public void visitIincInsn(final int varIndex, final int increment) {
                                        insnIndex++;
                                        clearCandidateAwait();
                                    }

                                    @Override
                                    public void visitTableSwitchInsn(
                                            final int min, final int max, final Label dflt, final Label... labels) {
                                        insnIndex++;
                                        clearCandidateAwait();
                                        if (sliceBounds[0] < 0 || sliceBounds[1] >= 0) {
                                            externalTargets.add(dflt);
                                            for (final Label l : labels) externalTargets.add(l);
                                        }
                                    }

                                    @Override
                                    public void visitLookupSwitchInsn(
                                            final Label dflt, final int[] keys, final Label[] labels) {
                                        insnIndex++;
                                        clearCandidateAwait();
                                        if (sliceBounds[0] < 0 || sliceBounds[1] >= 0) {
                                            externalTargets.add(dflt);
                                            for (final Label l : labels) externalTargets.add(l);
                                        }
                                    }

                                    @Override
                                    public void visitInvokeDynamicInsn(
                                            final String name,
                                            final String desc,
                                            final Handle bsm,
                                            final Object... args) {
                                        insnIndex++;
                                        clearCandidateAwait();
                                    }

                                    @Override
                                    public void visitMultiANewArrayInsn(final String desc, final int dims) {
                                        insnIndex++;
                                        clearCandidateAwait();
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (arrayListCount[0] != 1 || sliceBounds[0] < 0 || sliceBounds[1] <= sliceBounds[0]) {
            throw new IllegalArgumentException("unrecognized rendering/b candidate selection structure");
        }
        for (final Label external : externalTargets) {
            if (skippedLabels.contains(external)) {
                throw new IllegalArgumentException("external branch targets skipped pool-selection label");
            }
        }
        return new PoolSlice(sliceBounds[0], sliceBounds[1]);
    }

    private static final class PresetColorTypeRewriter extends MethodVisitor {

        private final int[] applied;

        PresetColorTypeRewriter(final MethodVisitor downstream, final int[] applied) {
            super(Opcodes.ASM9, downstream);
            this.applied = applied;
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {
            if (isPresetValuesCall(opcode, owner, name, descriptor, isInterface)) {
                final Handle bootstrap = new Handle(
                        Opcodes.H_INVOKESTATIC,
                        CONSTANT_BOOTSTRAPS_OWNER,
                        CONSTANT_BOOTSTRAPS_INVOKE_METHOD,
                        CONSTANT_BOOTSTRAPS_INVOKE_DESCRIPTOR,
                        false);
                final Handle target = new Handle(
                        Opcodes.H_INVOKESTATIC,
                        PRESET_COLOR_TYPE_OWNER,
                        VALUES_METHOD,
                        "()" + PRESET_ARRAY_DESCRIPTOR,
                        false);
                super.visitLdcInsn(new ConstantDynamic(CONDY_NAME, PRESET_ARRAY_DESCRIPTOR, bootstrap, target));
                applied[0]++;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    private static final class PoolLeaseRewriter extends MethodVisitor {

        private final PoolSlice slice;
        private final int[] applied;
        private int insnIndex;

        PoolLeaseRewriter(final MethodVisitor downstream, final PoolSlice slice, final int[] applied) {
            super(Opcodes.ASM9, downstream);
            this.slice = slice;
            this.applied = applied;
        }

        private boolean skipping() {
            return insnIndex > slice.startExclusiveInsn() && insnIndex <= slice.endInclusiveInsn();
        }

        private boolean advanceAndCheckReplace() {
            final int current = insnIndex++;
            if (current == slice.endInclusiveInsn()) {
                emitSinglePassLeaseSelection();
                applied[0]++;
                return true;
            }
            return current > slice.startExclusiveInsn() && current < slice.endInclusiveInsn();
        }

        private void emitSinglePassLeaseSelection() {
            final Label loopHead = new Label();
            final Label loopExit = new Label();
            final Label selectCandidate = new Label();

            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitVarInsn(Opcodes.ASTORE, POOL_SELECTED_LOCAL);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitVarInsn(Opcodes.ISTORE, POOL_BEST_EXCESS_LOCAL);

            super.visitVarInsn(Opcodes.ALOAD, POOL_ENTRY_LOCAL);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, POOL_ENTRY_OWNER, GET_FREE_SET_METHOD, GET_FREE_SET_DESCRIPTOR, false);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HASH_SET_OWNER, "iterator", "()Ljava/util/Iterator;", false);
            super.visitVarInsn(Opcodes.ASTORE, POOL_ITERATOR_LOCAL);

            super.visitLabel(loopHead);
            super.visitVarInsn(Opcodes.ALOAD, POOL_ITERATOR_LOCAL);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, ITERATOR_OWNER, "hasNext", "()Z", true);
            super.visitJumpInsn(Opcodes.IFEQ, loopExit);

            super.visitVarInsn(Opcodes.ALOAD, POOL_ITERATOR_LOCAL);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, ITERATOR_OWNER, "next", "()Ljava/lang/Object;", true);
            super.visitTypeInsn(Opcodes.CHECKCAST, LEASE_OWNER);
            super.visitVarInsn(Opcodes.ASTORE, POOL_CANDIDATE_LOCAL);

            super.visitVarInsn(Opcodes.ALOAD, POOL_CANDIDATE_LOCAL);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    LEASE_OWNER,
                    GET_RENDER_TEXTURE_METHOD,
                    GET_RENDER_TEXTURE_DESCRIPTOR,
                    false);
            super.visitVarInsn(Opcodes.ASTORE, POOL_TEXTURE_LOCAL);

            super.visitVarInsn(Opcodes.ALOAD, POOL_TEXTURE_LOCAL);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RENDER_TEXTURE_OWNER,
                    GET_PRESET_COLOR_TYPE_METHOD,
                    GET_PRESET_COLOR_TYPE_DESCRIPTOR,
                    false);
            super.visitVarInsn(Opcodes.ALOAD, POOL_COLOR_PARAM);
            super.visitJumpInsn(Opcodes.IF_ACMPNE, loopHead);

            super.visitVarInsn(Opcodes.ALOAD, POOL_TEXTURE_LOCAL);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RENDER_TEXTURE_OWNER, GET_WIDTH_METHOD, "()I", false);
            super.visitVarInsn(Opcodes.ILOAD, POOL_WIDTH_PARAM);
            super.visitJumpInsn(Opcodes.IF_ICMPLT, loopHead);

            super.visitVarInsn(Opcodes.ALOAD, POOL_TEXTURE_LOCAL);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RENDER_TEXTURE_OWNER, GET_HEIGHT_METHOD, "()I", false);
            super.visitVarInsn(Opcodes.ILOAD, POOL_HEIGHT_PARAM);
            super.visitJumpInsn(Opcodes.IF_ICMPLT, loopHead);

            super.visitVarInsn(Opcodes.ALOAD, POOL_TEXTURE_LOCAL);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RENDER_TEXTURE_OWNER, GET_STENCIL_METHOD, "()Z", false);
            super.visitVarInsn(Opcodes.ILOAD, POOL_STENCIL_PARAM);
            super.visitJumpInsn(Opcodes.IF_ICMPNE, loopHead);

            super.visitVarInsn(Opcodes.ALOAD, POOL_TEXTURE_LOCAL);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RENDER_TEXTURE_OWNER, GET_WIDTH_METHOD, "()I", false);
            super.visitVarInsn(Opcodes.ILOAD, POOL_WIDTH_PARAM);
            super.visitInsn(Opcodes.ISUB);
            super.visitVarInsn(Opcodes.ALOAD, POOL_TEXTURE_LOCAL);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RENDER_TEXTURE_OWNER, GET_HEIGHT_METHOD, "()I", false);
            super.visitVarInsn(Opcodes.ILOAD, POOL_HEIGHT_PARAM);
            super.visitInsn(Opcodes.ISUB);
            super.visitInsn(Opcodes.IMUL);
            super.visitVarInsn(Opcodes.ISTORE, POOL_CURRENT_EXCESS_LOCAL);

            super.visitVarInsn(Opcodes.ALOAD, POOL_SELECTED_LOCAL);
            super.visitJumpInsn(Opcodes.IFNULL, selectCandidate);
            super.visitVarInsn(Opcodes.ILOAD, POOL_BEST_EXCESS_LOCAL);
            super.visitVarInsn(Opcodes.ILOAD, POOL_CURRENT_EXCESS_LOCAL);
            super.visitJumpInsn(Opcodes.IF_ICMPLE, loopHead);

            super.visitLabel(selectCandidate);
            super.visitVarInsn(Opcodes.ALOAD, POOL_CANDIDATE_LOCAL);
            super.visitVarInsn(Opcodes.ASTORE, POOL_SELECTED_LOCAL);
            super.visitVarInsn(Opcodes.ILOAD, POOL_CURRENT_EXCESS_LOCAL);
            super.visitVarInsn(Opcodes.ISTORE, POOL_BEST_EXCESS_LOCAL);
            super.visitJumpInsn(Opcodes.GOTO, loopHead);

            super.visitLabel(loopExit);
        }

        @Override
        public void visitFrame(
                final int type, final int numLocal, final Object[] local, final int numStack, final Object[] stack) {
            if (!skipping()) {
                super.visitFrame(type, numLocal, local, numStack, stack);
            }
        }

        @Override
        public void visitLabel(final Label label) {
            if (!skipping()) {
                super.visitLabel(label);
            }
        }

        @Override
        public void visitInsn(final int opcode) {
            if (!advanceAndCheckReplace()) super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            if (!advanceAndCheckReplace()) super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (!advanceAndCheckReplace()) super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            if (!advanceAndCheckReplace()) super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            if (!advanceAndCheckReplace()) super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {
            if (!advanceAndCheckReplace()) super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name, final String descriptor, final Handle bootstrapMethodHandle, final Object... args) {
            if (!advanceAndCheckReplace()) {
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, args);
            }
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            if (!advanceAndCheckReplace()) super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            if (!advanceAndCheckReplace()) super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(final int varIndex, final int increment) {
            if (!advanceAndCheckReplace()) super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {
            if (!advanceAndCheckReplace()) super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
            if (!advanceAndCheckReplace()) super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
            if (!advanceAndCheckReplace()) super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            super.visitMaxs(maxStack, Math.max(maxLocals, POOL_MIN_LOCALS));
        }
    }
}
