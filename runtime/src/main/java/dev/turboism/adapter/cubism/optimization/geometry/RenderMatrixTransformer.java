package dev.turboism.adapter.cubism.optimization.geometry;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.SlotBridgeEmitter;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites {@code GMeshRenderer} and {@code GLineRenderer} to compute sorting Z without
 * intermediate allocations and pass a dedicated thread-local scratch {@code GMatrix44} to
 * {@code GMaterial.setup}.
 */
public final class RenderMatrixTransformer implements ClassFileTransformer {

    /** Internal name of {@code GMeshRenderer}. */
    public static final String MESH_RENDERER_OWNER = "com/live2d/graphics3d/component/GMeshRenderer";

    /** Binary name of {@code GMeshRenderer}. */
    static final String MESH_RENDERER_DOT = "com.live2d.graphics3d.component.GMeshRenderer";

    /** Internal name of {@code GLineRenderer}. */
    public static final String LINE_RENDERER_OWNER = "com/live2d/graphics3d/component/GLineRenderer";

    /** Internal name of {@code GMeshContainer}. */
    public static final String MESH_CONTAINER_OWNER = "com/live2d/graphics3d/component/GMeshContainer";

    /** Internal name of abstract mesh base class. */
    public static final String MESH_OWNER = "com/live2d/graphics3d/mesh/a";

    /** Internal name of {@code GCamera}. */
    public static final String CAMERA_OWNER = "com/live2d/graphics3d/GCamera";

    /** Internal name of {@code GMaterial}. */
    public static final String MATERIAL_OWNER = "com/live2d/graphics3d/material/GMaterial";

    /** Internal name of {@code GShader}. */
    public static final String SHADER_OWNER = "com/live2d/graphics3d/shader/GShader";

    /** Reviewed sorting-Z method name on {@code GMeshRenderer}. */
    public static final String SORTING_Z_METHOD = "getSortingZOrder__testImpl";

    /** Descriptor of {@code GMeshRenderer.getSortingZOrder__testImpl(GCamera)}. */
    public static final String SORTING_Z_DESCRIPTOR = "(L" + CAMERA_OWNER + ";)F";

    /** Internal name of {@code com.live2d.graphics3d.a} render context. */
    public static final String RENDER_CONTEXT_OWNER = "com/live2d/graphics3d/a";

    /** Reviewed render method name on {@code GMeshRenderer} and {@code GLineRenderer}. */
    public static final String RENDER_METHOD = "render";

    /** Descriptor of {@code render(a)}. */
    public static final String RENDER_DESCRIPTOR = "(L" + RENDER_CONTEXT_OWNER + ";)V";

    /** Reviewed {@code GMaterial.setup} method name. */
    public static final String MATERIAL_SETUP_METHOD = "setup";

    /** Descriptor of {@code GMaterial.setup(a, GMatrix44)}. */
    public static final String MATERIAL_SETUP_DESCRIPTOR =
            "(L" + RENDER_CONTEXT_OWNER + ";L" + MatrixScratchTransformer.MATRIX + ";)V";

    /** Expected transformed class count covering both {@code GMeshRenderer} and {@code GLineRenderer}. */
    public static final int EXPECTED_MATCHES = 2;

    private static final int EXPECTED_RENDER_SITES = 1;
    private static final int MIN_RENDER_LOCALS = 2;
    private static final int MIN_SORTING_LOCALS = 2;
    private static final int LONG_SLOT_COUNT = 2;
    private static final int RENDER_EXTRA_LOCALS = 2;

    private static final String THROWABLE = "java/lang/Throwable";
    private static final String ATOMIC_BOOLEAN = "java/util/concurrent/atomic/AtomicBoolean";
    private static final String ATOMIC_GET = "get";
    private static final String ATOMIC_GET_DESC = "()Z";
    private static final String FUNCTION = "java/util/function/Function";
    private static final String FUNCTION_APPLY = "apply";
    private static final String FUNCTION_APPLY_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String TO_LONG_BI_FUNCTION = "java/util/function/ToLongBiFunction";
    private static final String APPLY_AS_LONG = "applyAsLong";
    private static final String APPLY_AS_LONG_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)J";
    private static final String FLOAT_OWNER = "java/lang/Float";
    private static final String INT_BITS_TO_FLOAT = "intBitsToFloat";
    private static final String INT_BITS_TO_FLOAT_DESC = "(I)F";

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> sortingShape;
    private final List<String> meshRenderShape;
    private final List<String> lineRenderShape;
    private final int sortingLocalBase;
    private final int meshRenderLocalBase;
    private final int lineRenderLocalBase;
    private volatile String failure;
    private volatile int matches;

    /**
     * Captures exact reference method shapes for {@code GMeshRenderer} and {@code GLineRenderer}.
     *
     * @param loader admitted host class loader
     * @param artifact expected defining JAR archive
     * @param meshRendererReference reference bytes of {@code GMeshRenderer}
     * @param lineRendererReference reference bytes of {@code GLineRenderer}
     * @throws IllegalArgumentException when any reviewed method or call site is missing or altered
     */
    public RenderMatrixTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] meshRendererReference,
            final byte[] lineRendererReference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(meshRendererReference, "meshRendererReference");
        Objects.requireNonNull(lineRendererReference, "lineRendererReference");

        sortingShape = ReviewedMethodShape.read(
                meshRendererReference, MESH_RENDERER_OWNER, SORTING_Z_METHOD, SORTING_Z_DESCRIPTOR);
        meshRenderShape =
                ReviewedMethodShape.read(meshRendererReference, MESH_RENDERER_OWNER, RENDER_METHOD, RENDER_DESCRIPTOR);
        lineRenderShape =
                ReviewedMethodShape.read(lineRendererReference, LINE_RENDERER_OWNER, RENDER_METHOD, RENDER_DESCRIPTOR);
        if (sortingShape == null || meshRenderShape == null || lineRenderShape == null) {
            throw new IllegalArgumentException("reviewed renderer matrix method absent");
        }
        sortingLocalBase = inspectSortingLocals(meshRendererReference);
        meshRenderLocalBase = inspectRenderCallSite(meshRendererReference);
        lineRenderLocalBase = inspectRenderCallSite(lineRendererReference);
    }

    /**
     * Returns the latest fail-closed rejection message, or {@code null}.
     *
     * @return failure diagnostic or {@code null}
     */
    public String failure() {
        return failure;
    }

    /**
     * Returns the number of rewritten renderer classes observed so far.
     *
     * @return matched class count
     */
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
        if (bytes == null) {
            return null;
        }
        final boolean isMeshRenderer = MESH_RENDERER_OWNER.equals(name);
        final boolean isLineRenderer = LINE_RENDERER_OWNER.equals(name);
        if (!isMeshRenderer && !isLineRenderer) {
            return null;
        }
        try {
            if (actualLoader != loader || !HostCodeSource.matchesArtifact(domain, artifact)) {
                failure = "renderer matrix source mismatch: " + name;
                return null;
            }
            if (isMeshRenderer) {
                return transformMeshRenderer(bytes);
            }
            return transformLineRenderer(bytes);
        } catch (final Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private byte[] transformMeshRenderer(final byte[] bytes) {
        final List<String> observedSorting =
                ReviewedMethodShape.read(bytes, MESH_RENDERER_OWNER, SORTING_Z_METHOD, SORTING_Z_DESCRIPTOR);
        final List<String> observedRender =
                ReviewedMethodShape.read(bytes, MESH_RENDERER_OWNER, RENDER_METHOD, RENDER_DESCRIPTOR);
        if (!sortingShape.equals(observedSorting) || !meshRenderShape.equals(observedRender)) {
            failure = "GMeshRenderer method shape changed";
            return null;
        }
        final ClassReader reader = HostClassWriters.newClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        reader.accept(
                HostClassWriters.preservingClassVersion(bytes, new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String method,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor downstream =
                                super.visitMethod(access, method, descriptor, signature, exceptions);
                        if (SORTING_Z_METHOD.equals(method) && SORTING_Z_DESCRIPTOR.equals(descriptor)) {
                            return new SortingZVisitor(downstream, sortingLocalBase);
                        }
                        if (RENDER_METHOD.equals(method) && RENDER_DESCRIPTOR.equals(descriptor)) {
                            return new RenderLocalMatrixVisitor(downstream, meshRenderLocalBase);
                        }
                        return downstream;
                    }
                }),
                ClassReader.EXPAND_FRAMES);
        matches++;
        return writer.toByteArray();
    }

    private byte[] transformLineRenderer(final byte[] bytes) {
        final List<String> observedRender =
                ReviewedMethodShape.read(bytes, LINE_RENDERER_OWNER, RENDER_METHOD, RENDER_DESCRIPTOR);
        if (!lineRenderShape.equals(observedRender)) {
            failure = "GLineRenderer.render shape changed";
            return null;
        }
        final ClassReader reader = HostClassWriters.newClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        reader.accept(
                HostClassWriters.preservingClassVersion(bytes, new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String method,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor downstream =
                                super.visitMethod(access, method, descriptor, signature, exceptions);
                        return RENDER_METHOD.equals(method) && RENDER_DESCRIPTOR.equals(descriptor)
                                ? new RenderLocalMatrixVisitor(downstream, lineRenderLocalBase)
                                : downstream;
                    }
                }),
                ClassReader.EXPAND_FRAMES);
        matches++;
        return writer.toByteArray();
    }

    private static int inspectSortingLocals(final byte[] bytes) {
        final int[] maxLocals = {-1};
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
                                if (!SORTING_Z_METHOD.equals(name) || !SORTING_Z_DESCRIPTOR.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMaxs(final int maxStack, final int locals) {
                                        maxLocals[0] = locals;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG);
        if (maxLocals[0] < MIN_SORTING_LOCALS) {
            throw new IllegalArgumentException("unexpected locals in " + SORTING_Z_METHOD);
        }
        return maxLocals[0];
    }

    private static int inspectRenderCallSite(final byte[] bytes) {
        final int[] inspected = {-1, 0};
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
                                if (!RENDER_METHOD.equals(name) || !RENDER_DESCRIPTOR.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String method,
                                            final String desc,
                                            final boolean itf) {
                                        if (isLocalToWorldCall(opcode, owner, method, desc, itf)) {
                                            inspected[1]++;
                                        }
                                    }

                                    @Override
                                    public void visitMaxs(final int maxStack, final int locals) {
                                        inspected[0] = locals;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG);
        if (inspected[0] < MIN_RENDER_LOCALS || inspected[1] != EXPECTED_RENDER_SITES) {
            throw new IllegalArgumentException("expected one localToWorldMatrix site in render");
        }
        return inspected[0];
    }

    private static boolean isLocalToWorldCall(
            final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
        return opcode == Opcodes.INVOKEVIRTUAL
                && !itf
                && MatrixScratchTransformer.OWNER.equals(owner)
                && MatrixScratchTransformer.METHOD.equals(name)
                && MatrixScratchTransformer.DESCRIPTOR.equals(descriptor);
    }

    private static void emitGateBridgeLookup(
            final MethodVisitor mv,
            final String callbackProperty,
            final int bridgeSlot,
            final List<TryCatchBlock> deferredHandlers) {
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, bridgeSlot);

        final Label start = new Label();
        final Label popAdmissionAndEnd = new Label();
        final Label end = new Label();
        final Label failed = new Label();
        final Label done = new Label();

        mv.visitTryCatchBlock(start, end, failed, THROWABLE);
        mv.visitLabel(start);
        SlotBridgeEmitter.emitGetSlot(mv, MatrixScratchTransformer.ADMISSION_PROPERTY);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, ATOMIC_BOOLEAN);
        mv.visitJumpInsn(Opcodes.IFEQ, popAdmissionAndEnd);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ATOMIC_BOOLEAN);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ATOMIC_BOOLEAN, ATOMIC_GET, ATOMIC_GET_DESC, false);
        mv.visitJumpInsn(Opcodes.IFEQ, end);

        SlotBridgeEmitter.emitGetSlot(mv, callbackProperty);
        mv.visitVarInsn(Opcodes.ASTORE, bridgeSlot);
        mv.visitJumpInsn(Opcodes.GOTO, end);

        mv.visitLabel(popAdmissionAndEnd);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
        mv.visitJumpInsn(Opcodes.GOTO, done);

        mv.visitLabel(failed);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, bridgeSlot);
        mv.visitLabel(done);

        // Native handlers are buffered separately and emitted after entry gate handlers in visitMaxs.
        deferredHandlers.clear();
    }

    private static final class SortingZVisitor extends MethodVisitor {

        private final int bridgeSlot;
        private final int resultSlot;
        private final List<TryCatchBlock> nativeHandlers = new ArrayList<>();

        SortingZVisitor(final MethodVisitor downstream, final int localBase) {
            super(Opcodes.ASM9, downstream);
            this.bridgeSlot = localBase;
            this.resultSlot = localBase;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitGateBridgeLookup(mv, MatrixScratchBridge.SORTING_CALLBACK_PROPERTY, bridgeSlot, nativeHandlers);

            final Label nativePath = new Label();
            super.visitVarInsn(Opcodes.ALOAD, bridgeSlot);
            super.visitTypeInsn(Opcodes.INSTANCEOF, TO_LONG_BI_FUNCTION);
            super.visitJumpInsn(Opcodes.IFEQ, nativePath);

            super.visitVarInsn(Opcodes.ALOAD, bridgeSlot);
            super.visitTypeInsn(Opcodes.CHECKCAST, TO_LONG_BI_FUNCTION);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, 1);
            super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, TO_LONG_BI_FUNCTION, APPLY_AS_LONG, APPLY_AS_LONG_DESC, true);
            super.visitVarInsn(Opcodes.LSTORE, resultSlot);
            super.visitVarInsn(Opcodes.LLOAD, resultSlot);
            super.visitInsn(Opcodes.LCONST_0);
            super.visitInsn(Opcodes.LCMP);
            super.visitJumpInsn(Opcodes.IFEQ, nativePath);

            super.visitVarInsn(Opcodes.LLOAD, resultSlot);
            super.visitInsn(Opcodes.L2I);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, FLOAT_OWNER, INT_BITS_TO_FLOAT, INT_BITS_TO_FLOAT_DESC, false);
            super.visitInsn(Opcodes.FRETURN);

            super.visitLabel(nativePath);
        }

        @Override
        public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
            nativeHandlers.add(new TryCatchBlock(start, end, handler, type));
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            for (final TryCatchBlock block : nativeHandlers) {
                super.visitTryCatchBlock(block.start(), block.end(), block.handler(), block.type());
            }
            super.visitMaxs(maxStack, Math.max(maxLocals, resultSlot + LONG_SLOT_COUNT));
        }
    }

    private static final class RenderLocalMatrixVisitor extends MethodVisitor {

        private final int bridgeSlot;
        private final int transformSlot;
        private final List<TryCatchBlock> nativeHandlers = new ArrayList<>();

        RenderLocalMatrixVisitor(final MethodVisitor downstream, final int localBase) {
            super(Opcodes.ASM9, downstream);
            this.bridgeSlot = localBase;
            this.transformSlot = localBase + 1;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitVarInsn(Opcodes.ASTORE, transformSlot);
            emitGateBridgeLookup(mv, MatrixScratchBridge.RENDER_CALLBACK_PROPERTY, bridgeSlot, nativeHandlers);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            if (!isLocalToWorldCall(opcode, owner, name, descriptor, itf)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                return;
            }
            super.visitVarInsn(Opcodes.ASTORE, transformSlot);
            final Label nativeCall = new Label();
            final Label popAndNative = new Label();
            final Label done = new Label();

            super.visitVarInsn(Opcodes.ALOAD, bridgeSlot);
            super.visitTypeInsn(Opcodes.INSTANCEOF, FUNCTION);
            super.visitJumpInsn(Opcodes.IFEQ, nativeCall);

            super.visitVarInsn(Opcodes.ALOAD, bridgeSlot);
            super.visitTypeInsn(Opcodes.CHECKCAST, FUNCTION);
            super.visitVarInsn(Opcodes.ALOAD, transformSlot);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION, FUNCTION_APPLY, FUNCTION_APPLY_DESC, true);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, MatrixScratchTransformer.MATRIX);
            super.visitJumpInsn(Opcodes.IFEQ, popAndNative);
            super.visitTypeInsn(Opcodes.CHECKCAST, MatrixScratchTransformer.MATRIX);
            super.visitJumpInsn(Opcodes.GOTO, done);

            super.visitLabel(popAndNative);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(nativeCall);
            super.visitVarInsn(Opcodes.ALOAD, transformSlot);
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);

            super.visitLabel(done);
        }

        @Override
        public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
            nativeHandlers.add(new TryCatchBlock(start, end, handler, type));
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            for (final TryCatchBlock block : nativeHandlers) {
                super.visitTryCatchBlock(block.start(), block.end(), block.handler(), block.type());
            }
            super.visitMaxs(maxStack, Math.max(maxLocals, bridgeSlot + RENDER_EXTRA_LOCALS));
        }
    }

    private record TryCatchBlock(Label start, Label end, Label handler, String type) {}
}
