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
    private static final String INTRINSICS_INTERNAL = "kotlin/jvm/internal/Intrinsics";
    private static final String CHECK_NOT_NULL_METHOD = "checkNotNullParameter";
    private static final String CHECK_NOT_NULL_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/String;)V";
    private static final String EMPTY_STRING = "";
    private static final String GET_BOUNDS_RECT_METHOD = "getBoundsRect";
    private static final String GET_BOUNDS_RECT_DESCRIPTOR = "()L" + WarpGridInterpolationTarget.GRECTF_OWNER + ";";
    private static final String RECT_CONTAINS_METHOD = "contains";
    private static final String RECT_CONTAINS_DESCRIPTOR = "(FF)Z";
    private static final String VECTOR_GET_X_METHOD = "getX";
    private static final String VECTOR_GET_Y_METHOD = "getY";
    private static final String VECTOR_COORD_DESCRIPTOR = "()F";
    private static final String FLOAT_BOX_INTERNAL = "java/lang/Float";
    private static final String FLOAT_IS_NAN_METHOD = "isNaN";
    private static final String FLOAT_IS_NAN_DESCRIPTOR = "(F)Z";
    private static final String COL_FIELD = "col";
    private static final String ROW_FIELD = "row";
    private static final String POINTS_FIELD = "points";
    private static final String INT_DESCRIPTOR = "I";
    private static final String FLOAT_ARRAY_DESCRIPTOR = "[F";

    private static final float BARYCENTRIC_EPS = 1.0e-6f;
    private static final float NEG_BARYCENTRIC_EPS = 0.0f - BARYCENTRIC_EPS;
    private static final float ONE_PLUS_BARYCENTRIC_EPS = 1.0f + BARYCENTRIC_EPS;
    private static final float AABB_PAD_RATIO = 1.0e-4f;
    private static final float AABB_PAD_MIN = 1.0e-5f;

    private static final int SG_LOCAL_PX = 1;
    private static final int SG_LOCAL_PY = 2;
    private static final int SG_LOCAL_TARGET = 3;
    private static final int SG_LOCAL_OUT = 4;
    private static final int SG_LOCAL_COL = 5;
    private static final int SG_LOCAL_ROW = 6;
    private static final int SG_LOCAL_POINTS = 7;
    private static final int SG_LOCAL_COL_PLUS_1 = 8;
    private static final int SG_LOCAL_FCOL = 9;
    private static final int SG_LOCAL_FROW = 10;
    private static final int SG_LOCAL_HAS_TARGET = 11;
    private static final int SG_LOCAL_TARGET_X = 12;
    private static final int SG_LOCAL_TARGET_Y = 13;
    private static final int SG_LOCAL_BEST_U = 14;
    private static final int SG_LOCAL_BEST_V = 15;
    private static final int SG_LOCAL_BEST_DIST_SQ = 16;
    private static final int SG_LOCAL_IY = 17;
    private static final int SG_LOCAL_IX = 18;
    private static final int SG_LOCAL_P0_OFF = 19;
    private static final int SG_LOCAL_P1_OFF = 20;
    private static final int SG_LOCAL_P00X = 21;
    private static final int SG_LOCAL_P00Y = 22;
    private static final int SG_LOCAL_P10X = 23;
    private static final int SG_LOCAL_P10Y = 24;
    private static final int SG_LOCAL_P01X = 25;
    private static final int SG_LOCAL_P01Y = 26;
    private static final int SG_LOCAL_P11X = 27;
    private static final int SG_LOCAL_P11Y = 28;
    private static final int SG_LOCAL_VX = 29;
    private static final int SG_LOCAL_VY = 30;
    private static final int SG_LOCAL_UX = 31;
    private static final int SG_LOCAL_UY = 32;
    private static final int SG_LOCAL_DET = 33;
    private static final int SG_LOCAL_DX = 34;
    private static final int SG_LOCAL_DY = 35;
    private static final int SG_LOCAL_TX = 36;
    private static final int SG_LOCAL_TY = 37;
    private static final int SG_LOCAL_SLOT_PAD = 31;
    private static final int SG_LOCAL_MIN_BOUND = 29;
    private static final int SG_LOCAL_MAX_BOUND = 30;
    private static final int CELL_POINT_STRIDE = 2;
    private static final int P10_X_OFFSET = 2;
    private static final int P10_Y_OFFSET = 3;

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
    private final List<String> simpleGridShape;
    private final List<String> borderGridShape;
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
        this(loader, artifact, reference, null, null, target);
    }

    /**
     * Builds a fail-closed transformer verifying {@code warp.o}, {@code CSimpleGrid}, and {@code warp.grid.a}.
     *
     * @param loader host loader permitted to receive rewrites
     * @param artifact normalized path to the reviewed host JAR
     * @param reference unmodified bytes of {@code com.live2d.cubism.doc.model.deformer.warp.o}
     * @param simpleGridReference optional unmodified bytes of {@code CSimpleGrid}
     * @param borderGridReference optional unmodified bytes of {@code com.live2d.cubism.doc.model.deformer.warp.grid.a}
     * @param target reviewed target metadata for this artifact
     * @throws IllegalArgumentException when a reviewed method is absent from provided reference bytes
     */
    public WarpGridInterpolationTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] reference,
            final byte[] simpleGridReference,
            final byte[] borderGridReference,
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
        simpleGridShape = simpleGridReference == null
                ? null
                : ReviewedMethodShape.read(
                        simpleGridReference,
                        WarpGridInterpolationTarget.SIMPLE_GRID_OWNER,
                        WarpGridInterpolationTarget.SIMPLE_GRID_GET01_EX_METHOD,
                        WarpGridInterpolationTarget.SIMPLE_GRID_GET01_EX_DESCRIPTOR);
        if (simpleGridReference != null && simpleGridShape == null) {
            throw new IllegalArgumentException(FAILURE_METHOD_ABSENT);
        }
        borderGridShape = borderGridReference == null
                ? null
                : ReviewedMethodShape.read(
                        borderGridReference,
                        WarpGridInterpolationTarget.BORDER_GRID_OWNER,
                        WarpGridInterpolationTarget.BORDER_GRID_METHOD,
                        WarpGridInterpolationTarget.BORDER_GRID_DESCRIPTOR);
        if (borderGridReference != null && borderGridShape == null) {
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
        if (actualLoader != loader || bytes == null) {
            return null;
        }
        final boolean isHelper = WarpGridInterpolationTarget.OWNER.equals(name);
        final boolean isSimpleGrid = WarpGridInterpolationTarget.SIMPLE_GRID_OWNER.equals(name);
        final boolean isBorderGrid = WarpGridInterpolationTarget.BORDER_GRID_OWNER.equals(name);
        if (!isHelper && !isSimpleGrid && !isBorderGrid) {
            return null;
        }
        try {
            if (!HostCodeSource.matchesArtifact(domain, artifact)) {
                failure = FAILURE_SOURCE_MISMATCH;
                return null;
            }
            if (isHelper) {
                return transformOwnerMethod(
                        bytes,
                        WarpGridInterpolationTarget.OWNER,
                        WarpGridInterpolationTarget.METHOD,
                        WarpGridInterpolationTarget.DESCRIPTOR,
                        shape);
            }
            if (isSimpleGrid) {
                return transformOwnerMethod(
                        bytes,
                        WarpGridInterpolationTarget.SIMPLE_GRID_OWNER,
                        WarpGridInterpolationTarget.SIMPLE_GRID_GET01_EX_METHOD,
                        WarpGridInterpolationTarget.SIMPLE_GRID_GET01_EX_DESCRIPTOR,
                        simpleGridShape);
            }
            return transformOwnerMethod(
                    bytes,
                    WarpGridInterpolationTarget.BORDER_GRID_OWNER,
                    WarpGridInterpolationTarget.BORDER_GRID_METHOD,
                    WarpGridInterpolationTarget.BORDER_GRID_DESCRIPTOR,
                    borderGridShape);
        } catch (final Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure =
                    rejected + FAILURE_LOCATOR_SEPARATOR + (trace.length == 0 ? NO_FRAMES_MARKER : trace[0].toString());
            return null;
        }
    }

    private byte[] transformOwnerMethod(
            final byte[] bytes,
            final String owner,
            final String method,
            final String descriptor,
            final List<String> expectedShape) {
        final List<String> actualShape = ReviewedMethodShape.read(bytes, owner, method, descriptor);
        if (actualShape == null) {
            failure = FAILURE_METHOD_ABSENT;
            return null;
        }
        if (expectedShape != null && !expectedShape.equals(actualShape)) {
            failure = FAILURE_SHAPE_MISMATCH;
            return null;
        }
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final boolean[] applied = {false};
        final ClassVisitor visitor =
                switch (owner) {
                    case WarpGridInterpolationTarget.OWNER -> new WarpGridClassVisitor(writer, applied);
                    case WarpGridInterpolationTarget.SIMPLE_GRID_OWNER -> new SimpleGridClassVisitor(writer, applied);
                    case WarpGridInterpolationTarget.BORDER_GRID_OWNER -> new BorderGridClassVisitor(writer, applied);
                    default -> throw new IllegalStateException(FAILURE_NOT_APPLIED);
                };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (!applied[0]) {
            failure = FAILURE_NOT_APPLIED;
            return null;
        }
        matches++;
        return writer.toByteArray();
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

    private static final class SimpleGridClassVisitor extends ClassVisitor {
        private final boolean[] applied;

        private SimpleGridClassVisitor(final ClassVisitor delegate, final boolean[] applied) {
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
            if (!WarpGridInterpolationTarget.SIMPLE_GRID_GET01_EX_METHOD.equals(method)
                    || !WarpGridInterpolationTarget.SIMPLE_GRID_GET01_EX_DESCRIPTOR.equals(descriptor)) {
                return visitor;
            }
            applied[0] = true;
            return new SimpleGridGet01ExReplacer(visitor);
        }
    }

    private static final class SimpleGridGet01ExReplacer extends MethodVisitor {
        private final MethodVisitor target;

        private SimpleGridGet01ExReplacer(final MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitParameter(final String name, final int access) {
            target.visitParameter(name, access);
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitAnnotation(final String desc, final boolean visible) {
            return target.visitAnnotation(desc, visible);
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitParameterAnnotation(
                final int parameter, final String desc, final boolean visible) {
            return target.visitParameterAnnotation(parameter, desc, visible);
        }

        @Override
        public void visitCode() {
            target.visitCode();
            emitBody();
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        private void emitBody() {
            final Label outNonNull = new Label();
            final Label inBounds = new Label();
            final Label returnNull = new Label();
            final Label dimsValid = new Label();
            final Label noTarget = new Label();
            final Label targetReady = new Label();
            final Label rowLoop = new Label();
            final Label rowDone = new Label();
            final Label colLoop = new Label();
            final Label colDone = new Label();
            final Label colNext = new Label();
            final Label tri2 = new Label();
            final Label cellHit = new Label();
            final Label hasTargetBranch = new Label();
            final Label takeBest = new Label();
            final Label returnBest = new Label();

            // Null-check out parameter without calling Intrinsics when non-null.
            target.visitVarInsn(Opcodes.ALOAD, SG_LOCAL_OUT);
            target.visitJumpInsn(Opcodes.IFNONNULL, outNonNull);
            target.visitVarInsn(Opcodes.ALOAD, SG_LOCAL_OUT);
            target.visitLdcInsn(EMPTY_STRING);
            target.visitMethodInsn(
                    Opcodes.INVOKESTATIC, INTRINSICS_INTERNAL, CHECK_NOT_NULL_METHOD, CHECK_NOT_NULL_DESCRIPTOR, false);

            target.visitLabel(outNonNull);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    WarpGridInterpolationTarget.SIMPLE_GRID_OWNER,
                    GET_BOUNDS_RECT_METHOD,
                    GET_BOUNDS_RECT_DESCRIPTOR,
                    false);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_PX);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_PY);
            target.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    WarpGridInterpolationTarget.GRECTF_OWNER,
                    RECT_CONTAINS_METHOD,
                    RECT_CONTAINS_DESCRIPTOR,
                    false);
            target.visitJumpInsn(Opcodes.IFNE, inBounds);
            target.visitInsn(Opcodes.ACONST_NULL);
            target.visitInsn(Opcodes.ARETURN);

            target.visitLabel(inBounds);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(
                    Opcodes.GETFIELD, WarpGridInterpolationTarget.SIMPLE_GRID_OWNER, COL_FIELD, INT_DESCRIPTOR);
            target.visitVarInsn(Opcodes.ISTORE, SG_LOCAL_COL);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(
                    Opcodes.GETFIELD, WarpGridInterpolationTarget.SIMPLE_GRID_OWNER, ROW_FIELD, INT_DESCRIPTOR);
            target.visitVarInsn(Opcodes.ISTORE, SG_LOCAL_ROW);
            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_COL);
            target.visitJumpInsn(Opcodes.IFLE, returnNull);
            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_ROW);
            target.visitJumpInsn(Opcodes.IFGT, dimsValid);
            target.visitLabel(returnNull);
            target.visitInsn(Opcodes.ACONST_NULL);
            target.visitInsn(Opcodes.ARETURN);

            target.visitLabel(dimsValid);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(
                    Opcodes.GETFIELD,
                    WarpGridInterpolationTarget.SIMPLE_GRID_OWNER,
                    POINTS_FIELD,
                    FLOAT_ARRAY_DESCRIPTOR);
            target.visitVarInsn(Opcodes.ASTORE, SG_LOCAL_POINTS);
            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_COL);
            target.visitInsn(Opcodes.ICONST_1);
            target.visitInsn(Opcodes.IADD);
            target.visitVarInsn(Opcodes.ISTORE, SG_LOCAL_COL_PLUS_1);
            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_COL);
            target.visitInsn(Opcodes.I2F);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_FCOL);
            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_ROW);
            target.visitInsn(Opcodes.I2F);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_FROW);

            target.visitVarInsn(Opcodes.ALOAD, SG_LOCAL_TARGET);
            target.visitJumpInsn(Opcodes.IFNULL, noTarget);
            target.visitInsn(Opcodes.ICONST_1);
            target.visitVarInsn(Opcodes.ISTORE, SG_LOCAL_HAS_TARGET);
            target.visitVarInsn(Opcodes.ALOAD, SG_LOCAL_TARGET);
            target.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    WarpGridInterpolationTarget.GVECTOR2_OWNER,
                    VECTOR_GET_X_METHOD,
                    VECTOR_COORD_DESCRIPTOR,
                    false);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_TARGET_X);
            target.visitVarInsn(Opcodes.ALOAD, SG_LOCAL_TARGET);
            target.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    WarpGridInterpolationTarget.GVECTOR2_OWNER,
                    VECTOR_GET_Y_METHOD,
                    VECTOR_COORD_DESCRIPTOR,
                    false);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_TARGET_Y);
            target.visitJumpInsn(Opcodes.GOTO, targetReady);

            target.visitLabel(noTarget);
            target.visitInsn(Opcodes.ICONST_0);
            target.visitVarInsn(Opcodes.ISTORE, SG_LOCAL_HAS_TARGET);
            target.visitInsn(Opcodes.FCONST_0);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_TARGET_X);
            target.visitInsn(Opcodes.FCONST_0);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_TARGET_Y);

            target.visitLabel(targetReady);
            target.visitLdcInsn(Float.NaN);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_BEST_U);
            target.visitInsn(Opcodes.FCONST_0);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_BEST_V);
            target.visitInsn(Opcodes.FCONST_0);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_BEST_DIST_SQ);

            target.visitInsn(Opcodes.ICONST_0);
            target.visitVarInsn(Opcodes.ISTORE, SG_LOCAL_IY);
            target.visitLabel(rowLoop);
            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_IY);
            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_ROW);
            target.visitJumpInsn(Opcodes.IF_ICMPGE, rowDone);

            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_IY);
            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_COL_PLUS_1);
            target.visitInsn(Opcodes.IMUL);
            target.visitInsn(Opcodes.DUP);
            target.visitInsn(Opcodes.ICONST_1);
            target.visitInsn(Opcodes.ISHL);
            target.visitVarInsn(Opcodes.ISTORE, SG_LOCAL_P0_OFF);
            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_COL_PLUS_1);
            target.visitInsn(Opcodes.IADD);
            target.visitInsn(Opcodes.ICONST_1);
            target.visitInsn(Opcodes.ISHL);
            target.visitVarInsn(Opcodes.ISTORE, SG_LOCAL_P1_OFF);

            target.visitInsn(Opcodes.ICONST_0);
            target.visitVarInsn(Opcodes.ISTORE, SG_LOCAL_IX);
            target.visitLabel(colLoop);
            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_IX);
            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_COL);
            target.visitJumpInsn(Opcodes.IF_ICMPGE, colDone);

            emitLoadCellVertices();
            emitAxisAabbReject(SG_LOCAL_PX, SG_LOCAL_P00X, SG_LOCAL_P10X, SG_LOCAL_P01X, SG_LOCAL_P11X, colNext);
            emitAxisAabbReject(SG_LOCAL_PY, SG_LOCAL_P00Y, SG_LOCAL_P10Y, SG_LOCAL_P01Y, SG_LOCAL_P11Y, colNext);

            // Triangle 1: (p00, p10, p01).
            emitBarycentricTriangleCheck(
                    SG_LOCAL_P00X,
                    SG_LOCAL_P00Y,
                    SG_LOCAL_P10X,
                    SG_LOCAL_P10Y,
                    SG_LOCAL_P01X,
                    SG_LOCAL_P01Y,
                    false,
                    tri2,
                    cellHit);

            // Triangle 2: (p11, p01, p10).
            target.visitLabel(tri2);
            emitBarycentricTriangleCheck(
                    SG_LOCAL_P11X,
                    SG_LOCAL_P11Y,
                    SG_LOCAL_P01X,
                    SG_LOCAL_P01Y,
                    SG_LOCAL_P10X,
                    SG_LOCAL_P10Y,
                    true,
                    colNext,
                    cellHit);

            target.visitLabel(cellHit);
            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_IX);
            target.visitInsn(Opcodes.I2F);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TX);
            target.visitInsn(Opcodes.FADD);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_FCOL);
            target.visitInsn(Opcodes.FDIV);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_TX);

            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_IY);
            target.visitInsn(Opcodes.I2F);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TY);
            target.visitInsn(Opcodes.FADD);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_FROW);
            target.visitInsn(Opcodes.FDIV);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_TY);

            target.visitVarInsn(Opcodes.ILOAD, SG_LOCAL_HAS_TARGET);
            target.visitJumpInsn(Opcodes.IFNE, hasTargetBranch);
            target.visitVarInsn(Opcodes.ALOAD, SG_LOCAL_OUT);
            target.visitInsn(Opcodes.ICONST_0);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TX);
            target.visitInsn(Opcodes.FASTORE);
            target.visitVarInsn(Opcodes.ALOAD, SG_LOCAL_OUT);
            target.visitInsn(Opcodes.ICONST_1);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TY);
            target.visitInsn(Opcodes.FASTORE);
            target.visitVarInsn(Opcodes.ALOAD, SG_LOCAL_OUT);
            target.visitInsn(Opcodes.ARETURN);

            target.visitLabel(hasTargetBranch);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TARGET_X);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TX);
            target.visitInsn(Opcodes.FSUB);
            target.visitInsn(Opcodes.DUP);
            target.visitInsn(Opcodes.FMUL);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TARGET_Y);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TY);
            target.visitInsn(Opcodes.FSUB);
            target.visitInsn(Opcodes.DUP);
            target.visitInsn(Opcodes.FMUL);
            target.visitInsn(Opcodes.FADD);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_DX);

            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_BEST_U);
            target.visitMethodInsn(
                    Opcodes.INVOKESTATIC, FLOAT_BOX_INTERNAL, FLOAT_IS_NAN_METHOD, FLOAT_IS_NAN_DESCRIPTOR, false);
            target.visitJumpInsn(Opcodes.IFNE, takeBest);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_BEST_DIST_SQ);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_DX);
            target.visitInsn(Opcodes.FCMPL);
            target.visitJumpInsn(Opcodes.IFLE, colNext);

            target.visitLabel(takeBest);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TX);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_BEST_U);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TY);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_BEST_V);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_DX);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_BEST_DIST_SQ);

            target.visitLabel(colNext);
            target.visitIincInsn(SG_LOCAL_IX, 1);
            target.visitIincInsn(SG_LOCAL_P0_OFF, CELL_POINT_STRIDE);
            target.visitIincInsn(SG_LOCAL_P1_OFF, CELL_POINT_STRIDE);
            target.visitJumpInsn(Opcodes.GOTO, colLoop);

            target.visitLabel(colDone);
            target.visitIincInsn(SG_LOCAL_IY, 1);
            target.visitJumpInsn(Opcodes.GOTO, rowLoop);

            target.visitLabel(rowDone);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_BEST_U);
            target.visitMethodInsn(
                    Opcodes.INVOKESTATIC, FLOAT_BOX_INTERNAL, FLOAT_IS_NAN_METHOD, FLOAT_IS_NAN_DESCRIPTOR, false);
            target.visitJumpInsn(Opcodes.IFEQ, returnBest);
            target.visitInsn(Opcodes.ACONST_NULL);
            target.visitInsn(Opcodes.ARETURN);

            target.visitLabel(returnBest);
            target.visitVarInsn(Opcodes.ALOAD, SG_LOCAL_OUT);
            target.visitInsn(Opcodes.ICONST_0);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_BEST_U);
            target.visitInsn(Opcodes.FASTORE);
            target.visitVarInsn(Opcodes.ALOAD, SG_LOCAL_OUT);
            target.visitInsn(Opcodes.ICONST_1);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_BEST_V);
            target.visitInsn(Opcodes.FASTORE);
            target.visitVarInsn(Opcodes.ALOAD, SG_LOCAL_OUT);
            target.visitInsn(Opcodes.ARETURN);
        }

        private void emitLoadCellVertices() {
            emitLoadCoord(SG_LOCAL_P0_OFF, 0, SG_LOCAL_P00X);
            emitLoadCoord(SG_LOCAL_P0_OFF, 1, SG_LOCAL_P00Y);
            emitLoadCoord(SG_LOCAL_P0_OFF, P10_X_OFFSET, SG_LOCAL_P10X);
            emitLoadCoord(SG_LOCAL_P0_OFF, P10_Y_OFFSET, SG_LOCAL_P10Y);
            emitLoadCoord(SG_LOCAL_P1_OFF, 0, SG_LOCAL_P01X);
            emitLoadCoord(SG_LOCAL_P1_OFF, 1, SG_LOCAL_P01Y);
            emitLoadCoord(SG_LOCAL_P1_OFF, P10_X_OFFSET, SG_LOCAL_P11X);
            emitLoadCoord(SG_LOCAL_P1_OFF, P10_Y_OFFSET, SG_LOCAL_P11Y);
        }

        private void emitLoadCoord(final int baseLocal, final int offset, final int dstLocal) {
            target.visitVarInsn(Opcodes.ALOAD, SG_LOCAL_POINTS);
            target.visitVarInsn(Opcodes.ILOAD, baseLocal);
            if (offset != 0) {
                target.visitIntInsn(Opcodes.BIPUSH, offset);
                target.visitInsn(Opcodes.IADD);
            }
            target.visitInsn(Opcodes.FALOAD);
            target.visitVarInsn(Opcodes.FSTORE, dstLocal);
        }

        private void emitAxisAabbReject(
                final int queryLocal, final int v0, final int v1, final int v2, final int v3, final Label missLabel) {
            target.visitLdcInsn(Float.POSITIVE_INFINITY);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_MIN_BOUND);
            target.visitLdcInsn(Float.NEGATIVE_INFINITY);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_MAX_BOUND);
            emitUpdateMinMax(v0);
            emitUpdateMinMax(v1);
            emitUpdateMinMax(v2);
            emitUpdateMinMax(v3);

            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_MAX_BOUND);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_MIN_BOUND);
            target.visitInsn(Opcodes.FSUB);
            target.visitLdcInsn(AABB_PAD_RATIO);
            target.visitInsn(Opcodes.FMUL);
            target.visitLdcInsn(AABB_PAD_MIN);
            target.visitInsn(Opcodes.FADD);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_SLOT_PAD);

            target.visitVarInsn(Opcodes.FLOAD, queryLocal);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_MIN_BOUND);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_SLOT_PAD);
            target.visitInsn(Opcodes.FSUB);
            target.visitInsn(Opcodes.FCMPL);
            target.visitJumpInsn(Opcodes.IFLT, missLabel);

            target.visitVarInsn(Opcodes.FLOAD, queryLocal);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_MAX_BOUND);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_SLOT_PAD);
            target.visitInsn(Opcodes.FADD);
            target.visitInsn(Opcodes.FCMPG);
            target.visitJumpInsn(Opcodes.IFGT, missLabel);
        }

        private void emitUpdateMinMax(final int valLocal) {
            final Label notSmaller = new Label();
            final Label notLarger = new Label();
            target.visitVarInsn(Opcodes.FLOAD, valLocal);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_MIN_BOUND);
            target.visitInsn(Opcodes.FCMPG);
            target.visitJumpInsn(Opcodes.IFGE, notSmaller);
            target.visitVarInsn(Opcodes.FLOAD, valLocal);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_MIN_BOUND);
            target.visitLabel(notSmaller);

            target.visitVarInsn(Opcodes.FLOAD, valLocal);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_MAX_BOUND);
            target.visitInsn(Opcodes.FCMPL);
            target.visitJumpInsn(Opcodes.IFLE, notLarger);
            target.visitVarInsn(Opcodes.FLOAD, valLocal);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_MAX_BOUND);
            target.visitLabel(notLarger);
        }

        private void emitBarycentricTriangleCheck(
                final int ax,
                final int ay,
                final int bx,
                final int by,
                final int cx,
                final int cy,
                final boolean flipFromOne,
                final Label missLabel,
                final Label hitLabel) {
            emitDiff(bx, ax, SG_LOCAL_VX);
            emitDiff(by, ay, SG_LOCAL_VY);
            emitDiff(cx, ax, SG_LOCAL_UX);
            emitDiff(cy, ay, SG_LOCAL_UY);

            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_VX);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_UY);
            target.visitInsn(Opcodes.FMUL);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_UX);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_VY);
            target.visitInsn(Opcodes.FMUL);
            target.visitInsn(Opcodes.FSUB);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_DET);

            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_DET);
            target.visitInsn(Opcodes.FCONST_0);
            target.visitInsn(Opcodes.FCMPG);
            target.visitJumpInsn(Opcodes.IFEQ, missLabel);

            emitDiff(SG_LOCAL_PX, ax, SG_LOCAL_DX);
            emitDiff(SG_LOCAL_PY, ay, SG_LOCAL_DY);

            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_DX);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_UY);
            target.visitInsn(Opcodes.FMUL);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_DY);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_UX);
            target.visitInsn(Opcodes.FMUL);
            target.visitInsn(Opcodes.FSUB);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_DET);
            target.visitInsn(Opcodes.FDIV);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_TX);

            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_DY);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_VX);
            target.visitInsn(Opcodes.FMUL);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_DX);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_VY);
            target.visitInsn(Opcodes.FMUL);
            target.visitInsn(Opcodes.FSUB);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_DET);
            target.visitInsn(Opcodes.FDIV);
            target.visitVarInsn(Opcodes.FSTORE, SG_LOCAL_TY);

            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TX);
            target.visitLdcInsn(NEG_BARYCENTRIC_EPS);
            target.visitInsn(Opcodes.FCMPL);
            target.visitJumpInsn(Opcodes.IFLE, missLabel);

            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TY);
            target.visitLdcInsn(NEG_BARYCENTRIC_EPS);
            target.visitInsn(Opcodes.FCMPL);
            target.visitJumpInsn(Opcodes.IFLE, missLabel);

            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TX);
            target.visitVarInsn(Opcodes.FLOAD, SG_LOCAL_TY);
            target.visitInsn(Opcodes.FADD);
            target.visitLdcInsn(ONE_PLUS_BARYCENTRIC_EPS);
            target.visitInsn(Opcodes.FCMPG);
            target.visitJumpInsn(Opcodes.IFGE, missLabel);

            if (flipFromOne) {
                emitOneMinus(SG_LOCAL_TX);
                emitOneMinus(SG_LOCAL_TY);
            }
            emitClamp01(SG_LOCAL_TX);
            emitClamp01(SG_LOCAL_TY);
            target.visitJumpInsn(Opcodes.GOTO, hitLabel);
        }

        private void emitDiff(final int minuend, final int subtrahend, final int dstLocal) {
            target.visitVarInsn(Opcodes.FLOAD, minuend);
            target.visitVarInsn(Opcodes.FLOAD, subtrahend);
            target.visitInsn(Opcodes.FSUB);
            target.visitVarInsn(Opcodes.FSTORE, dstLocal);
        }

        private void emitOneMinus(final int local) {
            target.visitInsn(Opcodes.FCONST_1);
            target.visitVarInsn(Opcodes.FLOAD, local);
            target.visitInsn(Opcodes.FSUB);
            target.visitVarInsn(Opcodes.FSTORE, local);
        }

        private void emitClamp01(final int local) {
            final Label checkHigh = new Label();
            final Label done = new Label();
            target.visitVarInsn(Opcodes.FLOAD, local);
            target.visitInsn(Opcodes.FCONST_0);
            target.visitInsn(Opcodes.FCMPG);
            target.visitJumpInsn(Opcodes.IFGE, checkHigh);
            target.visitInsn(Opcodes.FCONST_0);
            target.visitVarInsn(Opcodes.FSTORE, local);
            target.visitJumpInsn(Opcodes.GOTO, done);

            target.visitLabel(checkHigh);
            target.visitVarInsn(Opcodes.FLOAD, local);
            target.visitInsn(Opcodes.FCONST_1);
            target.visitInsn(Opcodes.FCMPL);
            target.visitJumpInsn(Opcodes.IFLE, done);
            target.visitInsn(Opcodes.FCONST_1);
            target.visitVarInsn(Opcodes.FSTORE, local);
            target.visitLabel(done);
        }
    }

    private static final class BorderGridClassVisitor extends ClassVisitor {
        private final boolean[] applied;

        private BorderGridClassVisitor(final ClassVisitor delegate, final boolean[] applied) {
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
            if (!WarpGridInterpolationTarget.BORDER_GRID_METHOD.equals(method)
                    || !WarpGridInterpolationTarget.BORDER_GRID_DESCRIPTOR.equals(descriptor)) {
                return visitor;
            }
            return new BorderGridMethodAdapter(visitor, applied);
        }
    }

    private static final class BorderGridMethodAdapter extends MethodVisitor {
        private static final String INTRINSICS_OWNER = "kotlin/jvm/internal/Intrinsics";
        private static final String CHECK_NOT_NULL_METHOD = "checkNotNullParameter";
        private static final String BORDER_ITEMS_FIELD = "i";
        private static final String ARRAY_LIST_INTERNAL = "java/util/ArrayList";
        private static final String ARRAY_LIST_DESCRIPTOR = "Ljava/util/ArrayList;";
        private static final String IS_EMPTY_METHOD = "isEmpty";
        private static final String BOOLEAN_NO_ARG_DESCRIPTOR = "()Z";

        private final boolean[] applied;
        private boolean hooked;

        private BorderGridMethodAdapter(final MethodVisitor delegate, final boolean[] applied) {
            super(Opcodes.ASM9, delegate);
            this.applied = applied;
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (!hooked
                    && opcode == Opcodes.INVOKESTATIC
                    && INTRINSICS_OWNER.equals(owner)
                    && CHECK_NOT_NULL_METHOD.equals(name)) {
                hooked = true;
                applied[0] = true;
                final Label fallbackNoPop = new Label();
                final Label fallbackPop = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        WarpGridInterpolationTarget.BORDER_GRID_OWNER,
                        BORDER_ITEMS_FIELD,
                        ARRAY_LIST_DESCRIPTOR);
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, ARRAY_LIST_INTERNAL, IS_EMPTY_METHOD, BOOLEAN_NO_ARG_DESCRIPTOR, false);
                mv.visitJumpInsn(Opcodes.IFNE, fallbackNoPop);
                SlotBridgeEmitter.emitGetSlot(mv, WarpGridInterpolationBridge.BORDER_GRID_PROPERTY);
                mv.visitInsn(Opcodes.DUP);
                mv.visitJumpInsn(Opcodes.IFNULL, fallbackPop);
                mv.visitTypeInsn(Opcodes.CHECKCAST, METHOD_HANDLE_INTERNAL);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.FLOAD, 1);
                mv.visitVarInsn(Opcodes.FLOAD, 2);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        METHOD_HANDLE_INTERNAL,
                        INVOKE_EXACT_METHOD,
                        WarpGridInterpolationTarget.BORDER_GRID_SOLVE_DESCRIPTOR,
                        false);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(fallbackPop);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(fallbackNoPop);
            }
        }
    }

    private record TryCatchBlock(Label start, Label end, Label handler, String type) {}
}
