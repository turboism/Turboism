package dev.turboism.adapter.cubism.optimization.warpgrid;

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
 * Rewrites {@code com.live2d.cubism.doc.model.deformer.warp.o.a} with a specialized in-bounds loop.
 *
 * <p>Splitting quad and triangle loops outside point iteration and hoisting row-stride invariants
 * reduces per-vertex integer conversions and register spills while preserving exact IEEE-754 float
 * evaluation order. Out-of-bounds tails delegate to {@link WarpGridInterpolationBridge} when active,
 * falling back to the unmodified host body when inactive or unbridged.</p>
 */
public final class WarpGridInterpolationTransformer implements ClassFileTransformer {

    private static final String METHOD_HANDLE_INTERNAL = "java/lang/invoke/MethodHandle";
    private static final String INVOKE_EXACT_METHOD = "invokeExact";

    private static final String FAILURE_SOURCE_MISMATCH = "warp grid source is not the reviewed artifact";
    private static final String FAILURE_SHAPE_MISMATCH = "warp grid interpolation method shape mismatch";
    private static final String FAILURE_NOT_APPLIED = "warp grid fast path was not applied";
    private static final String FAILURE_METHOD_ABSENT = "reviewed warp grid interpolation method absent";
    private static final String NO_FRAMES_MARKER = "<no frames>";
    private static final String FAILURE_LOCATOR_SEPARATOR = " at ";

    private static final int LOCAL_SRC = 1;
    private static final int LOCAL_DST = 2;
    private static final int LOCAL_NUM_POINT = 3;
    private static final int LOCAL_SRC_OFFSET = 4;
    private static final int LOCAL_SRC_STEP = 5;
    private static final int LOCAL_PT = 6;
    private static final int LOCAL_COL = 7;
    private static final int LOCAL_ROW = 8;
    private static final int LOCAL_IS_QUAD = 9;

    private static final int LOCAL_LIMIT = 10;
    private static final int LOCAL_INDEX_I = 11;
    private static final int LOCAL_FCOL = 12;
    private static final int LOCAL_FROW = 13;
    private static final int LOCAL_COL_PLUS_1 = 14;
    private static final int LOCAL_ROW_STRIDE = 15;
    private static final int LOCAL_SX = 16;
    private static final int LOCAL_SY = 17;
    private static final int LOCAL_X = 18;
    private static final int LOCAL_Y = 19;
    private static final int LOCAL_XI = 20;
    private static final int LOCAL_YI = 21;
    private static final int LOCAL_X_RATE = 22;
    private static final int LOCAL_Y_RATE = 23;
    private static final int LOCAL_GRID_IDX = 24;
    private static final int LOCAL_NEXT_ROW = 25;
    private static final int LOCAL_W0 = 26;
    private static final int LOCAL_W1 = 27;
    private static final int LOCAL_BRIDGE_SLOT = 28;
    private static final int LOCAL_W11 = 29;

    private static final int OFFSET_X = 0;
    private static final int OFFSET_Y = 1;
    private static final int OFFSET_RIGHT = 2;

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> shape;
    private volatile String failure;
    private volatile int matches;

    /**
     * Builds a fail-closed transformer bound to one loader, artifact path, and reference class bytes.
     *
     * @param loader host loader permitted to receive rewrites
     * @param artifact normalized path to the reviewed host JAR
     * @param reference unmodified bytes of {@code com.live2d.cubism.doc.model.deformer.warp.o}
     * @param target reviewed target metadata for this artifact
     * @throws IllegalArgumentException when the reviewed interpolation method is absent from {@code reference}
     */
    public WarpGridInterpolationTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] reference,
            final WarpGridInterpolationTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(target, "target");
        shape = ReviewedMethodShape.read(
                reference,
                WarpGridInterpolationTarget.OWNER,
                WarpGridInterpolationTarget.METHOD,
                WarpGridInterpolationTarget.DESCRIPTOR);
        if (shape == null) {
            throw new IllegalArgumentException(FAILURE_METHOD_ABSENT);
        }
    }

    /** Returns the most recent refusal reason, or {@code null} when none occurred. */
    public String failure() {
        return failure;
    }

    /** Returns the count of successfully rewritten class definitions. */
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
        if (actualLoader != loader || !WarpGridInterpolationTarget.OWNER.equals(name) || bytes == null) {
            return null;
        }
        try {
            if (!HostCodeSource.matchesArtifact(domain, artifact)) {
                failure = FAILURE_SOURCE_MISMATCH;
                return null;
            }
            if (!shape.equals(ReviewedMethodShape.read(
                    bytes,
                    WarpGridInterpolationTarget.OWNER,
                    WarpGridInterpolationTarget.METHOD,
                    WarpGridInterpolationTarget.DESCRIPTOR))) {
                failure = FAILURE_SHAPE_MISMATCH;
                return null;
            }
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
            final boolean[] applied = {false};
            reader.accept(new WarpGridClassVisitor(writer, applied), ClassReader.EXPAND_FRAMES);
            if (!applied[0]) {
                failure = FAILURE_NOT_APPLIED;
                return null;
            }
            matches++;
            return writer.toByteArray();
        } catch (final Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure =
                    rejected + FAILURE_LOCATOR_SEPARATOR + (trace.length == 0 ? NO_FRAMES_MARKER : trace[0].toString());
            return null;
        }
    }

    private static final class WarpGridClassVisitor extends ClassVisitor {
        private final boolean[] applied;

        private WarpGridClassVisitor(final ClassVisitor delegate, final boolean[] applied) {
            super(Opcodes.ASM9, delegate);
            this.applied = applied;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String method,
                final String descriptor,
                final String signature,
                final String[] exceptions) {
            final MethodVisitor visitor = super.visitMethod(access, method, descriptor, signature, exceptions);
            if (!WarpGridInterpolationTarget.METHOD.equals(method)
                    || !WarpGridInterpolationTarget.DESCRIPTOR.equals(descriptor)) {
                return visitor;
            }
            applied[0] = true;
            return new FastPathEntryVisitor(visitor);
        }
    }

    private static final class FastPathEntryVisitor extends MethodVisitor {
        private final List<TryCatchBlock> handlers = new ArrayList<>();

        private FastPathEntryVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
            handlers.add(new TryCatchBlock(start, end, handler, type));
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitFastPath();
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            for (final TryCatchBlock block : handlers) {
                super.visitTryCatchBlock(block.start(), block.end(), block.handler(), block.type());
            }
            super.visitMaxs(maxStack, maxLocals);
        }

        private void emitFastPath() {
            final Label fallback = new Label();
            final Label hasPoints = new Label();
            final Label quadTop = new Label();
            final Label triTop = new Label();
            final Label triLoop = new Label();
            final Label oobExit = new Label();

            emitEntryGuards(fallback);

            // Compute loop bounds outside iteration to avoid per-point multiplications.
            visitVarInsn(Opcodes.ILOAD, LOCAL_NUM_POINT);
            visitVarInsn(Opcodes.ILOAD, LOCAL_SRC_STEP);
            visitInsn(Opcodes.IMUL);
            visitVarInsn(Opcodes.ISTORE, LOCAL_LIMIT);

            visitVarInsn(Opcodes.ILOAD, LOCAL_SRC_OFFSET);
            visitVarInsn(Opcodes.ISTORE, LOCAL_INDEX_I);

            visitVarInsn(Opcodes.ILOAD, LOCAL_INDEX_I);
            visitVarInsn(Opcodes.ILOAD, LOCAL_LIMIT);
            visitJumpInsn(Opcodes.IF_ICMPLT, hasPoints);
            visitInsn(Opcodes.RETURN);

            visitLabel(hasPoints);
            visitVarInsn(Opcodes.ILOAD, LOCAL_COL);
            visitInsn(Opcodes.I2F);
            visitVarInsn(Opcodes.FSTORE, LOCAL_FCOL);

            visitVarInsn(Opcodes.ILOAD, LOCAL_ROW);
            visitInsn(Opcodes.I2F);
            visitVarInsn(Opcodes.FSTORE, LOCAL_FROW);

            visitVarInsn(Opcodes.ILOAD, LOCAL_COL);
            visitInsn(Opcodes.ICONST_1);
            visitInsn(Opcodes.IADD);
            visitVarInsn(Opcodes.ISTORE, LOCAL_COL_PLUS_1);

            visitVarInsn(Opcodes.ILOAD, LOCAL_COL_PLUS_1);
            visitInsn(Opcodes.ICONST_1);
            visitInsn(Opcodes.ISHL);
            visitVarInsn(Opcodes.ISTORE, LOCAL_ROW_STRIDE);

            visitVarInsn(Opcodes.ILOAD, LOCAL_IS_QUAD);
            visitJumpInsn(Opcodes.IFEQ, triLoop);

            emitQuadLoop(quadTop, oobExit);
            visitLabel(triLoop);
            emitTriangleLoop(triTop, oobExit);
            emitOobDelegation(oobExit, quadTop, triTop);

            visitLabel(fallback);
        }

        private void emitEntryGuards(final Label fallback) {
            visitVarInsn(Opcodes.ALOAD, LOCAL_SRC);
            visitJumpInsn(Opcodes.IFNULL, fallback);
            visitVarInsn(Opcodes.ALOAD, LOCAL_DST);
            visitJumpInsn(Opcodes.IFNULL, fallback);
            visitVarInsn(Opcodes.ALOAD, LOCAL_PT);
            visitJumpInsn(Opcodes.IFNULL, fallback);

            SlotBridgeEmitter.emitGetSlot(this, WarpGridInterpolationBridge.OOB_PROPERTY);
            visitVarInsn(Opcodes.ASTORE, LOCAL_BRIDGE_SLOT);
            visitVarInsn(Opcodes.ALOAD, LOCAL_BRIDGE_SLOT);
            visitTypeInsn(Opcodes.INSTANCEOF, METHOD_HANDLE_INTERNAL);
            visitJumpInsn(Opcodes.IFEQ, fallback);
        }

        private void emitQuadLoop(final Label quadTop, final Label oobExit) {
            visitLabel(quadTop);

            emitPointBoundsAndCellSetup(oobExit);

            visitInsn(Opcodes.FCONST_1);
            visitVarInsn(Opcodes.FLOAD, LOCAL_X_RATE);
            visitInsn(Opcodes.FSUB);
            visitVarInsn(Opcodes.FSTORE, LOCAL_W0);

            visitInsn(Opcodes.FCONST_1);
            visitVarInsn(Opcodes.FLOAD, LOCAL_Y_RATE);
            visitInsn(Opcodes.FSUB);
            visitVarInsn(Opcodes.FSTORE, LOCAL_W1);

            emitQuadComponent(OFFSET_X);
            emitQuadComponent(OFFSET_Y);

            emitLoopAdvance(quadTop);
        }

        private void emitTriangleLoop(final Label triTop, final Label oobExit) {
            final Label triUpper = new Label();
            final Label triAdvance = new Label();

            visitLabel(triTop);
            emitPointBoundsAndCellSetup(oobExit);

            visitVarInsn(Opcodes.FLOAD, LOCAL_X_RATE);
            visitVarInsn(Opcodes.FLOAD, LOCAL_Y_RATE);
            visitInsn(Opcodes.FADD);
            visitInsn(Opcodes.FCONST_1);
            visitInsn(Opcodes.FCMPG);
            visitJumpInsn(Opcodes.IFGE, triUpper);

            visitInsn(Opcodes.FCONST_1);
            visitVarInsn(Opcodes.FLOAD, LOCAL_X_RATE);
            visitInsn(Opcodes.FSUB);
            visitVarInsn(Opcodes.FLOAD, LOCAL_Y_RATE);
            visitInsn(Opcodes.FSUB);
            visitVarInsn(Opcodes.FSTORE, LOCAL_W0);

            emitTriLowerComponent(OFFSET_X);
            emitTriLowerComponent(OFFSET_Y);
            visitJumpInsn(Opcodes.GOTO, triAdvance);

            visitLabel(triUpper);
            visitVarInsn(Opcodes.FLOAD, LOCAL_X_RATE);
            visitInsn(Opcodes.FCONST_1);
            visitInsn(Opcodes.FSUB);
            visitVarInsn(Opcodes.FLOAD, LOCAL_Y_RATE);
            visitInsn(Opcodes.FADD);
            visitVarInsn(Opcodes.FSTORE, LOCAL_W11);

            visitInsn(Opcodes.FCONST_1);
            visitVarInsn(Opcodes.FLOAD, LOCAL_X_RATE);
            visitInsn(Opcodes.FSUB);
            visitVarInsn(Opcodes.FSTORE, LOCAL_W0);

            visitInsn(Opcodes.FCONST_1);
            visitVarInsn(Opcodes.FLOAD, LOCAL_Y_RATE);
            visitInsn(Opcodes.FSUB);
            visitVarInsn(Opcodes.FSTORE, LOCAL_W1);

            emitTriUpperComponent(OFFSET_X);
            emitTriUpperComponent(OFFSET_Y);

            visitLabel(triAdvance);
            emitLoopAdvance(triTop);
        }

        private void emitPointBoundsAndCellSetup(final Label oobExit) {
            visitVarInsn(Opcodes.ALOAD, LOCAL_SRC);
            visitVarInsn(Opcodes.ILOAD, LOCAL_INDEX_I);
            visitInsn(Opcodes.FALOAD);
            visitVarInsn(Opcodes.FSTORE, LOCAL_SX);

            visitVarInsn(Opcodes.ALOAD, LOCAL_SRC);
            visitVarInsn(Opcodes.ILOAD, LOCAL_INDEX_I);
            visitInsn(Opcodes.ICONST_1);
            visitInsn(Opcodes.IADD);
            visitInsn(Opcodes.FALOAD);
            visitVarInsn(Opcodes.FSTORE, LOCAL_SY);

            visitVarInsn(Opcodes.FLOAD, LOCAL_SX);
            visitVarInsn(Opcodes.FLOAD, LOCAL_FCOL);
            visitInsn(Opcodes.FMUL);
            visitVarInsn(Opcodes.FSTORE, LOCAL_X);

            visitVarInsn(Opcodes.FLOAD, LOCAL_SY);
            visitVarInsn(Opcodes.FLOAD, LOCAL_FROW);
            visitInsn(Opcodes.FMUL);
            visitVarInsn(Opcodes.FSTORE, LOCAL_Y);

            // Preserve original fcmpg NaN comparison behavior so NaN enters in-bounds math.
            visitVarInsn(Opcodes.FLOAD, LOCAL_X);
            visitInsn(Opcodes.FCONST_0);
            visitInsn(Opcodes.FCMPG);
            visitJumpInsn(Opcodes.IFLT, oobExit);

            visitVarInsn(Opcodes.FLOAD, LOCAL_Y);
            visitInsn(Opcodes.FCONST_0);
            visitInsn(Opcodes.FCMPG);
            visitJumpInsn(Opcodes.IFLT, oobExit);

            visitVarInsn(Opcodes.FLOAD, LOCAL_FCOL);
            visitVarInsn(Opcodes.FLOAD, LOCAL_X);
            visitInsn(Opcodes.FCMPG);
            visitJumpInsn(Opcodes.IFLE, oobExit);

            visitVarInsn(Opcodes.FLOAD, LOCAL_FROW);
            visitVarInsn(Opcodes.FLOAD, LOCAL_Y);
            visitInsn(Opcodes.FCMPG);
            visitJumpInsn(Opcodes.IFLE, oobExit);

            visitVarInsn(Opcodes.FLOAD, LOCAL_X);
            visitInsn(Opcodes.F2I);
            visitVarInsn(Opcodes.ISTORE, LOCAL_XI);

            visitVarInsn(Opcodes.FLOAD, LOCAL_Y);
            visitInsn(Opcodes.F2I);
            visitVarInsn(Opcodes.ISTORE, LOCAL_YI);

            visitVarInsn(Opcodes.FLOAD, LOCAL_X);
            visitVarInsn(Opcodes.ILOAD, LOCAL_XI);
            visitInsn(Opcodes.I2F);
            visitInsn(Opcodes.FSUB);
            visitVarInsn(Opcodes.FSTORE, LOCAL_X_RATE);

            visitVarInsn(Opcodes.FLOAD, LOCAL_Y);
            visitVarInsn(Opcodes.ILOAD, LOCAL_YI);
            visitInsn(Opcodes.I2F);
            visitInsn(Opcodes.FSUB);
            visitVarInsn(Opcodes.FSTORE, LOCAL_Y_RATE);

            visitVarInsn(Opcodes.ILOAD, LOCAL_XI);
            visitVarInsn(Opcodes.ILOAD, LOCAL_YI);
            visitVarInsn(Opcodes.ILOAD, LOCAL_COL_PLUS_1);
            visitInsn(Opcodes.IMUL);
            visitInsn(Opcodes.IADD);
            visitInsn(Opcodes.ICONST_1);
            visitInsn(Opcodes.ISHL);
            visitVarInsn(Opcodes.ISTORE, LOCAL_GRID_IDX);

            visitVarInsn(Opcodes.ILOAD, LOCAL_GRID_IDX);
            visitVarInsn(Opcodes.ILOAD, LOCAL_ROW_STRIDE);
            visitInsn(Opcodes.IADD);
            visitVarInsn(Opcodes.ISTORE, LOCAL_NEXT_ROW);
        }

        private void emitQuadComponent(final int componentOffset) {
            emitDstTarget(componentOffset);

            emitGridSample(LOCAL_GRID_IDX, componentOffset);
            visitVarInsn(Opcodes.FLOAD, LOCAL_W0);
            visitInsn(Opcodes.FMUL);
            visitVarInsn(Opcodes.FLOAD, LOCAL_W1);
            visitInsn(Opcodes.FMUL);

            emitGridSample(LOCAL_GRID_IDX, OFFSET_RIGHT + componentOffset);
            visitVarInsn(Opcodes.FLOAD, LOCAL_X_RATE);
            visitInsn(Opcodes.FMUL);
            visitVarInsn(Opcodes.FLOAD, LOCAL_W1);
            visitInsn(Opcodes.FMUL);
            visitInsn(Opcodes.FADD);

            emitGridSample(LOCAL_NEXT_ROW, componentOffset);
            visitVarInsn(Opcodes.FLOAD, LOCAL_W0);
            visitInsn(Opcodes.FMUL);
            visitVarInsn(Opcodes.FLOAD, LOCAL_Y_RATE);
            visitInsn(Opcodes.FMUL);
            visitInsn(Opcodes.FADD);

            emitGridSample(LOCAL_NEXT_ROW, OFFSET_RIGHT + componentOffset);
            visitVarInsn(Opcodes.FLOAD, LOCAL_X_RATE);
            visitInsn(Opcodes.FMUL);
            visitVarInsn(Opcodes.FLOAD, LOCAL_Y_RATE);
            visitInsn(Opcodes.FMUL);
            visitInsn(Opcodes.FADD);

            visitInsn(Opcodes.FASTORE);
        }

        private void emitTriLowerComponent(final int componentOffset) {
            emitDstTarget(componentOffset);

            emitGridSample(LOCAL_GRID_IDX, componentOffset);
            visitVarInsn(Opcodes.FLOAD, LOCAL_W0);
            visitInsn(Opcodes.FMUL);

            emitGridSample(LOCAL_GRID_IDX, OFFSET_RIGHT + componentOffset);
            visitVarInsn(Opcodes.FLOAD, LOCAL_X_RATE);
            visitInsn(Opcodes.FMUL);
            visitInsn(Opcodes.FADD);

            emitGridSample(LOCAL_NEXT_ROW, componentOffset);
            visitVarInsn(Opcodes.FLOAD, LOCAL_Y_RATE);
            visitInsn(Opcodes.FMUL);
            visitInsn(Opcodes.FADD);

            visitInsn(Opcodes.FASTORE);
        }

        private void emitTriUpperComponent(final int componentOffset) {
            emitDstTarget(componentOffset);

            emitGridSample(LOCAL_NEXT_ROW, OFFSET_RIGHT + componentOffset);
            visitVarInsn(Opcodes.FLOAD, LOCAL_W11);
            visitInsn(Opcodes.FMUL);

            emitGridSample(LOCAL_NEXT_ROW, componentOffset);
            visitVarInsn(Opcodes.FLOAD, LOCAL_W0);
            visitInsn(Opcodes.FMUL);
            visitInsn(Opcodes.FADD);

            emitGridSample(LOCAL_GRID_IDX, OFFSET_RIGHT + componentOffset);
            visitVarInsn(Opcodes.FLOAD, LOCAL_W1);
            visitInsn(Opcodes.FMUL);
            visitInsn(Opcodes.FADD);

            visitInsn(Opcodes.FASTORE);
        }

        private void emitDstTarget(final int componentOffset) {
            visitVarInsn(Opcodes.ALOAD, LOCAL_DST);
            visitVarInsn(Opcodes.ILOAD, LOCAL_INDEX_I);
            if (componentOffset != 0) {
                visitIntInsn(Opcodes.BIPUSH, componentOffset);
                visitInsn(Opcodes.IADD);
            }
        }

        private void emitGridSample(final int baseLocal, final int offset) {
            visitVarInsn(Opcodes.ALOAD, LOCAL_PT);
            visitVarInsn(Opcodes.ILOAD, baseLocal);
            if (offset != 0) {
                visitIntInsn(Opcodes.BIPUSH, offset);
                visitInsn(Opcodes.IADD);
            }
            visitInsn(Opcodes.FALOAD);
        }

        private void emitLoopAdvance(final Label loopTop) {
            visitVarInsn(Opcodes.ILOAD, LOCAL_INDEX_I);
            visitVarInsn(Opcodes.ILOAD, LOCAL_SRC_STEP);
            visitInsn(Opcodes.IADD);
            visitVarInsn(Opcodes.ISTORE, LOCAL_INDEX_I);

            visitVarInsn(Opcodes.ILOAD, LOCAL_INDEX_I);
            visitVarInsn(Opcodes.ILOAD, LOCAL_LIMIT);
            visitJumpInsn(Opcodes.IF_ICMPLT, loopTop);
            visitInsn(Opcodes.RETURN);
        }

        private void emitOobDelegation(final Label oobExit, final Label quadTop, final Label triTop) {
            final Label done = new Label();
            visitLabel(oobExit);
            visitVarInsn(Opcodes.ALOAD, LOCAL_BRIDGE_SLOT);
            visitTypeInsn(Opcodes.CHECKCAST, METHOD_HANDLE_INTERNAL);
            visitVarInsn(Opcodes.ALOAD, LOCAL_SRC);
            visitVarInsn(Opcodes.ALOAD, LOCAL_DST);
            visitVarInsn(Opcodes.ILOAD, LOCAL_INDEX_I);
            visitVarInsn(Opcodes.ILOAD, LOCAL_LIMIT);
            visitVarInsn(Opcodes.ILOAD, LOCAL_SRC_STEP);
            visitVarInsn(Opcodes.ALOAD, LOCAL_PT);
            visitVarInsn(Opcodes.ILOAD, LOCAL_COL);
            visitVarInsn(Opcodes.ILOAD, LOCAL_ROW);
            visitVarInsn(Opcodes.ILOAD, LOCAL_IS_QUAD);
            visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    METHOD_HANDLE_INTERNAL,
                    INVOKE_EXACT_METHOD,
                    WarpGridInterpolationTarget.OOB_RUN_DESCRIPTOR,
                    false);
            visitVarInsn(Opcodes.ISTORE, LOCAL_INDEX_I);
            visitVarInsn(Opcodes.ILOAD, LOCAL_INDEX_I);
            visitVarInsn(Opcodes.ILOAD, LOCAL_LIMIT);
            visitJumpInsn(Opcodes.IF_ICMPGE, done);
            visitVarInsn(Opcodes.ILOAD, LOCAL_IS_QUAD);
            visitJumpInsn(Opcodes.IFEQ, triTop);
            visitJumpInsn(Opcodes.GOTO, quadTop);
            visitLabel(done);
            visitInsn(Opcodes.RETURN);
        }
    }

    private record TryCatchBlock(Label start, Label end, Label handler, String type) {}
}
