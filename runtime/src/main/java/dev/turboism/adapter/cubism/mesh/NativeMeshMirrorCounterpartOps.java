package dev.turboism.adapter.cubism.mesh;

import static dev.turboism.adapter.cubism.mesh.NativeMeshMirrorReflectionOps.*;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves the mirror counterpart of a host mesh point or edge, and owns the host mirror
 * singleton the resolution runs against.
 *
 * <p>The rule is the one the newer reviewed generation implements natively: take the source
 * position into the context's mirror space, mirror it with the host's own axis, bring it back,
 * then accept the nearest existing element only inside the host's own tolerance. Anything that
 * cannot be resolved exactly returns {@code null} so the caller leaves native behaviour intact.
 */
final class NativeMeshMirrorCounterpartOps {
    private static final String MIRROR_OWNER = "com.live2d.cubism.view.palette.tool.toolMode.meshEditor.g";

    private static final AtomicReference<Object> MIRROR_OVERRIDE = new AtomicReference<>();

    /** Clears the test override; called when the bridge is revoked. */
    static void resetMirrorOverride() {
        MIRROR_OVERRIDE.set(null);
    }

    /**
     * The host's mirror singleton. Note this is the same object whose {@code a(GVector2)}
     * the mirror transformer already rewrites, so the counterpart positions we compute here
     * automatically follow the rotated axis rather than the host's unrotated one.
     */
    static Object hostMirror(final Object pack) {
        final Object override = MIRROR_OVERRIDE.get();
        if (override != null) return override;
        try {
            return Class.forName(MIRROR_OWNER, false, pack.getClass().getClassLoader())
                    .getField("a")
                    .get(null);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return null;
        }
    }

    /** Test seam: the exact host mirror class cannot exist on an offline classpath. */
    static void mirrorForTesting(final Object mirror) {
        MIRROR_OVERRIDE.set(mirror);
    }

    /** Uses the host's own enable predicate; we never invent one. */
    static boolean hostMirrorEnabled(final Object mirror, final Object pack) {
        try {
            final Object editMode = call(pack, "aP", NO_PARAMS);
            if (editMode != null) {
                for (Method method : MethodHandleCache.overloads(mirror.getClass(), "a", 1)) {
                    if (method.getReturnType() != boolean.class) continue;
                    if (!method.getParameterTypes()[0].isInstance(editMode)) continue;
                    return Boolean.TRUE.equals(method.invoke(mirror, editMode));
                }
            }
            final Method noArg = declaredMethod(mirror.getClass(), "a");
            return noArg != null && noArg.getReturnType() == boolean.class && Boolean.TRUE.equals(noArg.invoke(mirror));
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    static Object compatiblePoint(final Object mesh, final Object reference, final MeshFrameIndex index)
            throws ReflectiveOperationException {
        if (reference == null) return null;
        for (Method method : MethodHandleCache.overloads(mesh.getClass(), "getCompatiblePointRef", 1)) {
            if (!method.getParameterTypes()[0].isInstance(reference)) continue;
            return method.invoke(mesh, reference);
        }
        return index.pointIdentity().contains(reference) ? reference : null;
    }

    static float selectionWeight(final Object selector, final Object reference) throws ReflectiveOperationException {
        for (Method method : MethodHandleCache.overloads(selector.getClass(), "getWeight", 2)) {
            final Class<?>[] parameters = method.getParameterTypes();
            if (!parameters[0].isInstance(reference) || parameters[1] != float.class) continue;
            final Object value = method.invoke(selector, reference, 0.0f);
            return value instanceof Number number ? number.floatValue() : 0.0f;
        }
        throw new NoSuchMethodException("mesh point selection weight");
    }

    static Object mirrorPoint(final Object context, final Object mirror, final Object point)
            throws ReflectiveOperationException {
        if (point == null) return null;
        final Class<?> vector = point.getClass();
        final Object local = call(context, "b", new Class<?>[] {vector}, point);
        final Object mirrored = local == null ? null : call(mirror, "a", new Class<?>[] {vector}, local);
        return mirrored == null ? null : call(context, "a", new Class<?>[] {vector}, mirrored);
    }

    /**
     * The counterpart rule copied from 5.3.02: take the source position into the
     * context's mirror space, mirror it with the host's own axis, bring it back,
     * then accept the nearest existing point only inside the host's tolerance.
     */
    static Object counterpartPoint(
            final Object mirror, final Object source, final Object mesh, final Object pack, final Object context)
            throws ReflectiveOperationException {
        final Object scale = call(pack, "aL", NO_PARAMS);
        if (!(scale instanceof Number number)) return null;
        return counterpartPoint(mirror, source, mesh, pack, context, number.floatValue(), new MeshFrameIndex(mesh));
    }

    static Object counterpartPoint(
            final Object mirror,
            final Object source,
            final Object mesh,
            final Object pack,
            final Object context,
            final float tolerance,
            final MeshFrameIndex index)
            throws ReflectiveOperationException {
        final Object position = call(source, "getPos", NO_PARAMS);
        final Object target = mirrorPoint(context, mirror, position);
        if (target == null) return null;
        final Class<?> vector = target.getClass();

        return MeshMirrorVertexResolver.nearest(index.points(), tolerance, candidate -> {
            final Object candidatePosition = call(candidate, "getPos", NO_PARAMS);
            if (candidatePosition == null) return Double.NaN;
            final Object distance = call(candidatePosition, "distance", new Class<?>[] {vector}, target);
            return distance instanceof Number number ? number.doubleValue() : Double.NaN;
        });
    }

    /** Edge counterpart: mirror both endpoints, rebuild the edge id-ordered, keep it only if it exists. */
    static Object counterpartEdge(
            final Object mirror, final Object edge, final Object mesh, final Object pack, final Object context)
            throws ReflectiveOperationException {
        final Object scale = call(pack, "aL", NO_PARAMS);
        if (!(scale instanceof Number number)) return null;
        return counterpartEdge(mirror, edge, mesh, pack, context, number.floatValue(), new MeshFrameIndex(mesh));
    }

    static Object counterpartEdge(
            final Object mirror,
            final Object edge,
            final Object mesh,
            final Object pack,
            final Object context,
            final float tolerance,
            final MeshFrameIndex index)
            throws ReflectiveOperationException {
        final Object firstIndex = call(edge, "getIndex1", NO_PARAMS);
        final Object secondIndex = call(edge, "getIndex2", NO_PARAMS);
        if (!(firstIndex instanceof Number first) || !(secondIndex instanceof Number second)) return null;
        final Object start = index.pointsById().get(first.intValue());
        final Object end = index.pointsById().get(second.intValue());
        if (start == null || end == null) return null;
        final Object mirroredStart = counterpartPoint(mirror, start, mesh, pack, context, tolerance, index);
        final Object mirroredEnd = counterpartPoint(mirror, end, mesh, pack, context, tolerance, index);
        if (mirroredStart == null || mirroredEnd == null) return null;
        final int startId = pointId(mirroredStart);
        final int endId = pointId(mirroredEnd);
        if (startId == endId) return null;

        final Object type = call(edge, "getType", NO_PARAMS);
        final Constructor<?> constructor = edgeConstructor(edge.getClass(), type);
        if (constructor == null) return null;
        final Object rebuilt = startId < endId
                ? constructor.newInstance(startId, endId, type)
                : constructor.newInstance(endId, startId, type);
        final Object edges = call(mesh, "getEdges", NO_PARAMS);
        if (!(edges instanceof Collection<?> collection)) return null;
        for (Object candidate : collection) if (rebuilt.equals(candidate)) return candidate;
        return null;
    }

    private static Constructor<?> edgeConstructor(final Class<?> sourceType, final Object edgeType) {
        final Class<?> edgeClass = edgeType == null ? null : edgeType.getClass().getEnclosingClass();
        for (Class<?> current = sourceType; current != null; current = current.getSuperclass()) {
            if (edgeClass != null && current == edgeClass) break;
            final Constructor<?> constructor = threeArgumentEdgeConstructor(current);
            if (constructor != null) return constructor;
        }
        return edgeClass == null ? null : threeArgumentEdgeConstructor(edgeClass);
    }

    private static Constructor<?> threeArgumentEdgeConstructor(final Class<?> type) {
        for (Constructor<?> candidate : type.getDeclaredConstructors()) {
            final Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length != 3 || parameters[0] != int.class || parameters[1] != int.class) {
                continue;
            }
            try {
                ReflectAccess.open(candidate);
                return candidate;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }
}
