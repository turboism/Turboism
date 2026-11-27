package dev.turboism.adapter.cubism.optimization.physics;

import dev.turboism.adapter.cubism.optimization.SlotBridgeEmitter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits allocation-free ASM method fast paths for Cubism pendulum chain simulation.
 *
 * <p>Every arithmetic sequence preserves exact IEEE-754 float order and casts from the reviewed
 * Cubism evaluator, and guards cached {@code GVector2} reuse with pointer identity checks so
 * aliased reset or manually constructed chains behave identically to original allocations.</p>
 */
final class PhysicsMethodEmitters {

    private static final String INTRINSICS_OWNER = "kotlin/jvm/internal/Intrinsics";
    private static final String CHECK_NOT_NULL_PARAM = "checkNotNullParameter";
    private static final String CHECK_NOT_NULL_DESC = "(Ljava/lang/Object;Ljava/lang/String;)V";
    private static final String EMPTY_STRING = "";
    private static final String MATH_OWNER = "java/lang/Math";
    private static final String FLOAT_OWNER = "java/lang/Float";
    private static final String MAP_OWNER = "java/util/Map";
    private static final String ARRAY_LIST_OWNER_JDK = "java/util/ArrayList";
    private static final String FUNCTION1_OWNER = "kotlin/jvm/functions/Function1";

    private static final String SINGLETON_FIELD = "a";
    private static final String GRAVITY_DEFAULT_FIELD = "c";
    private static final String STIFFNESS_DIVISOR_FIELD = "d";
    private static final String FPS_SCALE_FIELD = "b";
    private static final String CHAIN_ANGLE_BIAS_FIELD = "f";
    private static final String THRESHOLD_SCALE_FIELD = "e";
    private static final String VECTOR_X_METHOD = "getX";
    private static final String VECTOR_Y_METHOD = "getY";
    private static final String SET_X_METHOD = "setX";
    private static final String SET_Y_METHOD = "setY";
    private static final String SIZE_METHOD = "size";
    private static final String GET_METHOD = "get";
    private static final String INIT_METHOD = "<init>";

    private static final String VECTOR2_TYPE = "L" + PhysicsAccelerationTarget.VECTOR2_OWNER + ";";
    private static final String PARAMS_DESC = "()L" + PhysicsAccelerationTarget.VECTOR2_OWNER + ";";
    private static final String SET_VEC_DESC = "(" + VECTOR2_TYPE + ")V";
    private static final String VOID_FF_DESC = "(FF)V";
    private static final String F_VOID_DESC = "()F";
    private static final String F_SET_DESC = "(F)V";
    private static final String INT_VOID_DESC = "()I";
    private static final String BOOL_VOID_DESC = "()Z";
    private static final String OBJ_INT_DESC = "(I)Ljava/lang/Object;";
    private static final String DD_D_DESC = "(DD)D";
    private static final String D_D_DESC = "(D)D";
    private static final String WRAP_FF_DESC = "(FF)F";
    private static final String FLOAT_TO_RAW_BITS_DESC = "(F)I";
    private static final String FLOAT_VALUE_OF_DESC = "(F)Ljava/lang/Float;";

    private static final float ZERO_F = 0.0f;
    private static final float ONE_F = 1.0f;
    private static final int NEG_ONE = -1;
    private static final int ZERO_I = 0;

    private PhysicsMethodEmitters() {}

    static void emitActiveGate(final MethodVisitor mv, final Label fallback) {
        final Label popAndFallback = new Label();
        final Label active = new Label();
        SlotBridgeEmitter.emitPredicateConsult(mv, PhysicsAccelerationBridge.ACTIVE_PROPERTY, popAndFallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        SlotBridgeEmitter.emitTest(mv);
        mv.visitJumpInsn(Opcodes.IFNE, active);
        mv.visitJumpInsn(Opcodes.GOTO, fallback);
        mv.visitLabel(popAndFallback);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, fallback);
        mv.visitLabel(active);
    }

    static void emitNotNull(final MethodVisitor mv, final int slot) {
        final Label ok = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitJumpInsn(Opcodes.IFNONNULL, ok);
        mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitLdcInsn(EMPTY_STRING);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, INTRINSICS_OWNER, CHECK_NOT_NULL_PARAM, CHECK_NOT_NULL_DESC, false);
        mv.visitLabel(ok);
    }

    static void emitFindParameterFastPath(final MethodVisitor mv, final Label fallback) {
        emitActiveGate(mv, fallback);
        emitNotNull(mv, 1);
        final Label nonNullGuid = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNONNULL, nonNullGuid);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(nonNullGuid);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.PARAMETER_SET_OWNER,
                GET_METHOD,
                "(Lcom/live2d/type/CParameterGuid;)Lcom/live2d/cubism/doc/model/param/CParameter;",
                false);
        mv.visitInsn(Opcodes.ARETURN);
    }

    static void emitResetVerticesFastPath(final MethodVisitor mv, final Label fallback) {
        emitActiveGate(mv, fallback);
        emitNotNull(mv, 1);
        emitNotNull(mv, 2);

        final int lV0 = 3;
        final int lSize = 4;
        final int lIndex = 5;
        final int lCur = 6;
        final int lPrev = 7;
        final int lPrevPos = 8;
        final int lNewPos = 9;

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, lV0);

        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitTypeInsn(Opcodes.NEW, PhysicsAccelerationTarget.VECTOR2_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL, PhysicsAccelerationTarget.VECTOR2_OWNER, INIT_METHOD, VOID_FF_DESC, false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setPosition", SET_VEC_DESC, false);

        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getPosition", PARAMS_DESC, false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setOldPosition", SET_VEC_DESC, false);

        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setOldGravity", SET_VEC_DESC, false);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, SIZE_METHOD, INT_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ISTORE, lSize);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ISTORE, lIndex);

        final Label loopTop = new Label();
        final Label loopDone = new Label();
        mv.visitLabel(loopTop);
        mv.visitVarInsn(Opcodes.ILOAD, lIndex);
        mv.visitVarInsn(Opcodes.ILOAD, lSize);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopDone);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, lIndex);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, lCur);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, lIndex);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, lPrev);

        mv.visitVarInsn(Opcodes.ALOAD, lPrev);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lPrevPos);

        // Emits FCONST_0 + FADD on X to preserve IEEE-754 negative-zero canonicalization from GVector2.plus.
        mv.visitTypeInsn(Opcodes.NEW, PhysicsAccelerationTarget.VECTOR2_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, lPrevPos);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.ALOAD, lPrevPos);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lCur);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getRadius", F_VOID_DESC, false);
        mv.visitInsn(Opcodes.FADD);
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL, PhysicsAccelerationTarget.VECTOR2_OWNER, INIT_METHOD, VOID_FF_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lNewPos);

        mv.visitVarInsn(Opcodes.ALOAD, lCur);
        mv.visitVarInsn(Opcodes.ALOAD, lNewPos);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setPosition", SET_VEC_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lCur);
        mv.visitVarInsn(Opcodes.ALOAD, lNewPos);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setLocalPosition", SET_VEC_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lCur);
        mv.visitVarInsn(Opcodes.ALOAD, lNewPos);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setOldPosition", SET_VEC_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lCur);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setOldGravity", SET_VEC_DESC, false);

        mv.visitIincInsn(lIndex, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopTop);

        mv.visitLabel(loopDone);
        mv.visitInsn(Opcodes.RETURN);
    }

    static void emitStepVerticesFastPath(
            final MethodVisitor mv, final PhysicsAccelerationTarget target, final Label fallback) {
        emitActiveGate(mv, fallback);
        emitNotNull(mv, 1);
        emitNotNull(mv, 2);
        emitNotNull(mv, 4);
        emitNotNull(mv, 5);

        final Label dtNonZero = new Label();
        mv.visitVarInsn(Opcodes.FLOAD, 6);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFNE, dtNonZero);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(dtNonZero);

        final String owner = target.evaluatorOwner();
        final String mathOwner = target.mathUtilsOwner();
        final String mathDesc = "L" + mathOwner + ";";

        final int lV0 = 8;
        final int lThisC = 9;
        final int lGx = 10;
        final int lGy = 11;
        final int lWindX = 12;
        final int lWindY = 13;
        final int lSize = 14;
        final int lIdx = 15;
        final int lV0Pos = 16;
        final int lV0Lp = 17;
        final int lV0Op = 18;
        final int lV0Grav = 19;
        final int lPrevV = 20;
        final int lCurV = 21;
        final int lPrevLp = 22;
        final int lPrevLx = 23;
        final int lPrevLy = 24;
        final int lOldLp = 25;
        final int lLpX = 26;
        final int lLpY = 27;
        final int lFx = 28;
        final int lFy = 29;
        final int lDiffX = 30;
        final int lDiffY = 31;
        final int lRot = 32;
        final int lNewLx = 33;
        final int lNewLy = 34;
        final int lDelay = 35;
        final int lCurPos = 36;
        final int lPrevOldPos = 37;
        final int lCurVel = 38;
        final int lCurForce = 39;
        final int lCurOldGrav = 40;
        final int lTempF = 41;
        final int lDx = 42;
        final int lDy = 43;
        final int lVx = 44;
        final int lVy = 45;
        final int lSharedGrav = 46;
        final int lSafeGrav = 47;

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, lV0);

        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setLocalPosition", SET_VEC_DESC, false);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, GRAVITY_DEFAULT_FIELD, VECTOR2_TYPE);
        mv.visitVarInsn(Opcodes.ASTORE, lThisC);

        // Compute normalized new gravity direction vector (gx, gy) without GVector2 allocation.
        emitVectorAngleDiff(mv, mathOwner, mathDesc, 4, lThisC);
        mv.visitVarInsn(Opcodes.FSTORE, lTempF);

        mv.visitFieldInsn(Opcodes.GETSTATIC, mathOwner, SINGLETON_FIELD, mathDesc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, mathOwner, "c", F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FLOAD, 3);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, lTempF);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, lTempF);

        mv.visitVarInsn(Opcodes.FLOAD, lTempF);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "sin", D_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitVarInsn(Opcodes.FSTORE, lGx);

        mv.visitVarInsn(Opcodes.FLOAD, lTempF);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "cos", D_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitVarInsn(Opcodes.FSTORE, lGy);

        emitNormalizePair(mv, lGx, lGy, lTempF);

        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FSTORE, lWindX);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FSTORE, lWindY);

        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lV0Pos);
        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getLocalPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lV0Lp);
        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getOldPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lV0Op);
        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getOldGravity", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lV0Grav);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, SIZE_METHOD, INT_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ISTORE, lSize);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ISTORE, lIdx);

        final Label vertexLoopTop = new Label();
        final Label vertexLoopDone = new Label();
        mv.visitLabel(vertexLoopTop);
        mv.visitVarInsn(Opcodes.ILOAD, lIdx);
        mv.visitVarInsn(Opcodes.ILOAD, lSize);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, vertexLoopDone);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, lIdx);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, lPrevV);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, lIdx);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, lCurV);

        mv.visitVarInsn(Opcodes.ALOAD, lPrevV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getLocalPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lPrevLp);
        mv.visitVarInsn(Opcodes.ALOAD, lPrevLp);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FSTORE, lPrevLx);
        mv.visitVarInsn(Opcodes.ALOAD, lPrevLp);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FSTORE, lPrevLy);

        // Compute vertex force locally without mutating vertex.force prematurely.
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getAcceleration", F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FSTORE, lTempF);
        mv.visitVarInsn(Opcodes.FLOAD, lGx);
        mv.visitVarInsn(Opcodes.FLOAD, lTempF);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, lWindX);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, lFx);
        mv.visitVarInsn(Opcodes.FLOAD, lGy);
        mv.visitVarInsn(Opcodes.FLOAD, lTempF);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, lWindY);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, lFy);

        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getLocalPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lOldLp);
        mv.visitVarInsn(Opcodes.ALOAD, lOldLp);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FSTORE, lLpX);
        mv.visitVarInsn(Opcodes.ALOAD, lOldLp);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FSTORE, lLpY);

        mv.visitVarInsn(Opcodes.FLOAD, lLpX);
        mv.visitVarInsn(Opcodes.FLOAD, lPrevLx);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FSTORE, lDiffX);
        mv.visitVarInsn(Opcodes.FLOAD, lLpY);
        mv.visitVarInsn(Opcodes.FLOAD, lPrevLy);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FSTORE, lDiffY);

        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getOldGravity", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lCurOldGrav);

        // Preserve exact operand order: a(oldGravity, newGravity) divides L.b(newG, oldG) by this.d.
        mv.visitFieldInsn(Opcodes.GETSTATIC, mathOwner, SINGLETON_FIELD, mathDesc);
        mv.visitVarInsn(Opcodes.FLOAD, lGy);
        mv.visitInsn(Opcodes.F2D);
        mv.visitVarInsn(Opcodes.FLOAD, lGx);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "atan2", DD_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitVarInsn(Opcodes.ALOAD, lCurOldGrav);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitInsn(Opcodes.F2D);
        mv.visitVarInsn(Opcodes.ALOAD, lCurOldGrav);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "atan2", DD_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, mathOwner, "b", WRAP_FF_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, STIFFNESS_DIVISOR_FIELD, F_VOID_DESC.substring(2));
        mv.visitInsn(Opcodes.FDIV);
        mv.visitVarInsn(Opcodes.FSTORE, lRot);

        // Match Cubism's sequential X-then-Y rotation where setX feeds the updated X into setY.
        mv.visitVarInsn(Opcodes.FLOAD, lRot);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "cos", D_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitVarInsn(Opcodes.FLOAD, lDiffX);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, lDiffY);
        mv.visitVarInsn(Opcodes.FLOAD, lRot);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "sin", D_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FSTORE, lDiffX);

        mv.visitVarInsn(Opcodes.FLOAD, lRot);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "sin", D_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitVarInsn(Opcodes.FLOAD, lDiffX);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, lDiffY);
        mv.visitVarInsn(Opcodes.FLOAD, lRot);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "cos", D_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, lDiffY);

        mv.visitVarInsn(Opcodes.FLOAD, lPrevLx);
        mv.visitVarInsn(Opcodes.FLOAD, lDiffX);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, lNewLx);
        mv.visitVarInsn(Opcodes.FLOAD, lPrevLy);
        mv.visitVarInsn(Opcodes.FLOAD, lDiffY);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, lNewLy);

        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getDelay", F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FLOAD, 6);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, FPS_SCALE_FIELD, F_VOID_DESC.substring(2));
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FSTORE, lDelay);

        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getVelocity", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lCurVel);

        // Velocity step + double force step: preserve separate multiplications for exact rounding.
        mv.visitVarInsn(Opcodes.FLOAD, lNewLx);
        mv.visitVarInsn(Opcodes.ALOAD, lCurVel);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FLOAD, lDelay);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FLOAD, lFx);
        mv.visitVarInsn(Opcodes.FLOAD, lDelay);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, lDelay);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, lNewLx);

        mv.visitVarInsn(Opcodes.FLOAD, lNewLy);
        mv.visitVarInsn(Opcodes.ALOAD, lCurVel);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FLOAD, lDelay);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FLOAD, lFy);
        mv.visitVarInsn(Opcodes.FLOAD, lDelay);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, lDelay);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, lNewLy);

        mv.visitVarInsn(Opcodes.FLOAD, lNewLx);
        mv.visitVarInsn(Opcodes.FLOAD, lPrevLx);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FSTORE, lDx);
        mv.visitVarInsn(Opcodes.FLOAD, lNewLy);
        mv.visitVarInsn(Opcodes.FLOAD, lPrevLy);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FSTORE, lDy);

        emitNormalizePair(mv, lDx, lDy, lTempF);

        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getRadius", F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FSTORE, lTempF);

        mv.visitVarInsn(Opcodes.FLOAD, lPrevLx);
        mv.visitVarInsn(Opcodes.FLOAD, lDx);
        mv.visitVarInsn(Opcodes.FLOAD, lTempF);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, lNewLx);

        mv.visitVarInsn(Opcodes.FLOAD, lPrevLy);
        mv.visitVarInsn(Opcodes.FLOAD, lDy);
        mv.visitVarInsn(Opcodes.FLOAD, lTempF);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, lNewLy);

        // Threshold X clamp before velocity subtraction matches Cubism's setX(0) ordering.
        final Label absPos = new Label();
        final Label absDone = new Label();
        final Label noClamp = new Label();
        mv.visitVarInsn(Opcodes.FLOAD, lNewLx);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFGE, absPos);
        mv.visitVarInsn(Opcodes.FLOAD, lNewLx);
        mv.visitInsn(Opcodes.FNEG);
        mv.visitJumpInsn(Opcodes.GOTO, absDone);
        mv.visitLabel(absPos);
        mv.visitVarInsn(Opcodes.FLOAD, lNewLx);
        mv.visitLabel(absDone);
        mv.visitVarInsn(Opcodes.FLOAD, 7);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFGE, noClamp);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitVarInsn(Opcodes.FSTORE, lNewLx);
        mv.visitLabel(noClamp);

        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lCurPos);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getOldPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lPrevOldPos);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getForce", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lCurForce);

        final Label skipVel = new Label();
        mv.visitVarInsn(Opcodes.FLOAD, lDelay);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFEQ, skipVel);

        // Preserve GVector2.div: scale = 1.0f / delay, then scale * component, then * mobility.
        mv.visitInsn(Opcodes.FCONST_1);
        mv.visitVarInsn(Opcodes.FLOAD, lDelay);
        mv.visitInsn(Opcodes.FDIV);
        mv.visitVarInsn(Opcodes.FSTORE, lTempF);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getMobility", F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FSTORE, lVx);
        mv.visitVarInsn(Opcodes.FLOAD, lTempF);
        mv.visitVarInsn(Opcodes.FLOAD, lNewLx);
        mv.visitVarInsn(Opcodes.FLOAD, lLpX);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, lVx);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, lTempF);
        mv.visitVarInsn(Opcodes.FLOAD, lNewLy);
        mv.visitVarInsn(Opcodes.FLOAD, lLpY);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, lVx);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FSTORE, lVy);
        mv.visitVarInsn(Opcodes.FSTORE, lVx);

        final Label allocVel = new Label();
        final Label velDone = new Label();
        emitRejectIfEqual(mv, lCurVel, allocVel, lCurPos, lOldLp, lPrevOldPos, lCurForce, lCurOldGrav, lPrevLp, lThisC);
        emitRejectIfEqual(mv, lCurVel, allocVel, 2, 4, 5, lV0Pos, lV0Lp, lV0Op, lV0Grav);
        emitWriteVectorInPlace(mv, lCurVel, lVx, lVy);
        mv.visitJumpInsn(Opcodes.GOTO, velDone);
        mv.visitLabel(allocVel);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        emitNewVector(mv, lVx, lVy);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setVelocity", SET_VEC_DESC, false);
        mv.visitLabel(velDone);
        mv.visitLabel(skipVel);

        // Rotate oldLp into oldPosition and double-buffer localPosition using unaliased prevOldPos.
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitVarInsn(Opcodes.ALOAD, lOldLp);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setOldPosition", SET_VEC_DESC, false);

        final Label allocLp = new Label();
        final Label lpDone = new Label();
        emitRejectIfEqual(mv, lPrevOldPos, allocLp, lOldLp, lCurPos, lCurVel, lCurForce, lCurOldGrav, lPrevLp, lThisC);
        emitRejectIfEqual(mv, lPrevOldPos, allocLp, 2, 4, 5, lV0Pos, lV0Lp, lV0Op, lV0Grav);
        emitWriteVectorInPlace(mv, lPrevOldPos, lNewLx, lNewLy);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitVarInsn(Opcodes.ALOAD, lPrevOldPos);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setLocalPosition", SET_VEC_DESC, false);
        mv.visitJumpInsn(Opcodes.GOTO, lpDone);
        mv.visitLabel(allocLp);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        emitNewVector(mv, lNewLx, lNewLy);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setLocalPosition", SET_VEC_DESC, false);
        mv.visitLabel(lpDone);

        // Reset vertex.force to (0, 0) only when bit pattern is not already positive zero.
        final Label forceNeedsZero = new Label();
        final Label forceDone = new Label();
        final Label allocForce = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, lCurForce);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, FLOAT_OWNER, "floatToRawIntBits", FLOAT_TO_RAW_BITS_DESC, false);
        mv.visitJumpInsn(Opcodes.IFNE, forceNeedsZero);
        mv.visitVarInsn(Opcodes.ALOAD, lCurForce);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, FLOAT_OWNER, "floatToRawIntBits", FLOAT_TO_RAW_BITS_DESC, false);
        mv.visitJumpInsn(Opcodes.IFEQ, forceDone);

        mv.visitLabel(forceNeedsZero);
        emitRejectIfEqual(
                mv, lCurForce, allocForce, lCurPos, lOldLp, lPrevOldPos, lCurVel, lCurOldGrav, lPrevLp, lThisC);
        emitRejectIfEqual(mv, lCurForce, allocForce, 2, 4, 5, lV0Pos, lV0Lp, lV0Op, lV0Grav);
        mv.visitVarInsn(Opcodes.ALOAD, lCurForce);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, SET_X_METHOD, F_SET_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lCurForce);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, SET_Y_METHOD, F_SET_DESC, false);
        mv.visitJumpInsn(Opcodes.GOTO, forceDone);

        mv.visitLabel(allocForce);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitTypeInsn(Opcodes.NEW, PhysicsAccelerationTarget.VECTOR2_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL, PhysicsAccelerationTarget.VECTOR2_OWNER, INIT_METHOD, VOID_FF_DESC, false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setForce", SET_VEC_DESC, false);
        mv.visitLabel(forceDone);

        mv.visitIincInsn(lIdx, 1);
        mv.visitJumpInsn(Opcodes.GOTO, vertexLoopTop);

        // Defer oldGravity mutation until all vertices complete Pass 1 because chains often alias it.
        mv.visitLabel(vertexLoopDone);
        final Label exit = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, lSize);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, exit);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getOldGravity", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lSharedGrav);

        final Label checkScanStart = new Label();
        final Label allocGrav = new Label();
        final Label gravReady = new Label();
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ISTORE, lSafeGrav);
        emitRejectIfEqual(mv, lSharedGrav, allocGrav, 2, 4, 5, lThisC, lV0Grav, -1, -1);

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, lIdx);
        mv.visitLabel(checkScanStart);
        mv.visitVarInsn(Opcodes.ILOAD, lIdx);
        mv.visitVarInsn(Opcodes.ILOAD, lSize);
        final Label scanDone = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, scanDone);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, lIdx);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, lCurV);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lSharedGrav);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, allocGrav);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getLocalPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lSharedGrav);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, allocGrav);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getOldPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lSharedGrav);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, allocGrav);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getVelocity", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lSharedGrav);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, allocGrav);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getForce", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lSharedGrav);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, allocGrav);
        mv.visitIincInsn(lIdx, 1);
        mv.visitJumpInsn(Opcodes.GOTO, checkScanStart);

        mv.visitLabel(scanDone);
        emitWriteVectorInPlace(mv, lSharedGrav, lGx, lGy);
        mv.visitJumpInsn(Opcodes.GOTO, gravReady);

        mv.visitLabel(allocGrav);
        emitNewVector(mv, lGx, lGy);
        mv.visitVarInsn(Opcodes.ASTORE, lSharedGrav);

        mv.visitLabel(gravReady);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ISTORE, lIdx);
        final Label assignGravLoop = new Label();
        mv.visitLabel(assignGravLoop);
        mv.visitVarInsn(Opcodes.ILOAD, lIdx);
        mv.visitVarInsn(Opcodes.ILOAD, lSize);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, exit);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, lIdx);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, lCurV);
        final Label skipGravSet = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getOldGravity", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lSharedGrav);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, skipGravSet);
        mv.visitVarInsn(Opcodes.ALOAD, lCurV);
        mv.visitVarInsn(Opcodes.ALOAD, lSharedGrav);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "setOldGravity", SET_VEC_DESC, false);
        mv.visitLabel(skipGravSet);
        mv.visitIincInsn(lIdx, 1);
        mv.visitJumpInsn(Opcodes.GOTO, assignGravLoop);

        mv.visitLabel(exit);
        mv.visitInsn(Opcodes.RETURN);
    }

    static void emitEvaluateSourceFastPath(
            final MethodVisitor mv, final PhysicsAccelerationTarget target, final Label fallback) {
        emitActiveGate(mv, fallback);
        emitNotNull(mv, 1);
        emitNotNull(mv, 2);
        emitNotNull(mv, 3);
        emitNotNull(mv, 4);
        emitNotNull(mv, 6);

        final String owner = target.evaluatorOwner();
        final String mathOwner = target.mathUtilsOwner();
        final String mathDesc = "L" + mathOwner + ";";
        final String srcTypeDesc = "L" + PhysicsAccelerationTarget.SOURCE_TYPE_OWNER + ";";
        final String compDesc = "L" + PhysicsAccelerationTarget.CONTROLLER_COMPANION_OWNER + ";";

        final int lTotalAngle = 11;
        final int lRx = 12;
        final int lRy = 13;
        final int lInputs = 14;
        final int lSize = 15;
        final int lIdx = 16;
        final int lInput = 17;
        final int lWeight = 18;
        final int lParam = 19;
        final int lWeightDiv = 20;
        final int lRad = 21;
        final int lVertices = 22;
        final int lBasePos = 23;
        final int lThisC = 24;
        final int lOutputs = 25;
        final int lOut = 26;
        final int lVIdx = 27;
        final int lDirX = 28;
        final int lDirY = 29;
        final int lVal = 30;
        final int lRefX = 31;
        final int lRefY = 32;
        final int lTmpVec = 33;
        final int lTmpVec2 = 34;
        final int lV0 = 35;

        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitVarInsn(Opcodes.FSTORE, lTotalAngle);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitVarInsn(Opcodes.FSTORE, lRx);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitVarInsn(Opcodes.FSTORE, lRy);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.SETTINGS_SOURCE_OWNER,
                "getInputs",
                "()L" + PhysicsAccelerationTarget.ARRAY_LIST_OWNER + ";",
                false);
        mv.visitVarInsn(Opcodes.ASTORE, lInputs);
        mv.visitVarInsn(Opcodes.ALOAD, lInputs);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, SIZE_METHOD, INT_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ISTORE, lSize);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, lIdx);

        final Label inLoopTop = new Label();
        final Label inLoopDone = new Label();
        mv.visitLabel(inLoopTop);
        mv.visitVarInsn(Opcodes.ILOAD, lIdx);
        mv.visitVarInsn(Opcodes.ILOAD, lSize);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, inLoopDone);

        mv.visitVarInsn(Opcodes.ALOAD, lInputs);
        mv.visitVarInsn(Opcodes.ILOAD, lIdx);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.INPUT_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, lInput);

        mv.visitVarInsn(Opcodes.ALOAD, lInput);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.INPUT_OWNER, "getWeight", F_VOID_DESC, false);
        mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                "com/live2d/cubism/doc/gameData/physics/CPhysicsController",
                SINGLETON_FIELD,
                compDesc);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.CONTROLLER_COMPANION_OWNER, "b", F_VOID_DESC, false);
        mv.visitInsn(Opcodes.FDIV);
        mv.visitVarInsn(Opcodes.FSTORE, lWeight);

        mv.visitVarInsn(Opcodes.ALOAD, lInput);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.INPUT_OWNER, "getType", "()" + srcTypeDesc, false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.SOURCE_TYPE_OWNER, "ordinal", INT_VOID_DESC, false);

        final Label inCaseX = new Label();
        final Label inCaseY = new Label();
        final Label inCaseAngle = new Label();
        final Label inNext = new Label();
        mv.visitTableSwitchInsn(0, 2, inNext, inCaseX, inCaseY, inCaseAngle);

        emitInputCase(mv, owner, lInput, lRx, lWeight, lParam, "Position", inCaseX, inNext);
        emitInputCase(mv, owner, lInput, lRy, lWeight, lParam, "Position", inCaseY, inNext);
        emitInputCase(mv, owner, lInput, lTotalAngle, lWeight, lParam, "Angle", inCaseAngle, inNext);

        mv.visitLabel(inNext);
        mv.visitIincInsn(lIdx, 1);
        mv.visitJumpInsn(Opcodes.GOTO, inLoopTop);

        mv.visitLabel(inLoopDone);
        mv.visitFieldInsn(Opcodes.GETSTATIC, mathOwner, SINGLETON_FIELD, mathDesc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, mathOwner, "c", F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FLOAD, lTotalAngle);
        mv.visitInsn(Opcodes.FNEG);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FSTORE, lRad);

        // Rotate (rx, ry) sequentially matching bytecode order.
        mv.visitVarInsn(Opcodes.FLOAD, lRx);
        mv.visitVarInsn(Opcodes.FLOAD, lRad);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "cos", D_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, lRy);
        mv.visitVarInsn(Opcodes.FLOAD, lRad);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "sin", D_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FSTORE, lRx);

        mv.visitVarInsn(Opcodes.FLOAD, lRx);
        mv.visitVarInsn(Opcodes.FLOAD, lRad);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "sin", D_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, lRy);
        mv.visitVarInsn(Opcodes.FLOAD, lRad);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "cos", D_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, lRy);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.SETTINGS_SOURCE_OWNER,
                "getVertices",
                "()L" + PhysicsAccelerationTarget.ARRAY_LIST_OWNER + ";",
                false);
        mv.visitVarInsn(Opcodes.ASTORE, lVertices);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, GRAVITY_DEFAULT_FIELD, VECTOR2_TYPE);
        mv.visitVarInsn(Opcodes.ASTORE, lThisC);

        mv.visitVarInsn(Opcodes.ALOAD, lVertices);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, SIZE_METHOD, INT_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ISTORE, lSize);

        // Reuse vertices[0].localPosition for basePos only when deltaTime != 0 and pointer is unaliased.
        final Label allocBase = new Label();
        final Label baseReady = new Label();
        mv.visitVarInsn(Opcodes.FLOAD, 5);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFEQ, allocBase);
        mv.visitVarInsn(Opcodes.ILOAD, lSize);
        mv.visitJumpInsn(Opcodes.IFLE, allocBase);

        mv.visitVarInsn(Opcodes.ALOAD, lVertices);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, lV0);

        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getLocalPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lBasePos);

        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lTmpVec);
        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getOldPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lTmpVec2);
        emitRejectIfEqual(mv, lBasePos, allocBase, lTmpVec, lTmpVec2, lThisC, 3, 4, -1, -1);

        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getOldGravity", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lTmpVec);
        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getVelocity", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lTmpVec2);
        emitRejectIfEqual(mv, lBasePos, allocBase, lTmpVec, lTmpVec2, -1, -1, -1, -1, -1);

        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getForce", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lTmpVec);
        emitRejectIfEqual(mv, lBasePos, allocBase, lTmpVec, -1, -1, -1, -1, -1, -1);

        final Label writeBase = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, lSize);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, writeBase);
        mv.visitVarInsn(Opcodes.ALOAD, lVertices);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, lV0);
        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getLocalPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lTmpVec);
        mv.visitVarInsn(Opcodes.ALOAD, lV0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getOldPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lTmpVec2);
        emitRejectIfEqual(mv, lBasePos, allocBase, lTmpVec, lTmpVec2, -1, -1, -1, -1, -1);

        mv.visitLabel(writeBase);
        emitWriteVectorInPlace(mv, lBasePos, lRx, lRy);
        mv.visitJumpInsn(Opcodes.GOTO, baseReady);

        mv.visitLabel(allocBase);
        final Label zeroDtSkipAlloc = new Label();
        mv.visitVarInsn(Opcodes.FLOAD, 5);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFNE, zeroDtSkipAlloc);
        mv.visitVarInsn(Opcodes.ALOAD, lThisC);
        mv.visitVarInsn(Opcodes.ASTORE, lBasePos);
        mv.visitJumpInsn(Opcodes.GOTO, baseReady);
        mv.visitLabel(zeroDtSkipAlloc);
        emitNewVector(mv, lRx, lRy);
        mv.visitVarInsn(Opcodes.ASTORE, lBasePos);

        mv.visitLabel(baseReady);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, lVertices);
        mv.visitVarInsn(Opcodes.ALOAD, lBasePos);
        mv.visitVarInsn(Opcodes.FLOAD, lTotalAngle);
        mv.visitVarInsn(Opcodes.ALOAD, lThisC);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.FLOAD, 5);
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, THRESHOLD_SCALE_FIELD, F_VOID_DESC.substring(2));
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.SETTINGS_SOURCE_OWNER,
                "getNormalizedPositionValueMax",
                F_VOID_DESC,
                false);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                owner,
                PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                PhysicsAccelerationTarget.STEP_VERTICES_DESCRIPTOR,
                false);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.SETTINGS_SOURCE_OWNER,
                "getOutputs",
                "()L" + PhysicsAccelerationTarget.ARRAY_LIST_OWNER + ";",
                false);
        mv.visitVarInsn(Opcodes.ASTORE, lOutputs);
        mv.visitVarInsn(Opcodes.ALOAD, lOutputs);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, SIZE_METHOD, INT_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ISTORE, lSize);
        mv.visitVarInsn(Opcodes.ALOAD, lVertices);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, SIZE_METHOD, INT_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ISTORE, lInputs);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, lIdx);

        final Label outLoopTop = new Label();
        final Label outLoopDone = new Label();
        mv.visitLabel(outLoopTop);
        mv.visitVarInsn(Opcodes.ILOAD, lIdx);
        mv.visitVarInsn(Opcodes.ILOAD, lSize);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, outLoopDone);

        mv.visitVarInsn(Opcodes.ALOAD, lOutputs);
        mv.visitVarInsn(Opcodes.ILOAD, lIdx);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.OUTPUT_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, lOut);

        mv.visitVarInsn(Opcodes.ALOAD, lOut);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.OUTPUT_OWNER, "getVertexIndex", INT_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ISTORE, lVIdx);

        final Label outNext = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, lVIdx);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, outNext);
        mv.visitVarInsn(Opcodes.ILOAD, lVIdx);
        mv.visitVarInsn(Opcodes.ILOAD, lInputs);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, outNext);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, lOut);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.OUTPUT_OWNER,
                "getDestination",
                "()Lcom/live2d/type/CParameterGuid;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                owner,
                PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                PhysicsAccelerationTarget.FIND_PARAMETER_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, lParam);
        mv.visitVarInsn(Opcodes.ALOAD, lParam);
        mv.visitJumpInsn(Opcodes.IFNULL, outNext);

        final Label skipMaskE = new Label();
        final Label skipMaskD = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitJumpInsn(Opcodes.IFNULL, skipMaskE);
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitVarInsn(Opcodes.ALOAD, lParam);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.PARAMETER_OWNER,
                "getId",
                "()Lcom/live2d/cubism/doc/model/id/CParameterId;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.VIEWER_MASK_OWNER,
                "e",
                "(Lcom/live2d/cubism/doc/model/id/CParameterId;)Z",
                false);
        mv.visitJumpInsn(Opcodes.IFNE, outNext);
        mv.visitLabel(skipMaskE);

        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitJumpInsn(Opcodes.IFNULL, skipMaskD);
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitVarInsn(Opcodes.ALOAD, lParam);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.PARAMETER_OWNER,
                "getId",
                "()Lcom/live2d/cubism/doc/model/id/CParameterId;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.VIEWER_MASK_OWNER,
                "d",
                "(Lcom/live2d/cubism/doc/model/id/CParameterId;)Z",
                false);
        mv.visitJumpInsn(Opcodes.IFNE, outNext);
        mv.visitLabel(skipMaskD);

        mv.visitVarInsn(Opcodes.ALOAD, lVertices);
        mv.visitVarInsn(Opcodes.ILOAD, lVIdx);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getLocalPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lTmpVec);

        mv.visitVarInsn(Opcodes.ALOAD, lVertices);
        mv.visitVarInsn(Opcodes.ILOAD, lVIdx);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getLocalPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lTmpVec2);

        mv.visitVarInsn(Opcodes.ALOAD, lTmpVec);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lTmpVec2);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FSTORE, lDirX);

        mv.visitVarInsn(Opcodes.ALOAD, lTmpVec);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lTmpVec2);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FSTORE, lDirY);

        mv.visitVarInsn(Opcodes.ALOAD, lOut);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.OUTPUT_OWNER, "getType", "()" + srcTypeDesc, false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.SOURCE_TYPE_OWNER, "ordinal", INT_VOID_DESC, false);

        final Label outCaseX = new Label();
        final Label outCaseY = new Label();
        final Label outCaseAngle = new Label();
        final Label outDefault = new Label();
        final Label outValReady = new Label();
        mv.visitTableSwitchInsn(0, 2, outDefault, outCaseX, outCaseY, outCaseAngle);

        emitOutputLinearCase(mv, lOut, lDirX, lVal, outCaseX, outValReady);
        emitOutputLinearCase(mv, lOut, lDirY, lVal, outCaseY, outValReady);

        mv.visitLabel(outCaseAngle);
        final Label useThisC = new Label();
        final Label refReady = new Label();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, CHAIN_ANGLE_BIAS_FIELD, BOOL_VOID_DESC.substring(2));
        mv.visitJumpInsn(Opcodes.IFEQ, useThisC);
        mv.visitVarInsn(Opcodes.ILOAD, lVIdx);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, useThisC);

        mv.visitVarInsn(Opcodes.ALOAD, lVertices);
        mv.visitVarInsn(Opcodes.ILOAD, lVIdx);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getLocalPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lTmpVec);

        mv.visitVarInsn(Opcodes.ALOAD, lVertices);
        mv.visitVarInsn(Opcodes.ILOAD, lVIdx);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.ARRAY_LIST_OWNER, GET_METHOD, OBJ_INT_DESC, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PhysicsAccelerationTarget.VERTEX_OWNER);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VERTEX_OWNER, "getLocalPosition", PARAMS_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, lTmpVec2);

        mv.visitVarInsn(Opcodes.ALOAD, lTmpVec);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lTmpVec2);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FSTORE, lRefX);
        mv.visitVarInsn(Opcodes.ALOAD, lTmpVec);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, lTmpVec2);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FSTORE, lRefY);
        mv.visitJumpInsn(Opcodes.GOTO, refReady);

        mv.visitLabel(useThisC);
        mv.visitVarInsn(Opcodes.ALOAD, lThisC);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FSTORE, lRefX);
        mv.visitVarInsn(Opcodes.ALOAD, lThisC);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitVarInsn(Opcodes.FSTORE, lRefY);

        mv.visitLabel(refReady);
        mv.visitFieldInsn(Opcodes.GETSTATIC, mathOwner, SINGLETON_FIELD, mathDesc);
        mv.visitVarInsn(Opcodes.FLOAD, lDirY);
        mv.visitInsn(Opcodes.F2D);
        mv.visitVarInsn(Opcodes.FLOAD, lDirX);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "atan2", DD_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitVarInsn(Opcodes.FLOAD, lRefY);
        mv.visitInsn(Opcodes.F2D);
        mv.visitVarInsn(Opcodes.FLOAD, lRefX);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "atan2", DD_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, mathOwner, "b", WRAP_FF_DESC, false);
        mv.visitVarInsn(Opcodes.FSTORE, lVal);
        emitMaybeReverse(mv, lOut, lVal);
        mv.visitJumpInsn(Opcodes.GOTO, outValReady);

        mv.visitLabel(outDefault);
        mv.visitVarInsn(Opcodes.FLOAD, lDirX);
        mv.visitVarInsn(Opcodes.FSTORE, lVal);

        mv.visitLabel(outValReady);
        final Label skipOutValues = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitJumpInsn(Opcodes.IFNULL, skipOutValues);
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitVarInsn(Opcodes.ILOAD, lIdx);
        mv.visitVarInsn(Opcodes.FLOAD, lVal);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, FLOAT_OWNER, "valueOf", FLOAT_VALUE_OF_DESC, false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, ARRAY_LIST_OWNER_JDK, "set", "(ILjava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(skipOutValues);

        final Label skipNotify = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, lParam);
        mv.visitVarInsn(Opcodes.FLOAD, lVal);
        mv.visitVarInsn(Opcodes.ALOAD, lOut);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                owner,
                PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                PhysicsAccelerationTarget.APPLY_OUTPUT_DESCRIPTOR,
                false);
        mv.visitJumpInsn(Opcodes.IFEQ, skipNotify);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, lOut);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.OUTPUT_OWNER,
                "getGuid",
                "()Lcom/live2d/type/CPhysicsDataGuid;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE, FUNCTION1_OWNER, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(skipNotify);

        final Label skipOutMap = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitJumpInsn(Opcodes.IFNULL, skipOutMap);
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitVarInsn(Opcodes.ALOAD, lParam);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.PARAMETER_OWNER,
                "getId",
                "()Lcom/live2d/cubism/doc/model/id/CParameterId;",
                false);
        mv.visitVarInsn(Opcodes.ALOAD, lParam);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.PARAMETER_OWNER, "getValue", F_VOID_DESC, false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, FLOAT_OWNER, "valueOf", FLOAT_VALUE_OF_DESC, false);
        mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                MAP_OWNER,
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                true);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(skipOutMap);

        mv.visitLabel(outNext);
        mv.visitIincInsn(lIdx, 1);
        mv.visitJumpInsn(Opcodes.GOTO, outLoopTop);

        mv.visitLabel(outLoopDone);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.FLOAD, lTotalAngle);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.SETTINGS_SOURCE_OWNER,
                "setTotalAngle",
                F_SET_DESC,
                false);
        mv.visitInsn(Opcodes.RETURN);
    }

    private static void emitInputCase(
            final MethodVisitor mv,
            final String owner,
            final int lInput,
            final int accSlot,
            final int lWeight,
            final int lParam,
            final String kindPrefix,
            final Label startLabel,
            final Label nextLabel) {
        mv.visitLabel(startLabel);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, lInput);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.INPUT_OWNER,
                "getSource",
                "()Lcom/live2d/type/CParameterGuid;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                owner,
                PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                PhysicsAccelerationTarget.FIND_PARAMETER_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, lParam);
        mv.visitVarInsn(Opcodes.ALOAD, lParam);
        mv.visitJumpInsn(Opcodes.IFNULL, nextLabel);

        mv.visitVarInsn(Opcodes.FLOAD, accSlot);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, lParam);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.SETTINGS_SOURCE_OWNER,
                "getNormalized" + kindPrefix + "ValueMin",
                F_VOID_DESC,
                false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.SETTINGS_SOURCE_OWNER,
                "getNormalized" + kindPrefix + "ValueMax",
                F_VOID_DESC,
                false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PhysicsAccelerationTarget.SETTINGS_SOURCE_OWNER,
                "getNormalized" + kindPrefix + "DefaultValue",
                F_VOID_DESC,
                false);
        mv.visitVarInsn(Opcodes.ALOAD, lInput);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.INPUT_OWNER, "isReverse", BOOL_VOID_DESC, false);
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                owner,
                PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                PhysicsAccelerationTarget.APPLY_INPUT_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.FLOAD, lWeight);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, accSlot);
        mv.visitJumpInsn(Opcodes.GOTO, nextLabel);
    }

    private static void emitOutputLinearCase(
            final MethodVisitor mv,
            final int lOut,
            final int dirSlot,
            final int valSlot,
            final Label startLabel,
            final Label doneLabel) {
        mv.visitLabel(startLabel);
        mv.visitVarInsn(Opcodes.FLOAD, dirSlot);
        mv.visitVarInsn(Opcodes.FSTORE, valSlot);
        emitMaybeReverse(mv, lOut, valSlot);
        mv.visitJumpInsn(Opcodes.GOTO, doneLabel);
    }

    private static void emitMaybeReverse(final MethodVisitor mv, final int lOut, final int valSlot) {
        final Label keep = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, lOut);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.OUTPUT_OWNER, "isReverse", BOOL_VOID_DESC, false);
        mv.visitJumpInsn(Opcodes.IFEQ, keep);
        mv.visitVarInsn(Opcodes.FLOAD, valSlot);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitInsn(Opcodes.I2F);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FSTORE, valSlot);
        mv.visitLabel(keep);
    }

    private static void emitVectorAngleDiff(
            final MethodVisitor mv, final String mathOwner, final String mathDesc, final int v1Slot, final int v2Slot) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, mathOwner, SINGLETON_FIELD, mathDesc);
        mv.visitVarInsn(Opcodes.ALOAD, v1Slot);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitInsn(Opcodes.F2D);
        mv.visitVarInsn(Opcodes.ALOAD, v1Slot);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "atan2", DD_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitVarInsn(Opcodes.ALOAD, v2Slot);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_Y_METHOD, F_VOID_DESC, false);
        mv.visitInsn(Opcodes.F2D);
        mv.visitVarInsn(Opcodes.ALOAD, v2Slot);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, VECTOR_X_METHOD, F_VOID_DESC, false);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "atan2", DD_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, mathOwner, "b", WRAP_FF_DESC, false);
    }

    private static void emitNormalizePair(
            final MethodVisitor mv, final int xSlot, final int ySlot, final int lenSqSlot) {
        final Label skipNorm = new Label();
        mv.visitVarInsn(Opcodes.FLOAD, xSlot);
        mv.visitVarInsn(Opcodes.FLOAD, xSlot);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FLOAD, ySlot);
        mv.visitVarInsn(Opcodes.FLOAD, ySlot);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FADD);
        mv.visitVarInsn(Opcodes.FSTORE, lenSqSlot);
        mv.visitVarInsn(Opcodes.FLOAD, lenSqSlot);
        mv.visitInsn(Opcodes.F2D);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitInsn(Opcodes.DCMPL);
        mv.visitJumpInsn(Opcodes.IFLE, skipNorm);
        mv.visitInsn(Opcodes.FCONST_1);
        mv.visitVarInsn(Opcodes.FLOAD, lenSqSlot);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATH_OWNER, "sqrt", D_D_DESC, false);
        mv.visitInsn(Opcodes.D2F);
        mv.visitInsn(Opcodes.FDIV);
        mv.visitVarInsn(Opcodes.FSTORE, lenSqSlot);
        mv.visitVarInsn(Opcodes.FLOAD, xSlot);
        mv.visitVarInsn(Opcodes.FLOAD, lenSqSlot);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FSTORE, xSlot);
        mv.visitVarInsn(Opcodes.FLOAD, ySlot);
        mv.visitVarInsn(Opcodes.FLOAD, lenSqSlot);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitVarInsn(Opcodes.FSTORE, ySlot);
        mv.visitLabel(skipNorm);
    }

    private static void emitRejectIfEqual(
            final MethodVisitor mv,
            final int candidateSlot,
            final Label rejectLabel,
            final int s0,
            final int s1,
            final int s2,
            final int s3,
            final int s4,
            final int s5,
            final int s6) {
        final int[] slots = {s0, s1, s2, s3, s4, s5, s6};
        for (final int s : slots) {
            if (s < ZERO_I) {
                continue;
            }
            mv.visitVarInsn(Opcodes.ALOAD, candidateSlot);
            mv.visitVarInsn(Opcodes.ALOAD, s);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, rejectLabel);
        }
    }

    private static void emitWriteVectorInPlace(
            final MethodVisitor mv, final int vecSlot, final int xSlot, final int ySlot) {
        mv.visitVarInsn(Opcodes.ALOAD, vecSlot);
        mv.visitVarInsn(Opcodes.FLOAD, xSlot);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, SET_X_METHOD, F_SET_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, vecSlot);
        mv.visitVarInsn(Opcodes.FLOAD, ySlot);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PhysicsAccelerationTarget.VECTOR2_OWNER, SET_Y_METHOD, F_SET_DESC, false);
    }

    private static void emitNewVector(final MethodVisitor mv, final int xSlot, final int ySlot) {
        mv.visitTypeInsn(Opcodes.NEW, PhysicsAccelerationTarget.VECTOR2_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.FLOAD, xSlot);
        mv.visitVarInsn(Opcodes.FLOAD, ySlot);
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL, PhysicsAccelerationTarget.VECTOR2_OWNER, INIT_METHOD, VOID_FF_DESC, false);
    }
}
