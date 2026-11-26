package dev.turboism.adapter.cubism.optimization.warpdeform;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Reflection bridge for Cubism 5.3+ {@code extension/a/*} Coons patch and Bezier drag solvers.
 *
 * <p>Resolves optional extension classes cleanly (returning {@code null} on earlier Cubism releases)
 * so heavy control-point drags run through {@link WarpDeformMath} without intermediate allocations.</p>
 */
final class ExtensionBezierSupport {

    private static final String EXT_C_OWNER = "com/live2d/cubism/doc/model/extension/a/c";
    private static final String EXT_D_OWNER = WarpDeformEditTarget.EXTENSION_BEZIER_EVAL_OWNER;
    private static final String EXT_I_OWNER = "com/live2d/cubism/doc/model/extension/a/i";
    private static final String EXT_J_OWNER = "com/live2d/cubism/doc/model/extension/a/j";
    private static final String EXT_K_OWNER = "com/live2d/cubism/doc/model/extension/a/k";

    private static final String VEC2_DESCRIPTOR = "L" + WarpDeformEditTarget.GVECTOR2_OWNER + ";";
    private static final String GET_VEC2_DESCRIPTOR = "()" + VEC2_DESCRIPTOR;
    private static final String GET_LIST_DESCRIPTOR = "()Ljava/util/List;";
    private static final String LIST_DESCRIPTOR = "Ljava/util/List;";
    private static final String INT_DESCRIPTOR = "I";
    private static final String FLOAT_GET_DESCRIPTOR = "()F";
    private static final String FLOAT_SET_DESCRIPTOR = "(F)V";
    private static final String FLOAT2_INIT_DESCRIPTOR = "(FF)V";
    private static final String EXT_J_INIT_DESCRIPTOR = "(L" + EXT_K_OWNER + ";" + VEC2_DESCRIPTOR + ")V";

    private static final int NODE_FLOAT_STRIDE = 10;
    private static final int COONS_PATCH_NODES = 4;
    private static final int COORD_STRIDE = 2;
    private static final int UV_RESIDUAL_FLOATS = 4;

    private final Class<?> extCClass;
    private final Class<?> extDClass;
    private final Class<?> extIClass;
    private final Class<?> extJClass;
    private final Class<?> extKClass;

    private final MethodHandle vec2CtorFloats;
    private final MethodHandle vec2GetX;
    private final MethodHandle vec2GetY;

    private final MethodHandle extCGetAnchor;
    private final MethodHandle extCGetCn;
    private final MethodHandle extCGetCs;
    private final MethodHandle extCGetCw;
    private final MethodHandle extCGetCe;

    private final MethodHandle extDGetCol;
    private final MethodHandle extDGetRow;
    private final MethodHandle extDGetNodes;

    private final MethodHandle extIGetParams;
    private final MethodHandle extKGetU;
    private final MethodHandle extKGetV;
    private final MethodHandle extKSetU;
    private final MethodHandle extKSetV;
    private final MethodHandle extKCtor;
    private final MethodHandle extJCtor;

    private final ThreadLocal<ScratchBuffers> scratch = ThreadLocal.withInitial(ScratchBuffers::new);

    private ExtensionBezierSupport(
            final Class<?> extCClass,
            final Class<?> extDClass,
            final Class<?> extIClass,
            final Class<?> extJClass,
            final Class<?> extKClass,
            final Class<?> vec2Class,
            final MethodHandle vec2GetX,
            final MethodHandle vec2GetY)
            throws ReflectiveOperationException {
        this.extCClass = extCClass;
        this.extDClass = extDClass;
        this.extIClass = extIClass;
        this.extJClass = extJClass;
        this.extKClass = extKClass;
        this.vec2GetX = vec2GetX;
        this.vec2GetY = vec2GetY;

        final MethodType objUnary = MethodType.methodType(Object.class, Object.class);
        final MethodType floatUnary = MethodType.methodType(float.class, Object.class);

        this.vec2CtorFloats = HostHandles.construct(
                vec2Class, FLOAT2_INIT_DESCRIPTOR, MethodType.methodType(Object.class, float.class, float.class));
        this.extCGetAnchor = HostHandles.virtual(extCClass, "a", GET_VEC2_DESCRIPTOR, objUnary);
        this.extCGetCn = HostHandles.virtual(extCClass, "b", GET_VEC2_DESCRIPTOR, objUnary);
        this.extCGetCs = HostHandles.virtual(extCClass, "c", GET_VEC2_DESCRIPTOR, objUnary);
        this.extCGetCw = HostHandles.virtual(extCClass, "d", GET_VEC2_DESCRIPTOR, objUnary);
        this.extCGetCe = HostHandles.virtual(extCClass, "e", GET_VEC2_DESCRIPTOR, objUnary);

        this.extDGetCol =
                HostHandles.getter(extDClass, "b", INT_DESCRIPTOR, MethodType.methodType(int.class, Object.class));
        this.extDGetRow =
                HostHandles.getter(extDClass, "c", INT_DESCRIPTOR, MethodType.methodType(int.class, Object.class));
        this.extDGetNodes = HostHandles.getter(extDClass, "d", LIST_DESCRIPTOR, objUnary);

        this.extIGetParams = HostHandles.virtual(extIClass, "a", GET_LIST_DESCRIPTOR, objUnary);
        this.extKGetU = HostHandles.virtual(extKClass, "a", FLOAT_GET_DESCRIPTOR, floatUnary);
        this.extKGetV = HostHandles.virtual(extKClass, "b", FLOAT_GET_DESCRIPTOR, floatUnary);
        this.extKSetU = HostHandles.virtual(
                extKClass, "a", FLOAT_SET_DESCRIPTOR, MethodType.methodType(void.class, Object.class, float.class));
        this.extKSetV = HostHandles.virtual(
                extKClass, "b", FLOAT_SET_DESCRIPTOR, MethodType.methodType(void.class, Object.class, float.class));
        this.extKCtor = HostHandles.construct(
                extKClass, FLOAT2_INIT_DESCRIPTOR, MethodType.methodType(Object.class, float.class, float.class));
        this.extJCtor = HostHandles.construct(
                extJClass, EXT_J_INIT_DESCRIPTOR, MethodType.methodType(Object.class, Object.class, Object.class));
    }

    /** Loads extension handles on Cubism 5.3+ hosts or returns {@code null} when absent. */
    static ExtensionBezierSupport tryCreate(
            final ClassLoader loader,
            final Class<?> vec2Class,
            final MethodHandle vec2GetX,
            final MethodHandle vec2GetY)
            throws ReflectiveOperationException {
        final Class<?> cClass = tryLoad(loader, EXT_C_OWNER);
        final Class<?> dClass = tryLoad(loader, EXT_D_OWNER);
        final Class<?> iClass = tryLoad(loader, EXT_I_OWNER);
        final Class<?> jClass = tryLoad(loader, EXT_J_OWNER);
        final Class<?> kClass = tryLoad(loader, EXT_K_OWNER);
        if (cClass == null || dClass == null || iClass == null || jClass == null || kClass == null) {
            return null;
        }
        return new ExtensionBezierSupport(cClass, dClass, iClass, jClass, kClass, vec2Class, vec2GetX, vec2GetY);
    }

    void clearCaches() {
        scratch.remove();
    }

    List<Object> evaluateDragBatch(
            final Object oldGrid,
            final Object newGrid,
            final Object prevParamsObj,
            final Object curParamsObj,
            final int wCol,
            final int wRow,
            final float[] deformedPoints,
            final LongAdder counter,
            final LongAdder failures) {
        if (!extDClass.isInstance(oldGrid)
                || !extDClass.isInstance(newGrid)
                || !extIClass.isInstance(prevParamsObj)
                || !extIClass.isInstance(curParamsObj)
                || deformedPoints == null
                || wCol <= 0
                || wRow <= 0) {
            return null;
        }
        counter.increment();
        try {
            final int oldCol = (int) extDGetCol.invokeExact(oldGrid);
            final int oldRow = (int) extDGetRow.invokeExact(oldGrid);
            final int newCol = (int) extDGetCol.invokeExact(newGrid);
            final int newRow = (int) extDGetRow.invokeExact(newGrid);
            if (oldCol <= 0 || oldRow <= 0 || newCol <= 0 || newRow <= 0) {
                return null;
            }
            final Object oldNodesObj = (Object) extDGetNodes.invokeExact(oldGrid);
            final Object newNodesObj = (Object) extDGetNodes.invokeExact(newGrid);
            final Object prevListObj = (Object) extIGetParams.invokeExact(prevParamsObj);
            final Object curListObj = (Object) extIGetParams.invokeExact(curParamsObj);
            if (!(oldNodesObj instanceof List<?> oldNodes)
                    || !(newNodesObj instanceof List<?> newNodes)
                    || !(prevListObj instanceof List<?> prevList)
                    || !(curListObj instanceof List<?> curList)) {
                return null;
            }

            final int oldNodeCount = (oldCol + 1) * (oldRow + 1);
            final int newNodeCount = (newCol + 1) * (newRow + 1);
            final int nPts = (wCol + 1) * (wRow + 1);
            if (oldNodes.size() < oldNodeCount
                    || newNodes.size() < newNodeCount
                    || prevList.size() < nPts
                    || curList.size() < nPts
                    || deformedPoints.length < nPts * COORD_STRIDE) {
                return null;
            }

            final ScratchBuffers buf = scratch.get();
            buf.ensureGridNodes(oldNodeCount, newNodeCount);
            buf.ensurePoints(nPts);
            if (!unpackNodes(oldNodes, oldNodeCount, buf.oldGridPts)
                    || !unpackNodes(newNodes, newNodeCount, buf.newGridPts)) {
                return null;
            }

            final float[] prevParams = buf.prevParams;
            for (int i = 0; i < nPts; i++) {
                final Object pk = prevList.get(i);
                final Object ck = curList.get(i);
                if (!extKClass.isInstance(pk) || !extKClass.isInstance(ck)) {
                    return null;
                }
                final int pair = i * COORD_STRIDE;
                prevParams[pair] = (float) extKGetU.invokeExact(pk);
                prevParams[pair + 1] = (float) extKGetV.invokeExact(pk);
            }

            WarpDeformMath.evaluateExtensionDragBatch(
                    buf.oldGridPts,
                    oldCol,
                    oldRow,
                    buf.newGridPts,
                    newCol,
                    newRow,
                    prevParams,
                    wCol,
                    wRow,
                    deformedPoints,
                    buf.outParams,
                    buf.outPositions);

            final float[] outParams = buf.outParams;
            final float[] outPos = buf.outPositions;
            final ArrayList<Object> result = new ArrayList<>(nPts);
            for (int i = 0; i < nPts; i++) {
                final int pair = i * COORD_STRIDE;
                final Object ck = curList.get(i);
                extKSetU.invokeExact(ck, outParams[pair]);
                extKSetV.invokeExact(ck, outParams[pair + 1]);
                result.add((Object) vec2CtorFloats.invokeExact(outPos[pair], outPos[pair + 1]));
            }
            return result;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return null;
        }
    }

    Object evaluateCoonsPatch(
            final Object nw,
            final Object ne,
            final Object sw,
            final Object se,
            final float u,
            final float v,
            final LongAdder counter,
            final LongAdder failures) {
        if (!extCClass.isInstance(nw)
                || !extCClass.isInstance(ne)
                || !extCClass.isInstance(sw)
                || !extCClass.isInstance(se)) {
            return null;
        }
        counter.increment();
        try {
            final ScratchBuffers buf = scratch.get();
            final float[] patch = buf.patchPts;
            if (!unpackNode(nw, patch, 0)
                    || !unpackNode(ne, patch, NODE_FLOAT_STRIDE)
                    || !unpackNode(sw, patch, 2 * NODE_FLOAT_STRIDE)
                    || !unpackNode(se, patch, 3 * NODE_FLOAT_STRIDE)) {
                return null;
            }
            WarpDeformMath.evalCoonsPatch(patch, 0, 1, 2, 3, u, v, buf.evalOut);
            return (Object) vec2CtorFloats.invokeExact(buf.evalOut[0], buf.evalOut[1]);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return null;
        }
    }

    Object invertCoonsPatch(
            final Object nw,
            final Object ne,
            final Object sw,
            final Object se,
            final Object targetVec,
            final float u0,
            final float v0,
            final LongAdder counter,
            final LongAdder failures) {
        if (!extCClass.isInstance(nw)
                || !extCClass.isInstance(ne)
                || !extCClass.isInstance(sw)
                || !extCClass.isInstance(se)
                || targetVec == null) {
            return null;
        }
        counter.increment();
        try {
            final ScratchBuffers buf = scratch.get();
            final float[] patch = buf.patchPts;
            if (!unpackNode(nw, patch, 0)
                    || !unpackNode(ne, patch, NODE_FLOAT_STRIDE)
                    || !unpackNode(sw, patch, 2 * NODE_FLOAT_STRIDE)
                    || !unpackNode(se, patch, 3 * NODE_FLOAT_STRIDE)) {
                return null;
            }
            final float targetX = (float) vec2GetX.invokeExact(targetVec);
            final float targetY = (float) vec2GetY.invokeExact(targetVec);
            WarpDeformMath.invertCoonsPatch(patch, 0, 1, 2, 3, targetX, targetY, u0, v0, buf.uvResidual);
            final float[] uvRes = buf.uvResidual;
            final Object kObj = (Object) extKCtor.invokeExact(uvRes[0], uvRes[1]);
            final Object resVec = (Object) vec2CtorFloats.invokeExact(uvRes[2], uvRes[3]);
            return (Object) extJCtor.invokeExact(kObj, resVec);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return null;
        }
    }

    Object evaluateBicubicGrid(
            final float[] points,
            final int col,
            final int row,
            final float u,
            final float v,
            final LongAdder counter,
            final LongAdder failures) {
        if (points == null || col < 2 || row < 2) {
            return null;
        }
        final int needed = (col + 1) * (row + 1) * COORD_STRIDE;
        if (points.length < needed) {
            return null;
        }
        counter.increment();
        try {
            final ScratchBuffers buf = scratch.get();
            if (!WarpDeformMath.evalBicubicGrid(points, col, row, u, v, buf.evalOut)) {
                return null;
            }
            return (Object) vec2CtorFloats.invokeExact(buf.evalOut[0], buf.evalOut[1]);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return null;
        }
    }

    private boolean unpackNodes(final List<?> nodes, final int count, final float[] dst) throws Throwable {
        for (int i = 0; i < count; i++) {
            if (!unpackNode(nodes.get(i), dst, i * NODE_FLOAT_STRIDE)) {
                return false;
            }
        }
        return true;
    }

    private boolean unpackNode(final Object node, final float[] dst, final int off) throws Throwable {
        if (!extCClass.isInstance(node)) {
            return false;
        }
        final Object a = (Object) extCGetAnchor.invokeExact(node);
        final Object cn = (Object) extCGetCn.invokeExact(node);
        final Object cs = (Object) extCGetCs.invokeExact(node);
        final Object cw = (Object) extCGetCw.invokeExact(node);
        final Object ce = (Object) extCGetCe.invokeExact(node);
        if (a == null || cn == null || cs == null || cw == null || ce == null) {
            return false;
        }
        dst[off] = (float) vec2GetX.invokeExact(a);
        dst[off + 1] = (float) vec2GetY.invokeExact(a);
        dst[off + 2] = (float) vec2GetX.invokeExact(cn);
        dst[off + 3] = (float) vec2GetY.invokeExact(cn);
        dst[off + 4] = (float) vec2GetX.invokeExact(cs);
        dst[off + 5] = (float) vec2GetY.invokeExact(cs);
        dst[off + 6] = (float) vec2GetX.invokeExact(cw);
        dst[off + 7] = (float) vec2GetY.invokeExact(cw);
        dst[off + 8] = (float) vec2GetX.invokeExact(ce);
        dst[off + 9] = (float) vec2GetY.invokeExact(ce);
        return true;
    }

    private static Class<?> tryLoad(final ClassLoader loader, final String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false, loader);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    private static final class ScratchBuffers {
        float[] oldGridPts = new float[0];
        float[] newGridPts = new float[0];
        float[] prevParams = new float[0];
        float[] outParams = new float[0];
        float[] outPositions = new float[0];
        final float[] patchPts = new float[COONS_PATCH_NODES * NODE_FLOAT_STRIDE];
        final float[] evalOut = new float[COORD_STRIDE];
        final float[] uvResidual = new float[UV_RESIDUAL_FLOATS];

        void ensureGridNodes(final int oldNodes, final int newNodes) {
            final int oldLen = oldNodes * NODE_FLOAT_STRIDE;
            if (oldGridPts.length < oldLen) {
                oldGridPts = new float[oldLen];
            }
            final int newLen = newNodes * NODE_FLOAT_STRIDE;
            if (newGridPts.length < newLen) {
                newGridPts = new float[newLen];
            }
        }

        void ensurePoints(final int nPts) {
            final int len = nPts * COORD_STRIDE;
            if (prevParams.length < len) {
                prevParams = new float[len];
                outParams = new float[len];
                outPositions = new float[len];
            }
        }
    }
}
