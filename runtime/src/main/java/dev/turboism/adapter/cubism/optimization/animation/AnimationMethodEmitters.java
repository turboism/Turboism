package dev.turboism.adapter.cubism.optimization.animation;

import dev.turboism.adapter.cubism.optimization.SlotBridgeEmitter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits allocation-free bytecode fast paths for Cubism animation evaluation and EDT painting.
 *
 * <p>Every emitter consults {@link AnimationAccelerationBridge} slots before choosing a fast path
 * and falls back cleanly to unmodified Cubism bytecode when disabled or unbound.</p>
 */
final class AnimationMethodEmitters {

    static final String METHOD_HANDLE_OWNER = "java/lang/invoke/MethodHandle";
    static final String INVOKE_EXACT = "invokeExact";
    static final String THROWABLE_OWNER = "java/lang/Throwable";
    static final String INTRINSICS_OWNER = "kotlin/jvm/internal/Intrinsics";
    static final String CHECK_NOT_NULL_METHOD = "checkNotNull";
    static final String CHECK_NOT_NULL_OBJ_DESC = "(Ljava/lang/Object;)V";
    static final String ARRAYS_KT_OWNER = "kotlin/collections/ArraysKt";
    static final String GET_OR_NULL_METHOD = "getOrNull";
    static final String GET_OR_NULL_DESC = "([Ljava/lang/Object;I)Ljava/lang/Object;";
    static final String LAST_OR_NULL_METHOD = "lastOrNull";
    static final String LAST_OR_NULL_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";
    static final String FLOAT_BOX_OWNER = "java/lang/Float";
    static final String FLOAT_VALUE_OF_METHOD = "valueOf";
    static final String FLOAT_VALUE_OF_DESC = "(F)Ljava/lang/Float;";
    static final String INT_RANGE_OWNER = "kotlin/ranges/IntRange";
    static final String INT_RANGE_GET_FIRST = "getFirst";
    static final String INT_RANGE_GET_LAST = "getLast";
    static final String INT_RANGE_GET_START = "getStart";
    static final String INT_RANGE_CONTAINS = "contains";
    static final String INTEGER_OWNER = "java/lang/Integer";
    static final String INT_VALUE_METHOD = "intValue";
    static final String PATH2D_FLOAT_OWNER = "java/awt/geom/Path2D$Float";
    static final String BASIC_STROKE_OWNER = "java/awt/BasicStroke";
    static final String STROKE_OWNER = "java/awt/Stroke";
    static final String SHAPE_OWNER = "java/awt/Shape";
    static final String GRAPHICS_WRAPPER_OWNER = "com/live2d/ui/g";
    static final String CCOLOR_OWNER = "com/live2d/type/CColor";
    static final String CFONT_OWNER = "com/live2d/type/CFont";
    static final String AWT_POINT_OWNER = "java/awt/Point";
    static final String CUSTOM_PAINT_COMP_OWNER = "com/live2d/ui/control/CCustomPaintComponent";
    static final String TRACK_INSTANCE_BASE_OWNER = "com/live2d/cubism/doc/animation/movie/track/ICMvTrack_Instance";
    static final String MODEL_OWNER = "com/live2d/cubism/doc/model/CModel";
    static final String PARAM_SET_OWNER = "com/live2d/cubism/doc/model/param/CParameterSet";
    static final String CURVE_TYPE_OWNER = AnimationAccelerationTarget.CURVE_TYPE_OWNER;
    static final String CURVE_TYPE_ARRAY_LIST_METHOD = "get";
    static final String FUNCTION1_OWNER = "kotlin/jvm/functions/Function1";
    static final String BOOLEAN_BOX_OWNER = "java/lang/Boolean";
    static final String BOOLEAN_VALUE_METHOD = "booleanValue";
    static final String SCENE_SOURCE_OWNER = "com/live2d/cubism/doc/animation/CSceneSource";
    static final String CORE_TIMELINE_INFO_OWNER = "com/live2d/cubism/doc/animation/movie/core/b";

    static final String DECIMAL_FORMAT_OWNER = "java/text/DecimalFormat";
    static final String DECIMAL_FORMAT_METHOD = "format";
    static final String DECIMAL_FORMAT_DESC = "(D)Ljava/lang/String;";
    static final String SUBFRAME_MH_DESC = "(IIZ)Ljava/lang/String;";
    static final String TIMECODE_MH_DESC = "(IZLjava/lang/String;)Ljava/lang/String;";
    static final String FRAME_PAD_MH_DESC = "(IZ)Ljava/lang/String;";
    static final String RULER_TIMECODE_MH_DESC = "(IIZLjava/lang/String;)Ljava/lang/String;";
    static final String RULER_FRAME_MH_DESC = "(I)Ljava/lang/String;";
    static final String DECIMAL_MH_DESC = "(Ljava/text/DecimalFormat;D)Ljava/lang/String;";

    private static final int DRAW_CURVE_ARRAY_LEN = 8;
    private static final int HANDLE_RADIUS_HALF = 3;
    private static final int HANDLE_BOX_SIZE = 6;
    private static final int DOT_INNER_HALF = 2;
    private static final int DOT_INNER_SIZE = 4;
    private static final int LABEL_OFFSET_X = 5;
    private static final float CURVE_STROKE_WIDTH = 2.0f;
    private static final float TEXT_Y_OFFSET_POS = 4.0f;
    private static final float TEXT_Y_OFFSET_NEG = -4.0f;
    private static final int DEFAULT_HANDLE_BITMASK = 64;
    private static final String EMPTY_STRING = "";
    private static final String SINGLE_SPACE = " ";
    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final String INT_DESC = "I";
    private static final String BOOL_DESC = "Z";
    private static final String VOID_INT_DESC = "(I)V";
    private static final String VOID_BOOL_DESC = "(Z)V";
    private static final String VOID_FLOAT_DESC = "(F)V";
    private static final String VOID_II_DESC = "(II)V";
    private static final String VOID_FF_DESC = "(FF)V";
    private static final String VOID_IIII_DESC = "(IIII)V";
    private static final String VOID_IIIIF_DESC = "(IIIIF)V";
    private static final String VOID_STRING_FF_DESC = "(Ljava/lang/String;FF)V";
    private static final String VOID_FFFFFF_DESC = "(FFFFFF)V";
    private static final String VOID_STRING_STRING_DESC = "(Ljava/lang/String;Ljava/lang/String;)V";
    private static final String INT_VOID_DESC = "()I";
    private static final String BOOL_VOID_DESC = "()Z";
    private static final String FLOAT_VOID_DESC = "()F";
    private static final String DOUBLE_VOID_DESC = "()D";
    private static final String INT_INT_DESC = "(I)I";
    private static final String BOOL_INT_DESC = "(I)Z";
    private static final String FLOAT_INT_DESC = "(I)F";
    private static final String FLOAT_DOUBLE_DESC = "(D)F";
    private static final String OBJ_INT_DESC = "(I)Ljava/lang/Object;";
    private static final String OBJ_OBJ_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String STRING_STRING_DESC = "(Ljava/lang/String;)Ljava/lang/String;";
    private static final String FIELD_SINGLETON_A = "a";
    private static final String LOG_INVOKE_METHOD = "c";
    private static final String LOG_INVOKE_DESC = "(Lkotlin/jvm/functions/Function0;)V";
    private static final String FIELD_COMPANION = "Companion";
    private static final String FIELD_DF = "df";
    private static final String FIELD_PAINT_COMP = "paintComp";
    private static final String FIELD_BEZIER_HANDLE_COLOR = "color_BezierHandle";
    private static final String FIELD_SELECTED_DOT_COLOR = "color_selectedDot";
    private static final String FIELD_SHOW_KF_VALUE = "enableShowKeyframesValue";
    private static final String FIELD_SHOW_ATTR_NAME = "enableDisplayAttributeName";
    private static final String METHOD_GET_SUBFRAME = "getDisplayTimeSubFrame";
    private static final String METHOD_GET_FPS_INT = "getFpsInt";
    private static final String METHOD_GET_MOVIE_INFO = "getMovieInfo";
    private static final String METHOD_GET_TIMELINE_INFO = "getTimelineInfo";
    private static final String METHOD_GET_DISPLAY_SEC = "getDisplayTimeSec";
    private static final String METHOD_GET_DISPLAY_FRAME = "getDisplayFrame";
    private static final String METHOD_SETUP_TRANSFORM = "setupTransform__testImpl";
    private static final String METHOD_SIZE = "size";
    private static final String METHOD_GET = "get";
    private static final String METHOD_INVOKE = "invoke";
    private static final String METHOD_GET_ENABLED = "getEnabled";
    private static final String METHOD_SET_ENABLED = "setEnabled";
    private static final String METHOD_TRACK_CTX_G = "g";
    private static final String METHOD_GET_POS = "getPos";
    private static final String METHOD_GET_TIMELINE_X = "getTimelineX";
    private static final String METHOD_GET_TIMELINE_Y = "getTimelineY";
    private static final String METHOD_CALC_BEZIER_CTRL = "calcBezierController";
    private static final String METHOD_GET_CURVE_TYPES = "getCurveTypes";
    private static final String METHOD_ORDINAL = "ordinal";
    private static final String METHOD_MOVE_TO = "moveTo";
    private static final String METHOD_CURVE_TO = "curveTo";
    private static final String METHOD_LINE_TO = "lineTo";
    private static final String METHOD_GET_WIDTH = "getWidth";
    private static final String METHOD_OVERLAPPING_GUARD = "overlappingPointsGuard";
    private static final String METHOD_GET_ANCHOR = "getAnchor$cubism";
    private static final String METHOD_GET_DOUBLE_VALUE = "getDoubleValue";
    private static final String METHOD_POINT_COLOR = "drawCurve$getPointColor";
    private static final String METHOD_POINT_TEXT_COLOR = "drawCurve$getPointTextColor";
    private static final String METHOD_VALUE_TEXT_POS = "drawCurve$getValueTextVerticalPosition";
    private static final String METHOD_GET_SIZE = "getSize";
    private static final String METHOD_IS_EMPTY = "isEmpty";
    private static final String METHOD_CONCAT = "concat";
    private static final String STRING_OWNER = "java/lang/String";
    private static final String FUNCTION0_OWNER = "kotlin/jvm/functions/Function0";
    private static final String COMPARE_STUB_A = "a";
    private static final String DRAW_LINE_METHOD = "a";
    private static final String DRAW_TEXT_METHOD = "a";
    private static final String DRAW_COLOR_METHOD = "b";
    private static final String DRAW_SHAPE_METHOD = "b";
    private static final String DRAW_STROKE_METHOD = "a";
    private static final String DRAW_FILL_RECT_METHOD = "c";
    private static final String DRAW_RECT_METHOD = "f";
    private static final String FONT_QUERY_METHOD = "j";

    private AnimationMethodEmitters() {}

    /** Emits active-gate check targeting {@code fallback} when disabled or unbridged. */
    static void emitActiveGate(final MethodVisitor mv, final Label fallback) {
        final Label popAndFallback = new Label();
        final Label active = new Label();
        SlotBridgeEmitter.emitPredicateConsult(mv, AnimationAccelerationBridge.ACTIVE_PROPERTY, popAndFallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        SlotBridgeEmitter.emitTest(mv);
        mv.visitJumpInsn(Opcodes.IFNE, active);
        mv.visitJumpInsn(Opcodes.GOTO, fallback);
        mv.visitLabel(popAndFallback);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, fallback);
        mv.visitLabel(active);
    }

    /** Emits guarded {@link MethodHandle} invocation returning a {@code String} with fallback label. */
    static void emitSubframeFastPath(final MethodVisitor mv, final boolean negateNegative, final Label fallback) {
        final Label popAndFallback = new Label();
        emitActiveGate(mv, fallback);
        SlotBridgeEmitter.emitMethodHandleConsult(
                mv, AnimationAccelerationBridge.SUBFRAME_FORMAT_PROPERTY, popAndFallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                AnimationAccelerationTarget.MOVIE_INFO_OWNER,
                "getDisplayTimeSubFrame",
                "(I)I",
                false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.MOVIE_INFO_OWNER, "getFpsInt", "()I", false);
        mv.visitInsn(negateNegative ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT, SUBFRAME_MH_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(popAndFallback);
        mv.visitInsn(Opcodes.POP);
    }

    /** Emits fast path replacing {@code DecimalFormat.format(D)} using thread-local cache. */
    static void emitDecimalFormatCall(final MethodVisitor mv, final int tmpDfLocal, final int tmpDoubleLocal) {
        final Label fallback = new Label();
        final Label popFallback = new Label();
        final Label done = new Label();
        mv.visitVarInsn(Opcodes.DSTORE, tmpDoubleLocal);
        mv.visitVarInsn(Opcodes.ASTORE, tmpDfLocal);
        emitActiveGate(mv, fallback);
        SlotBridgeEmitter.emitMethodHandleConsult(mv, AnimationAccelerationBridge.DECIMAL_FORMAT_PROPERTY, popFallback);
        mv.visitVarInsn(Opcodes.ALOAD, tmpDfLocal);
        mv.visitVarInsn(Opcodes.DLOAD, tmpDoubleLocal);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT, DECIMAL_MH_DESC, false);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(popFallback);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(fallback);
        mv.visitVarInsn(Opcodes.ALOAD, tmpDfLocal);
        mv.visitVarInsn(Opcodes.DLOAD, tmpDoubleLocal);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, DECIMAL_FORMAT_OWNER, DECIMAL_FORMAT_METHOD, DECIMAL_FORMAT_DESC, false);
        mv.visitLabel(done);
    }

    /** Emits fast path for timeline status bar timecode + zero-padded frame formatting. */
    static void emitTimelineStatusTimecodeFastPath(
            final MethodVisitor mv, final UtilsTimecodeShape shape, final Label fallback) {
        final Label popTc = new Label();
        final Label popPad = new Label();
        final Label nullScene = new Label();
        final int locScene = 1;
        final int locMovie = 2;
        final int locFrame = 3;
        final int locSec = 4;
        final int locSub = 5;
        final int locIsNeg = 6;
        final int locTcHandle = 7;
        final int locPadHandle = 8;
        final int locTimeStr = 9;
        final int locFrameStr = 10;

        emitActiveGate(mv, fallback);
        SlotBridgeEmitter.emitMethodHandleConsult(mv, AnimationAccelerationBridge.TIMECODE_PREFIX_PROPERTY, popTc);
        mv.visitVarInsn(Opcodes.ASTORE, locTcHandle);
        SlotBridgeEmitter.emitMethodHandleConsult(mv, AnimationAccelerationBridge.FRAME_PAD_PROPERTY, popPad);
        mv.visitVarInsn(Opcodes.ASTORE, locPadHandle);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                shape.utilsOwner(),
                shape.sceneMethod(),
                "()L" + SCENE_SOURCE_OWNER + ";",
                false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, nullScene);
        mv.visitVarInsn(Opcodes.ASTORE, locScene);

        mv.visitVarInsn(Opcodes.ALOAD, locScene);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                SCENE_SOURCE_OWNER,
                "getMovieInfo",
                "()L" + AnimationAccelerationTarget.MOVIE_INFO_OWNER + ";",
                false);
        mv.visitVarInsn(Opcodes.ASTORE, locMovie);

        mv.visitVarInsn(Opcodes.ALOAD, locScene);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                SCENE_SOURCE_OWNER,
                "getTimelineInfo",
                "()L" + CORE_TIMELINE_INFO_OWNER + ";",
                false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CORE_TIMELINE_INFO_OWNER, shape.timelineFrameMethod(), "()I", false);
        mv.visitVarInsn(Opcodes.ISTORE, locFrame);

        mv.visitVarInsn(Opcodes.ALOAD, locMovie);
        mv.visitVarInsn(Opcodes.ILOAD, locFrame);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                AnimationAccelerationTarget.MOVIE_INFO_OWNER,
                "getDisplayTimeSec",
                "(I)I",
                false);
        mv.visitVarInsn(Opcodes.ISTORE, locSec);

        mv.visitVarInsn(Opcodes.ALOAD, locMovie);
        mv.visitVarInsn(Opcodes.ILOAD, locFrame);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                AnimationAccelerationTarget.MOVIE_INFO_OWNER,
                AnimationAccelerationTarget.getDisplaySubframeMethod(),
                AnimationAccelerationTarget.getDisplaySubframeDescriptor(),
                false);
        mv.visitVarInsn(Opcodes.ASTORE, locSub);

        if (shape.supportsNegative()) {
            final Label notNeg = new Label();
            final Label negReady = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, locFrame);
            mv.visitJumpInsn(Opcodes.IFGE, notNeg);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitJumpInsn(Opcodes.GOTO, negReady);
            mv.visitLabel(notNeg);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitLabel(negReady);
        } else {
            mv.visitInsn(Opcodes.ICONST_0);
        }
        mv.visitVarInsn(Opcodes.ISTORE, locIsNeg);

        mv.visitVarInsn(Opcodes.ALOAD, locTcHandle);
        mv.visitVarInsn(Opcodes.ILOAD, locSec);
        mv.visitVarInsn(Opcodes.ILOAD, locIsNeg);
        mv.visitVarInsn(Opcodes.ALOAD, locSub);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT, TIMECODE_MH_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, locTimeStr);

        mv.visitVarInsn(Opcodes.ALOAD, locPadHandle);
        mv.visitVarInsn(Opcodes.ALOAD, locMovie);
        mv.visitVarInsn(Opcodes.ILOAD, locFrame);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.MOVIE_INFO_OWNER, "getDisplayFrame", "(I)I", false);
        mv.visitVarInsn(Opcodes.ILOAD, locIsNeg);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT, FRAME_PAD_MH_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, locFrameStr);

        mv.visitFieldInsn(Opcodes.GETSTATIC, shape.logOwner(), "a", "L" + shape.logOwner() + ";");
        mv.visitTypeInsn(Opcodes.NEW, shape.lambdaOwner());
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, locTimeStr);
        mv.visitVarInsn(Opcodes.ALOAD, locFrameStr);
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL, shape.lambdaOwner(), "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "kotlin/jvm/functions/Function0");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, shape.logOwner(), "c", "(Lkotlin/jvm/functions/Function0;)V", false);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(nullScene);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(popPad);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, fallback);

        mv.visitLabel(popTc);
        mv.visitInsn(Opcodes.POP);
    }

    /** Emits indexed {@code CArrayList} loop replacing iterator allocation in {@code CMvTrack_Group_Instance}. */
    static void emitGroupIndexedLoop(
            final MethodVisitor mv,
            final AnimationAccelerationTarget target,
            final boolean forFa,
            final int baseLocal,
            final Label exitLabel) {
        final int locList = baseLocal;
        final int locIdx = baseLocal + 1;
        final int locSize = baseLocal + 2;
        final int locChild = baseLocal + 3;
        final Label fallback = new Label();
        final Label loopHead = new Label();
        final Label nextItem = new Label();

        mv.visitVarInsn(Opcodes.ASTORE, locList);
        emitActiveGate(mv, fallback);

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, locIdx);
        mv.visitVarInsn(Opcodes.ALOAD, locList);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.ARRAY_LIST_OWNER, "size", "()I", false);
        mv.visitVarInsn(Opcodes.ISTORE, locSize);

        mv.visitLabel(loopHead);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitVarInsn(Opcodes.ILOAD, locSize);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, exitLabel);

        mv.visitVarInsn(Opcodes.ALOAD, locList);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                AnimationAccelerationTarget.ARRAY_LIST_OWNER,
                "get",
                "(I)Ljava/lang/Object;",
                false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, TRACK_INSTANCE_BASE_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, locChild);

        if (forFa) {
            mv.visitVarInsn(Opcodes.ALOAD, locChild);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.ALOAD, locChild);
            mv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, FUNCTION1_OWNER, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, BOOLEAN_BOX_OWNER);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BOOLEAN_BOX_OWNER, BOOLEAN_VALUE_METHOD, "()Z", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TRACK_INSTANCE_BASE_OWNER, "setEnabled", "(Z)V", false);

            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitVarInsn(Opcodes.ALOAD, locChild);
            mv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, FUNCTION1_OWNER, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, BOOLEAN_BOX_OWNER);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BOOLEAN_BOX_OWNER, BOOLEAN_VALUE_METHOD, "()Z", false);
            mv.visitJumpInsn(Opcodes.IFEQ, nextItem);

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, locChild);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER,
                    "setupTransform__testImpl",
                    "(L" + AnimationAccelerationTarget.FRAME_TIME_OWNER + ";L" + TRACK_INSTANCE_BASE_OWNER + ";L"
                            + target.trackContextOwner() + ";)V",
                    false);

            final Label notGroup = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, locChild);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER);
            mv.visitJumpInsn(Opcodes.IFEQ, notGroup);
            mv.visitVarInsn(Opcodes.ALOAD, locChild);
            mv.visitTypeInsn(Opcodes.CHECKCAST, AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER,
                    AnimationAccelerationTarget.updateTrackInstanceForFaMethod(),
                    target.updateTrackInstanceForFaDescriptor(),
                    false);
            mv.visitJumpInsn(Opcodes.GOTO, nextItem);

            mv.visitLabel(notGroup);
            mv.visitVarInsn(Opcodes.ALOAD, locChild);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    TRACK_INSTANCE_BASE_OWNER,
                    AnimationAccelerationTarget.updateTrackInstanceTestImplMethod(),
                    target.updateTrackInstanceTestImplDescriptor(),
                    false);
        } else {
            final Label doProcess = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, target.trackContextOwner(), "g", "()Z", false);
            mv.visitJumpInsn(Opcodes.IFEQ, doProcess);
            mv.visitVarInsn(Opcodes.ALOAD, locChild);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER);
            mv.visitJumpInsn(Opcodes.IFNE, doProcess);
            mv.visitVarInsn(Opcodes.ALOAD, locChild);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TRACK_INSTANCE_BASE_OWNER, "getEnabled", "()Z", false);
            mv.visitJumpInsn(Opcodes.IFEQ, nextItem);

            mv.visitLabel(doProcess);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, locChild);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER,
                    "setupTransform__testImpl",
                    "(L" + AnimationAccelerationTarget.FRAME_TIME_OWNER + ";L" + TRACK_INSTANCE_BASE_OWNER + ";L"
                            + target.trackContextOwner() + ";)V",
                    false);
            mv.visitVarInsn(Opcodes.ALOAD, locChild);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    TRACK_INSTANCE_BASE_OWNER,
                    AnimationAccelerationTarget.updateTrackInstanceTestImplMethod(),
                    target.updateTrackInstanceTestImplDescriptor(),
                    false);
        }

        mv.visitLabel(nextItem);
        mv.visitIincInsn(locIdx, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopHead);

        mv.visitLabel(fallback);
        mv.visitVarInsn(Opcodes.ALOAD, locList);
    }

    /** Emits binary-searched horizontal viewport culling for {@code GraphEditor.drawCurve}. */
    static void emitGraphCurveCulledFastPath(
            final MethodVisitor mv,
            final AnimationAccelerationTarget target,
            final GraphDrawCurveShape shape,
            final int baseLocal,
            final Label dotExitBridge) {
        final int locN = baseLocal;
        final int locFirst = baseLocal + 1;
        final int locLast = baseLocal + 2;
        final int locSegStart = baseLocal + 3;
        final int locSegEnd = baseLocal + 4;
        final int locLo = baseLocal + 5;
        final int locHi = baseLocal + 6;
        final int locMid = baseLocal + 7;
        final int locPts8 = baseLocal + 8;
        final int locH0 = baseLocal + 9;
        final int locH1 = baseLocal + 10;
        final int locH2 = baseLocal + 11;
        final int locH3 = baseLocal + 12;
        final int locIdx = baseLocal + 13;
        final int locP0 = baseLocal + 14;
        final int locP1 = baseLocal + 15;
        final int locX0 = baseLocal + 16;
        final int locY0 = baseLocal + 17;
        final int locX1 = baseLocal + 18;
        final int locY1 = baseLocal + 19;
        final int locInView = baseLocal + 20;
        final int locC1X = baseLocal + 21;
        final int locC1Y = baseLocal + 22;
        final int locC2X = baseLocal + 23;
        final int locC2Y = baseLocal + 24;
        final int locDx = baseLocal + 25;
        final int locPx = baseLocal + 26;
        final int locPy = baseLocal + 27;
        final int locTextY = baseLocal + 28;
        final int locVal = baseLocal + 29; // double uses +29, +30
        final int locDispVal = baseLocal + 31; // double uses +31, +32
        final int locFmtStr = baseLocal + 33;
        final int locLblStr = baseLocal + 34;
        final int locTx = baseLocal + 35;
        final int locTy = baseLocal + 36;
        final int locSel = baseLocal + 37;
        final int locDfTmp = baseLocal + 38;
        final int locDfValTmp = baseLocal + 39; // double uses +39, +40
        final int locMonotonic = baseLocal + 41;

        final Label finishAndJump = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitVarInsn(Opcodes.ISTORE, locN);

        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INT_RANGE_OWNER, INT_RANGE_GET_FIRST, INT_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ISTORE, locFirst);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INT_RANGE_OWNER, INT_RANGE_GET_LAST, INT_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ISTORE, locLast);

        // Determine monotonic screen X ordering before enabling binary search.
        final Label nonMonotonic = new Label();
        final Label segBoundsReady = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, locFirst);
        mv.visitVarInsn(Opcodes.ILOAD, locLast);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, nonMonotonic);
        mv.visitVarInsn(Opcodes.ILOAD, locN);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, nonMonotonic);
        emitTimelineXOfPoint(mv, 0);
        emitTimelineXOfLastPoint(mv, locN);
        mv.visitInsn(Opcodes.FCMPL);
        mv.visitJumpInsn(Opcodes.IFGT, nonMonotonic);

        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ISTORE, locMonotonic);
        emitBinarySearchSegStart(mv, locN, locFirst, locLo, locHi, locMid, locSegStart);
        emitBinarySearchSegEnd(mv, locN, locLast, locLo, locHi, locMid, locSegEnd);
        mv.visitJumpInsn(Opcodes.GOTO, segBoundsReady);

        mv.visitLabel(nonMonotonic);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, locMonotonic);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, locSegStart);
        mv.visitVarInsn(Opcodes.ILOAD, locN);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, locSegEnd);

        mv.visitLabel(segBoundsReady);
        mv.visitIntInsn(Opcodes.BIPUSH, DRAW_CURVE_ARRAY_LEN);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
        mv.visitVarInsn(Opcodes.ASTORE, locPts8);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, locH0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, locH1);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, locH2);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, locH3);

        final Label segLoop = new Label();
        final Label segDone = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, locSegStart);
        mv.visitVarInsn(Opcodes.ISTORE, locIdx);
        mv.visitLabel(segLoop);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitVarInsn(Opcodes.ILOAD, locSegEnd);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, segDone);

        emitProcessSegment(
                mv, true, locIdx, locPts8, locP0, locP1, locX0, locY0, locX1, locY1, locInView, locC1X, locC1Y, locC2X,
                locC2Y, locDx, locH0, locH1, locH2, locH3);
        mv.visitIincInsn(locIdx, 1);
        mv.visitJumpInsn(Opcodes.GOTO, segLoop);

        mv.visitLabel(segDone);
        // Preserve Bezier control handles when selected keyframe lies offscreen outside [segStart, segEnd].
        final Label skipOffscreenSel = new Label();
        final Label checkK2 = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitJumpInsn(Opcodes.IFEQ, skipOffscreenSel);
        mv.visitVarInsn(Opcodes.ILOAD, 22);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, skipOffscreenSel);

        mv.visitVarInsn(Opcodes.ILOAD, 22);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, locIdx);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitJumpInsn(Opcodes.IFLT, checkK2);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitVarInsn(Opcodes.ILOAD, locN);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, checkK2);
        final Label evalK1 = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitVarInsn(Opcodes.ILOAD, locSegStart);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, evalK1);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitVarInsn(Opcodes.ILOAD, locSegEnd);
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, checkK2);
        mv.visitLabel(evalK1);
        emitProcessSegment(
                mv, false, locIdx, locPts8, locP0, locP1, locX0, locY0, locX1, locY1, locInView, locC1X, locC1Y, locC2X,
                locC2Y, locDx, locH0, locH1, locH2, locH3);

        mv.visitLabel(checkK2);
        mv.visitVarInsn(Opcodes.ILOAD, 22);
        mv.visitVarInsn(Opcodes.ISTORE, locIdx);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitJumpInsn(Opcodes.IFLT, skipOffscreenSel);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitVarInsn(Opcodes.ILOAD, locN);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, skipOffscreenSel);
        final Label evalK2 = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitVarInsn(Opcodes.ILOAD, locSegStart);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, evalK2);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitVarInsn(Opcodes.ILOAD, locSegEnd);
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, skipOffscreenSel);
        mv.visitLabel(evalK2);
        emitProcessSegment(
                mv, false, locIdx, locPts8, locP0, locP1, locX0, locY0, locX1, locY1, locInView, locC1X, locC1Y, locC2X,
                locC2Y, locDx, locH0, locH1, locH2, locH3);
        mv.visitLabel(skipOffscreenSel);

        // Draw accumulated curve path and infinite last-keyframe horizontal tail.
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 12);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "b", "(L" + CCOLOR_OWNER + ";)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SHAPE_OWNER);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "b", "(L" + SHAPE_OWNER + ";)V", false);

        emitLastPointTail(mv, locP0, locIdx, locX0, locY0);

        // Draw Bezier handles and keyframe dots only when editable.
        final Label notEditable = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitJumpInsn(Opcodes.IFEQ, notEditable);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER,
                "color_BezierHandle",
                "L" + CCOLOR_OWNER + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "b", "(L" + CCOLOR_OWNER + ";)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.NEW, BASIC_STROKE_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(CURVE_STROKE_WIDTH);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BASIC_STROKE_OWNER, "<init>", "(F)V", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, STROKE_OWNER);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "a", "(L" + STROKE_OWNER + ";)V", false);

        emitDrawSingleHandle(mv, locH0, locPx, locPy, locTx, locTy);
        emitDrawSingleHandle(mv, locH1, locPx, locPy, locTx, locTy);
        emitDrawSingleHandle(mv, locH2, locPx, locPy, locTx, locTy);
        emitDrawSingleHandle(mv, locH3, locPx, locPy, locTx, locTy);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.NEW, BASIC_STROKE_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BASIC_STROKE_OWNER, "<init>", "()V", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, STROKE_OWNER);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "a", "(L" + STROKE_OWNER + ";)V", false);

        // Initialize selected dot/label slots cleanly before visible keyframe loop.
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 26);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 27);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 28);

        final Label dotsAllRange = new Label();
        final Label dotBoundsReady = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, locMonotonic);
        mv.visitJumpInsn(Opcodes.IFEQ, dotsAllRange);
        emitBinarySearchDotStart(mv, locN, locFirst, locLo, locHi, locMid, locSegStart);
        emitBinarySearchDotEnd(mv, locN, locLast, locLo, locHi, locMid, locSegEnd);
        mv.visitJumpInsn(Opcodes.GOTO, dotBoundsReady);

        mv.visitLabel(dotsAllRange);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, locSegStart);
        mv.visitVarInsn(Opcodes.ILOAD, locN);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, locSegEnd);

        mv.visitLabel(dotBoundsReady);
        final Label dotLoop = new Label();
        final Label dotNext = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, locSegStart);
        mv.visitVarInsn(Opcodes.ISTORE, locIdx);
        mv.visitLabel(dotLoop);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitVarInsn(Opcodes.ILOAD, locSegEnd);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, dotExitBridge);

        emitDrawVisibleDot(
                mv,
                shape,
                locIdx,
                locFirst,
                locLast,
                locP0,
                locPx,
                locPy,
                locTx,
                locTy,
                locSel,
                locVal,
                locTextY,
                locDispVal,
                locFmtStr,
                locLblStr,
                locDfTmp,
                locDfValTmp,
                dotNext);
        mv.visitLabel(dotNext);
        mv.visitIincInsn(locIdx, 1);
        mv.visitJumpInsn(Opcodes.GOTO, dotLoop);

        mv.visitLabel(notEditable);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 26);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 27);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 28);
        mv.visitLabel(finishAndJump);
        mv.visitJumpInsn(Opcodes.GOTO, dotExitBridge);
    }

    private static void emitTimelineXOfPoint(final MethodVisitor mv, final int constIndex) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitIntInsn(Opcodes.BIPUSH, constIndex);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.BEZIER_PT_OWNER, "getPos", "()I", false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, "getTimelineX", "(I)F", false);
    }

    private static void emitTimelineXOfLastPoint(final MethodVisitor mv, final int locN) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, locN);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.BEZIER_PT_OWNER, "getPos", "()I", false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, "getTimelineX", "(I)F", false);
    }

    private static void emitBinarySearchSegStart(
            final MethodVisitor mv,
            final int locN,
            final int locFirst,
            final int locLo,
            final int locHi,
            final int locMid,
            final int locOut) {
        final Label loop = new Label();
        final Label elseBr = new Label();
        final Label done = new Label();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, locLo);
        mv.visitVarInsn(Opcodes.ILOAD, locN);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, locHi);

        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, locLo);
        mv.visitVarInsn(Opcodes.ILOAD, locHi);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, done);
        mv.visitVarInsn(Opcodes.ILOAD, locLo);
        mv.visitVarInsn(Opcodes.ILOAD, locHi);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IUSHR);
        mv.visitVarInsn(Opcodes.ISTORE, locMid);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, locMid);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.BEZIER_PT_OWNER, "getPos", "()I", false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, "getTimelineX", "(I)F", false);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.ILOAD, locFirst);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, elseBr);
        mv.visitVarInsn(Opcodes.ILOAD, locMid);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, locLo);
        mv.visitJumpInsn(Opcodes.GOTO, loop);

        mv.visitLabel(elseBr);
        mv.visitVarInsn(Opcodes.ILOAD, locMid);
        mv.visitVarInsn(Opcodes.ISTORE, locHi);
        mv.visitJumpInsn(Opcodes.GOTO, loop);

        mv.visitLabel(done);
        mv.visitVarInsn(Opcodes.ILOAD, locLo);
        mv.visitVarInsn(Opcodes.ISTORE, locOut);
    }

    private static void emitBinarySearchSegEnd(
            final MethodVisitor mv,
            final int locN,
            final int locLast,
            final int locLo,
            final int locHi,
            final int locMid,
            final int locOut) {
        final Label loop = new Label();
        final Label elseBr = new Label();
        final Label done = new Label();
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitVarInsn(Opcodes.ISTORE, locLo);
        mv.visitVarInsn(Opcodes.ILOAD, locN);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, locHi);

        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, locLo);
        mv.visitVarInsn(Opcodes.ILOAD, locHi);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, done);
        mv.visitVarInsn(Opcodes.ILOAD, locLo);
        mv.visitVarInsn(Opcodes.ILOAD, locHi);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IUSHR);
        mv.visitVarInsn(Opcodes.ISTORE, locMid);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, locMid);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.BEZIER_PT_OWNER, "getPos", "()I", false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, "getTimelineX", "(I)F", false);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.ILOAD, locLast);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, elseBr);
        mv.visitVarInsn(Opcodes.ILOAD, locMid);
        mv.visitVarInsn(Opcodes.ISTORE, locLo);
        mv.visitJumpInsn(Opcodes.GOTO, loop);

        mv.visitLabel(elseBr);
        mv.visitVarInsn(Opcodes.ILOAD, locMid);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, locHi);
        mv.visitJumpInsn(Opcodes.GOTO, loop);

        mv.visitLabel(done);
        mv.visitVarInsn(Opcodes.ILOAD, locLo);
        mv.visitVarInsn(Opcodes.ISTORE, locOut);
    }

    private static void emitBinarySearchDotStart(
            final MethodVisitor mv,
            final int locN,
            final int locFirst,
            final int locLo,
            final int locHi,
            final int locMid,
            final int locOut) {
        final Label loop = new Label();
        final Label elseBr = new Label();
        final Label done = new Label();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, locLo);
        mv.visitVarInsn(Opcodes.ILOAD, locN);
        mv.visitVarInsn(Opcodes.ISTORE, locHi);

        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, locLo);
        mv.visitVarInsn(Opcodes.ILOAD, locHi);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, done);
        mv.visitVarInsn(Opcodes.ILOAD, locLo);
        mv.visitVarInsn(Opcodes.ILOAD, locHi);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IUSHR);
        mv.visitVarInsn(Opcodes.ISTORE, locMid);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, locMid);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.BEZIER_PT_OWNER, "getPos", "()I", false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, "getTimelineX", "(I)F", false);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.ILOAD, locFirst);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, elseBr);
        mv.visitVarInsn(Opcodes.ILOAD, locMid);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, locLo);
        mv.visitJumpInsn(Opcodes.GOTO, loop);

        mv.visitLabel(elseBr);
        mv.visitVarInsn(Opcodes.ILOAD, locMid);
        mv.visitVarInsn(Opcodes.ISTORE, locHi);
        mv.visitJumpInsn(Opcodes.GOTO, loop);

        mv.visitLabel(done);
        mv.visitVarInsn(Opcodes.ILOAD, locLo);
        mv.visitVarInsn(Opcodes.ISTORE, locOut);
    }

    private static void emitBinarySearchDotEnd(
            final MethodVisitor mv,
            final int locN,
            final int locLast,
            final int locLo,
            final int locHi,
            final int locMid,
            final int locOut) {
        final Label loop = new Label();
        final Label elseBr = new Label();
        final Label done = new Label();
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitVarInsn(Opcodes.ISTORE, locLo);
        mv.visitVarInsn(Opcodes.ILOAD, locN);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, locHi);

        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, locLo);
        mv.visitVarInsn(Opcodes.ILOAD, locHi);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, done);
        mv.visitVarInsn(Opcodes.ILOAD, locLo);
        mv.visitVarInsn(Opcodes.ILOAD, locHi);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IUSHR);
        mv.visitVarInsn(Opcodes.ISTORE, locMid);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, locMid);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.BEZIER_PT_OWNER, "getPos", "()I", false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, "getTimelineX", "(I)F", false);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.ILOAD, locLast);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, elseBr);
        mv.visitVarInsn(Opcodes.ILOAD, locMid);
        mv.visitVarInsn(Opcodes.ISTORE, locLo);
        mv.visitJumpInsn(Opcodes.GOTO, loop);

        mv.visitLabel(elseBr);
        mv.visitVarInsn(Opcodes.ILOAD, locMid);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, locHi);
        mv.visitJumpInsn(Opcodes.GOTO, loop);

        mv.visitLabel(done);
        mv.visitVarInsn(Opcodes.ILOAD, locLo);
        mv.visitVarInsn(Opcodes.ISTORE, locOut);
    }

    private static void emitProcessSegment(
            final MethodVisitor mv,
            final boolean drawPath,
            final int locIdx,
            final int locPts8,
            final int locP0,
            final int locP1,
            final int locX0,
            final int locY0,
            final int locX1,
            final int locY1,
            final int locInView,
            final int locC1X,
            final int locC1Y,
            final int locC2X,
            final int locC2Y,
            final int locDx,
            final int locH0,
            final int locH1,
            final int locH2,
            final int locH3) {
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitVarInsn(Opcodes.ASTORE, locP0);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitVarInsn(Opcodes.ASTORE, locP1);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, locPts8);
        mv.visitVarInsn(Opcodes.ALOAD, locP0);
        mv.visitVarInsn(Opcodes.ALOAD, locP1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER,
                "calcBezierController",
                "([FL" + AnimationAccelerationTarget.BEZIER_PT_OWNER + ";L"
                        + AnimationAccelerationTarget.BEZIER_PT_OWNER + ";)V",
                false);

        loadFloatAt(mv, locPts8, 0, locX0);
        loadFloatAt(mv, locPts8, 1, locY0);
        loadFloatAt(mv, locPts8, 6, locX1);
        loadFloatAt(mv, locPts8, 7, locY1);

        final Label segDone = new Label();
        if (drawPath) {
            final Label checkStraddle = new Label();
            final Label inViewTrue = new Label();
            final Label inViewReady = new Label();
            final Label afterMoveTo = new Label();

            mv.visitVarInsn(Opcodes.ALOAD, 7);
            mv.visitVarInsn(Opcodes.FLOAD, locX0);
            mv.visitInsn(Opcodes.F2I);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INT_RANGE_OWNER, INT_RANGE_CONTAINS, "(I)Z", false);
            mv.visitJumpInsn(Opcodes.IFNE, inViewTrue);
            mv.visitVarInsn(Opcodes.ALOAD, 7);
            mv.visitVarInsn(Opcodes.FLOAD, locX1);
            mv.visitInsn(Opcodes.F2I);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INT_RANGE_OWNER, INT_RANGE_CONTAINS, "(I)Z", false);
            mv.visitJumpInsn(Opcodes.IFNE, inViewTrue);

            mv.visitVarInsn(Opcodes.FLOAD, locX0);
            mv.visitInsn(Opcodes.F2I);
            mv.visitVarInsn(Opcodes.ALOAD, 7);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, INT_RANGE_OWNER, INT_RANGE_GET_START, "()Ljava/lang/Integer;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INTEGER_OWNER, INT_VALUE_METHOD, "()I", false);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, checkStraddle);
            mv.visitVarInsn(Opcodes.FLOAD, locX1);
            mv.visitInsn(Opcodes.F2I);
            mv.visitVarInsn(Opcodes.ALOAD, 7);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INT_RANGE_OWNER, INT_RANGE_GET_LAST, "()I", false);
            mv.visitJumpInsn(Opcodes.IF_ICMPGT, inViewTrue);

            mv.visitLabel(checkStraddle);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitJumpInsn(Opcodes.GOTO, inViewReady);
            mv.visitLabel(inViewTrue);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitLabel(inViewReady);
            mv.visitVarInsn(Opcodes.ISTORE, locInView);

            mv.visitVarInsn(Opcodes.ILOAD, locInView);
            mv.visitJumpInsn(Opcodes.IFEQ, afterMoveTo);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.FLOAD, locX0);
            mv.visitVarInsn(Opcodes.FLOAD, locY0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PATH2D_FLOAT_OWNER, "moveTo", "(FF)V", false);
            mv.visitLabel(afterMoveTo);
        } else {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, locInView);
        }

        // Switch on curveType via GraphEditor$m.a[curveType.ordinal()].
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                AnimationAccelerationTarget.MUTABLE_SEQUENCE_OWNER,
                "getCurveTypes",
                "()L" + AnimationAccelerationTarget.ARRAY_LIST_OWNER + ";",
                false);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                AnimationAccelerationTarget.ARRAY_LIST_OWNER,
                CURVE_TYPE_ARRAY_LIST_METHOD,
                "(I)Ljava/lang/Object;",
                false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, CURVE_TYPE_OWNER);
        mv.visitFieldInsn(Opcodes.GETSTATIC, AnimationAccelerationTarget.GRAPH_SWITCH_MAP_OWNER, "a", "[I");
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CURVE_TYPE_OWNER, "ordinal", "()I", false);
        mv.visitInsn(Opcodes.IALOAD);

        final Label caseBezier = new Label();
        final Label caseSmooth = new Label();
        final Label caseLinear = new Label();
        final Label caseStep = new Label();
        final Label caseInverseStep = new Label();
        mv.visitTableSwitchInsn(1, 5, segDone, caseBezier, caseSmooth, caseLinear, caseStep, caseInverseStep);

        // Case 1: BEZIER (path curve + optional selected handles)
        mv.visitLabel(caseBezier);
        loadFloatAt(mv, locPts8, 2, locC1X);
        loadFloatAt(mv, locPts8, 3, locC1Y);
        loadFloatAt(mv, locPts8, 4, locC2X);
        loadFloatAt(mv, locPts8, 5, locC2Y);
        if (drawPath) {
            final Label skipCurveTo = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, locInView);
            mv.visitJumpInsn(Opcodes.IFEQ, skipCurveTo);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.FLOAD, locC1X);
            mv.visitVarInsn(Opcodes.FLOAD, locC1Y);
            mv.visitVarInsn(Opcodes.FLOAD, locC2X);
            mv.visitVarInsn(Opcodes.FLOAD, locC2Y);
            mv.visitVarInsn(Opcodes.FLOAD, locX1);
            mv.visitVarInsn(Opcodes.FLOAD, locY1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PATH2D_FLOAT_OWNER, "curveTo", "(FFFFFF)V", false);
            mv.visitLabel(skipCurveTo);
        }
        // Record Bezier handles when editable and segment touches selected index (d == -1 or d == 0).
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitJumpInsn(Opcodes.IFEQ, segDone);
        mv.visitVarInsn(Opcodes.ILOAD, 22);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, segDone);
        final Label tryZero = new Label();
        final Label storePair = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitVarInsn(Opcodes.ILOAD, 22);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, tryZero);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.FLOAD, locX1);
        mv.visitVarInsn(Opcodes.FLOAD, locX0);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FSTORE, locDx);
        emitNewGraphHandle(mv, false, locX0, locY0, locC1X, locC1Y, locDx);
        mv.visitVarInsn(Opcodes.ASTORE, locH0);
        emitNewGraphHandle(mv, true, locX1, locY1, locC2X, locC2Y, locDx);
        mv.visitVarInsn(Opcodes.ASTORE, locH1);
        mv.visitJumpInsn(Opcodes.GOTO, segDone);

        mv.visitLabel(tryZero);
        mv.visitJumpInsn(Opcodes.IFNE, segDone);
        mv.visitLabel(storePair);
        mv.visitVarInsn(Opcodes.FLOAD, locX1);
        mv.visitVarInsn(Opcodes.FLOAD, locX0);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FSTORE, locDx);
        emitNewGraphHandle(mv, false, locX0, locY0, locC1X, locC1Y, locDx);
        mv.visitVarInsn(Opcodes.ASTORE, locH2);
        emitNewGraphHandle(mv, true, locX1, locY1, locC2X, locC2Y, locDx);
        mv.visitVarInsn(Opcodes.ASTORE, locH3);
        mv.visitJumpInsn(Opcodes.GOTO, segDone);

        // Case 2: SMOOTH
        mv.visitLabel(caseSmooth);
        if (drawPath) {
            loadFloatAt(mv, locPts8, 2, locC1X);
            loadFloatAt(mv, locPts8, 3, locC1Y);
            loadFloatAt(mv, locPts8, 4, locC2X);
            loadFloatAt(mv, locPts8, 5, locC2Y);
            mv.visitVarInsn(Opcodes.ILOAD, locInView);
            mv.visitJumpInsn(Opcodes.IFEQ, segDone);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.FLOAD, locC1X);
            mv.visitVarInsn(Opcodes.FLOAD, locC1Y);
            mv.visitVarInsn(Opcodes.FLOAD, locC2X);
            mv.visitVarInsn(Opcodes.FLOAD, locC2Y);
            mv.visitVarInsn(Opcodes.FLOAD, locX1);
            mv.visitVarInsn(Opcodes.FLOAD, locY1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PATH2D_FLOAT_OWNER, "curveTo", "(FFFFFF)V", false);
        }
        mv.visitJumpInsn(Opcodes.GOTO, segDone);

        // Case 3: LINEAR
        mv.visitLabel(caseLinear);
        if (drawPath) {
            mv.visitVarInsn(Opcodes.ILOAD, locInView);
            mv.visitJumpInsn(Opcodes.IFEQ, segDone);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.FLOAD, locX1);
            mv.visitVarInsn(Opcodes.FLOAD, locY1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PATH2D_FLOAT_OWNER, "lineTo", "(FF)V", false);
        }
        mv.visitJumpInsn(Opcodes.GOTO, segDone);

        // Case 4: STEP
        mv.visitLabel(caseStep);
        if (drawPath) {
            mv.visitVarInsn(Opcodes.ILOAD, locInView);
            mv.visitJumpInsn(Opcodes.IFEQ, segDone);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.FLOAD, locX1);
            mv.visitVarInsn(Opcodes.FLOAD, locY0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PATH2D_FLOAT_OWNER, "lineTo", "(FF)V", false);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.FLOAD, locX1);
            mv.visitVarInsn(Opcodes.FLOAD, locY1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PATH2D_FLOAT_OWNER, "lineTo", "(FF)V", false);
        }
        mv.visitJumpInsn(Opcodes.GOTO, segDone);

        // Case 5: INVERSE_STEP
        mv.visitLabel(caseInverseStep);
        if (drawPath) {
            mv.visitVarInsn(Opcodes.ILOAD, locInView);
            mv.visitJumpInsn(Opcodes.IFEQ, segDone);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.FLOAD, locX0);
            mv.visitVarInsn(Opcodes.FLOAD, locY1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PATH2D_FLOAT_OWNER, "lineTo", "(FF)V", false);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.FLOAD, locX1);
            mv.visitVarInsn(Opcodes.FLOAD, locY1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PATH2D_FLOAT_OWNER, "lineTo", "(FF)V", false);
        }

        mv.visitLabel(segDone);
    }

    private static void loadFloatAt(final MethodVisitor mv, final int arrLocal, final int index, final int outLocal) {
        mv.visitVarInsn(Opcodes.ALOAD, arrLocal);
        mv.visitIntInsn(Opcodes.BIPUSH, index);
        mv.visitInsn(Opcodes.FALOAD);
        mv.visitVarInsn(Opcodes.FSTORE, outLocal);
    }

    private static void emitNewGraphHandle(
            final MethodVisitor mv,
            final boolean isEnd,
            final int locX,
            final int locY,
            final int locCX,
            final int locCY,
            final int locDx) {
        mv.visitTypeInsn(Opcodes.NEW, AnimationAccelerationTarget.GRAPH_HANDLE_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(isEnd ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.FLOAD, locX);
        mv.visitVarInsn(Opcodes.FLOAD, locY);
        mv.visitVarInsn(Opcodes.FLOAD, locCX);
        mv.visitVarInsn(Opcodes.FLOAD, locCY);
        mv.visitVarInsn(Opcodes.FLOAD, locDx);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, FLOAT_BOX_OWNER, FLOAT_VALUE_OF_METHOD, FLOAT_VALUE_OF_DESC, false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitIntInsn(Opcodes.BIPUSH, DEFAULT_HANDLE_BITMASK);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                AnimationAccelerationTarget.GRAPH_HANDLE_OWNER,
                "<init>",
                "(ZFFFFLjava/lang/Float;Ljava/lang/Float;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
                false);
    }

    private static void emitLastPointTail(
            final MethodVisitor mv, final int locLastPt, final int locWidth, final int locX, final int locY) {
        final Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARRAYS_KT_OWNER, LAST_OR_NULL_METHOD, LAST_OR_NULL_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, AnimationAccelerationTarget.BEZIER_PT_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, done);
        mv.visitVarInsn(Opcodes.ASTORE, locLastPt);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(
                Opcodes.GETFIELD,
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER,
                "paintComp",
                "L" + CUSTOM_PAINT_COMP_OWNER + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CUSTOM_PAINT_COMP_OWNER, "getWidth", "()I", false);
        mv.visitVarInsn(Opcodes.ISTORE, locWidth);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, locLastPt);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.BEZIER_PT_OWNER, "getPos", "()I", false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, "getTimelineX", "(I)F", false);
        mv.visitVarInsn(Opcodes.FSTORE, locX);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, locLastPt);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                AnimationAccelerationTarget.BEZIER_PT_OWNER,
                "getAnchor$cubism",
                "()L" + AnimationAccelerationTarget.SEQ_PT_OWNER + ";",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.SEQ_PT_OWNER, "getDoubleValue", "()D", false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, "getTimelineY", "(D)F", false);
        mv.visitVarInsn(Opcodes.FSTORE, locY);

        // Preserve exact original host check: y < width draws tail across remaining graph width.
        mv.visitVarInsn(Opcodes.FLOAD, locY);
        mv.visitVarInsn(Opcodes.ILOAD, locWidth);
        mv.visitInsn(Opcodes.I2F);
        mv.visitInsn(Opcodes.FCMPL);
        final Label skipDraw = new Label();
        mv.visitJumpInsn(Opcodes.IFGE, skipDraw);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.FLOAD, locX);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.FLOAD, locY);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.ILOAD, locWidth);
        mv.visitVarInsn(Opcodes.FLOAD, locY);
        mv.visitInsn(Opcodes.F2I);
        mv.visitLdcInsn(CURVE_STROKE_WIDTH);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "a", "(IIIIF)V", false);
        mv.visitJumpInsn(Opcodes.GOTO, skipDraw);

        mv.visitLabel(done);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(skipDraw);
    }

    private static void emitDrawSingleHandle(
            final MethodVisitor mv,
            final int locHandle,
            final int locAx,
            final int locAy,
            final int locHx,
            final int locHy) {
        final Label skip = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, locHandle);
        mv.visitJumpInsn(Opcodes.IFNULL, skip);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, locHandle);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER,
                "overlappingPointsGuard",
                "(L" + AnimationAccelerationTarget.GRAPH_HANDLE_OWNER + ";)L"
                        + AnimationAccelerationTarget.GRAPH_HANDLE_OWNER + ";",
                false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_HANDLE_OWNER, "b", "()F", false);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.ISTORE, locAx);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_HANDLE_OWNER, "c", "()F", false);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.ISTORE, locAy);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_HANDLE_OWNER, "d", "()F", false);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.ISTORE, locHx);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_HANDLE_OWNER, "e", "()F", false);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.ISTORE, locHy);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, locAx);
        mv.visitVarInsn(Opcodes.ILOAD, locAy);
        mv.visitVarInsn(Opcodes.ILOAD, locHx);
        mv.visitVarInsn(Opcodes.ILOAD, locHy);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "a", "(IIII)V", false);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, locHx);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, locHy);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitIntInsn(Opcodes.BIPUSH, HANDLE_BOX_SIZE);
        mv.visitIntInsn(Opcodes.BIPUSH, HANDLE_BOX_SIZE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "f", "(IIII)V", false);
        mv.visitLabel(skip);
    }

    private static void emitDrawVisibleDot(
            final MethodVisitor mv,
            final GraphDrawCurveShape shape,
            final int locIdx,
            final int locFirst,
            final int locLast,
            final int locPt,
            final int locPx,
            final int locPy,
            final int locTx,
            final int locTy,
            final int locSel,
            final int locVal,
            final int locTextY,
            final int locDispVal,
            final int locFmtStr,
            final int locLblStr,
            final int locDfTmp,
            final int locDfValTmp,
            final Label dotNext) {
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitVarInsn(Opcodes.ASTORE, locPt);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, locPt);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.BEZIER_PT_OWNER, "getPos", "()I", false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, "getTimelineX", "(I)F", false);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.ISTORE, locPx);

        mv.visitVarInsn(Opcodes.ILOAD, locPx);
        mv.visitVarInsn(Opcodes.ILOAD, locFirst);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, dotNext);
        mv.visitVarInsn(Opcodes.ILOAD, locPx);
        mv.visitVarInsn(Opcodes.ILOAD, locLast);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, dotNext);

        mv.visitVarInsn(Opcodes.ALOAD, locPt);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                AnimationAccelerationTarget.BEZIER_PT_OWNER,
                "getAnchor$cubism",
                "()L" + AnimationAccelerationTarget.SEQ_PT_OWNER + ";",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.SEQ_PT_OWNER, "getDoubleValue", "()D", false);
        mv.visitVarInsn(Opcodes.DSTORE, locVal);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.DLOAD, locVal);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, "getTimelineY", "(D)F", false);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.ISTORE, locPy);

        final Label notSel = new Label();
        final Label selReady = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 22);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, notSel);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, selReady);
        mv.visitLabel(notSel);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(selReady);
        mv.visitVarInsn(Opcodes.ISTORE, locSel);

        final Label dotUnselected = new Label();
        final Label afterDot = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, locSel);
        mv.visitJumpInsn(Opcodes.IFEQ, dotUnselected);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER,
                "color_selectedDot",
                "L" + CCOLOR_OWNER + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "b", "(L" + CCOLOR_OWNER + ";)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, locPx);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, locPy);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitIntInsn(Opcodes.BIPUSH, HANDLE_BOX_SIZE);
        mv.visitIntInsn(Opcodes.BIPUSH, HANDLE_BOX_SIZE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "c", "(IIII)V", false);

        mv.visitTypeInsn(Opcodes.NEW, AWT_POINT_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ILOAD, locPx);
        mv.visitVarInsn(Opcodes.ILOAD, locPy);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, AWT_POINT_OWNER, "<init>", "(II)V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 28);
        mv.visitJumpInsn(Opcodes.GOTO, afterDot);

        mv.visitLabel(dotUnselected);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 19);
        mv.visitVarInsn(Opcodes.ALOAD, 13);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 18);
        mv.visitVarInsn(Opcodes.ILOAD, locPx);
        mv.visitVarInsn(Opcodes.ILOAD, locPy);
        mv.visitVarInsn(Opcodes.ALOAD, locPt);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.BEZIER_PT_OWNER, "getPos", "()I", false);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER,
                "drawCurve$getPointColor",
                shape.getPointColorDescriptor(),
                false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "b", "(L" + CCOLOR_OWNER + ";)V", false);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, locPx);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, locPy);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitIntInsn(Opcodes.BIPUSH, HANDLE_BOX_SIZE);
        mv.visitIntInsn(Opcodes.BIPUSH, HANDLE_BOX_SIZE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "f", "(IIII)V", false);

        mv.visitLabel(afterDot);
        final Label drawText = new Label();
        mv.visitFieldInsn(
                Opcodes.GETSTATIC, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, "enableShowKeyframesValue", "Z");
        mv.visitJumpInsn(Opcodes.IFNE, drawText);
        mv.visitFieldInsn(
                Opcodes.GETSTATIC, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, "enableDisplayAttributeName", "Z");
        mv.visitJumpInsn(Opcodes.IFEQ, dotNext);

        mv.visitLabel(drawText);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 14);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 18);
        mv.visitVarInsn(Opcodes.ILOAD, locPx);
        mv.visitVarInsn(Opcodes.ILOAD, locPy);
        mv.visitVarInsn(Opcodes.ALOAD, locPt);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AnimationAccelerationTarget.BEZIER_PT_OWNER, "getPos", "()I", false);
        mv.visitVarInsn(Opcodes.ILOAD, locSel);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER,
                "drawCurve$getPointTextColor",
                shape.getPointTextColorDescriptor(),
                false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "b", "(L" + CCOLOR_OWNER + ";)V", false);

        final Label aboveText = new Label();
        final Label textYReady = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, locIdx);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER,
                "drawCurve$getValueTextVerticalPosition",
                "(L" + AnimationAccelerationTarget.MUTABLE_SEQUENCE_OWNER + ";I)I",
                false);
        mv.visitJumpInsn(Opcodes.IFGE, aboveText);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, "j", "()L" + CFONT_OWNER + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CFONT_OWNER, "getSize", "()F", false);
        mv.visitLdcInsn(TEXT_Y_OFFSET_POS);
        mv.visitInsn(Opcodes.FADD);
        mv.visitJumpInsn(Opcodes.GOTO, textYReady);
        mv.visitLabel(aboveText);
        mv.visitLdcInsn(TEXT_Y_OFFSET_NEG);
        mv.visitLabel(textYReady);
        mv.visitVarInsn(Opcodes.FSTORE, locTextY);

        mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER,
                "Companion",
                "L" + AnimationAccelerationTarget.GRAPH_EDITOR_OWNER + "$g;");
        mv.visitVarInsn(Opcodes.ALOAD, 17);
        mv.visitVarInsn(Opcodes.DLOAD, locVal);
        mv.visitVarInsn(Opcodes.FLOAD, 10);
        mv.visitInsn(Opcodes.F2D);
        mv.visitInsn(Opcodes.DMUL);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER + "$g",
                "a",
                "(L" + AnimationAccelerationTarget.GRAPH_EDITOR_OWNER + "$g;L"
                        + AnimationAccelerationTarget.MOVIE_INFO_OWNER + ";D)D",
                false);
        mv.visitVarInsn(Opcodes.DSTORE, locDispVal);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(
                Opcodes.GETFIELD,
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER,
                "df",
                "L" + DECIMAL_FORMAT_OWNER + ";");
        mv.visitVarInsn(Opcodes.DLOAD, locDispVal);
        emitDecimalFormatCall(mv, locDfTmp, locDfValTmp);
        mv.visitVarInsn(Opcodes.ASTORE, locFmtStr);

        mv.visitLdcInsn(EMPTY_STRING);
        mv.visitVarInsn(Opcodes.ASTORE, locLblStr);
        final Label skipAttrName = new Label();
        mv.visitFieldInsn(
                Opcodes.GETSTATIC, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, FIELD_SHOW_ATTR_NAME, BOOL_DESC);
        mv.visitJumpInsn(Opcodes.IFEQ, skipAttrName);
        mv.visitVarInsn(Opcodes.ALOAD, locLblStr);
        mv.visitVarInsn(Opcodes.ALOAD, 15);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_OWNER, METHOD_CONCAT, STRING_STRING_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, locLblStr);
        mv.visitLabel(skipAttrName);

        final Label skipKeyVal = new Label();
        final Label skipSpace = new Label();
        mv.visitFieldInsn(
                Opcodes.GETSTATIC, AnimationAccelerationTarget.GRAPH_EDITOR_OWNER, FIELD_SHOW_KF_VALUE, BOOL_DESC);
        mv.visitJumpInsn(Opcodes.IFEQ, skipKeyVal);
        mv.visitVarInsn(Opcodes.ALOAD, locLblStr);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_OWNER, METHOD_IS_EMPTY, BOOL_VOID_DESC, false);
        mv.visitJumpInsn(Opcodes.IFNE, skipSpace);
        mv.visitVarInsn(Opcodes.ALOAD, locLblStr);
        mv.visitLdcInsn(SINGLE_SPACE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_OWNER, METHOD_CONCAT, STRING_STRING_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, locLblStr);
        mv.visitLabel(skipSpace);
        mv.visitVarInsn(Opcodes.ALOAD, locLblStr);
        mv.visitVarInsn(Opcodes.ALOAD, locFmtStr);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_OWNER, METHOD_CONCAT, STRING_STRING_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, locLblStr);
        mv.visitLabel(skipKeyVal);

        mv.visitVarInsn(Opcodes.ILOAD, locPx);
        mv.visitIntInsn(Opcodes.BIPUSH, LABEL_OFFSET_X);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, locTx);
        mv.visitVarInsn(Opcodes.ILOAD, locPy);
        mv.visitInsn(Opcodes.I2F);
        mv.visitVarInsn(Opcodes.FLOAD, locTextY);
        mv.visitInsn(Opcodes.FADD);
        mv.visitInsn(Opcodes.F2I);
        mv.visitVarInsn(Opcodes.ISTORE, locTy);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, locLblStr);
        mv.visitVarInsn(Opcodes.ILOAD, locTx);
        mv.visitInsn(Opcodes.I2F);
        mv.visitVarInsn(Opcodes.ILOAD, locTy);
        mv.visitInsn(Opcodes.I2F);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_WRAPPER_OWNER, DRAW_TEXT_METHOD, VOID_STRING_FF_DESC, false);

        mv.visitVarInsn(Opcodes.ILOAD, locSel);
        mv.visitJumpInsn(Opcodes.IFEQ, dotNext);
        mv.visitVarInsn(Opcodes.ALOAD, locLblStr);
        mv.visitVarInsn(Opcodes.ASTORE, 27);
        mv.visitTypeInsn(Opcodes.NEW, AWT_POINT_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ILOAD, locTx);
        mv.visitVarInsn(Opcodes.ILOAD, locTy);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, AWT_POINT_OWNER, CONSTRUCTOR_NAME, VOID_II_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 26);
    }

    /**
     * Replaces {@code "-0:" + sub} string concatenation inside {@code HeaderRowEditPanel.paint_exe}.
     *
     * @param mv downstream method visitor
     * @param strLocal temp slot holding the sub-frame string
     * @param origFallback runnable emitting original invokedynamic instruction on fallback
     */
    static void emitRulerNegZeroConcat(final MethodVisitor mv, final int strLocal, final Runnable origFallback) {
        final Label fallback = new Label();
        final Label popFallback = new Label();
        final Label done = new Label();
        mv.visitVarInsn(Opcodes.ASTORE, strLocal);
        emitActiveGate(mv, fallback);
        SlotBridgeEmitter.emitMethodHandleConsult(mv, AnimationAccelerationBridge.RULER_TIMECODE_PROPERTY, popFallback);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ALOAD, strLocal);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT, RULER_TIMECODE_MH_DESC, false);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(popFallback);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(fallback);
        mv.visitVarInsn(Opcodes.ALOAD, strLocal);
        origFallback.run();
        mv.visitLabel(done);
    }

    /**
     * Replaces {@code sec + ":" + sub} string concatenation inside {@code HeaderRowEditPanel.paint_exe}.
     *
     * @param mv downstream method visitor
     * @param secLocal temp slot holding display seconds
     * @param strLocal temp slot holding sub-frame string
     * @param origFallback runnable emitting original invokedynamic instruction on fallback
     */
    static void emitRulerSecSubConcat(
            final MethodVisitor mv, final int secLocal, final int strLocal, final Runnable origFallback) {
        final Label fallback = new Label();
        final Label popFallback = new Label();
        final Label done = new Label();
        mv.visitVarInsn(Opcodes.ASTORE, strLocal);
        mv.visitVarInsn(Opcodes.ISTORE, secLocal);
        emitActiveGate(mv, fallback);
        SlotBridgeEmitter.emitMethodHandleConsult(mv, AnimationAccelerationBridge.RULER_TIMECODE_PROPERTY, popFallback);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ILOAD, secLocal);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, strLocal);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT, RULER_TIMECODE_MH_DESC, false);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(popFallback);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(fallback);
        mv.visitVarInsn(Opcodes.ILOAD, secLocal);
        mv.visitVarInsn(Opcodes.ALOAD, strLocal);
        origFallback.run();
        mv.visitLabel(done);
    }

    /**
     * Replaces integer display-frame stringification inside {@code HeaderRowEditPanel.paint_exe}.
     *
     * @param mv downstream method visitor
     * @param frameLocal temp slot holding display frame number
     * @param origFallback runnable emitting original invokedynamic instruction on fallback
     */
    static void emitRulerFrameConcat(final MethodVisitor mv, final int frameLocal, final Runnable origFallback) {
        final Label fallback = new Label();
        final Label popFallback = new Label();
        final Label done = new Label();
        mv.visitVarInsn(Opcodes.ISTORE, frameLocal);
        emitActiveGate(mv, fallback);
        SlotBridgeEmitter.emitMethodHandleConsult(mv, AnimationAccelerationBridge.RULER_FRAME_PROPERTY, popFallback);
        mv.visitVarInsn(Opcodes.ILOAD, frameLocal);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT, RULER_FRAME_MH_DESC, false);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(popFallback);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(fallback);
        mv.visitVarInsn(Opcodes.ILOAD, frameLocal);
        origFallback.run();
        mv.visitLabel(done);
    }

    /** Metadata describing {@code Utils.{s,v}} frame-time formatter resolved from bytecode. */
    record UtilsTimecodeShape(
            String utilsOwner,
            String methodName,
            String sceneMethod,
            String timelineFrameMethod,
            boolean supportsNegative,
            String logOwner,
            String lambdaOwner) {}

    /** Metadata describing {@code GraphEditor.drawCurve} helper descriptors resolved from bytecode. */
    record GraphDrawCurveShape(String getPointColorDescriptor, String getPointTextColorDescriptor) {}
}
