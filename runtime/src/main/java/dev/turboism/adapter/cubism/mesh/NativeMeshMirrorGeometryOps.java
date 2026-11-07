package dev.turboism.adapter.cubism.mesh;

import static dev.turboism.adapter.cubism.mesh.NativeMeshMirrorReflectionOps.*;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Editable-mesh geometry primitives for the mesh-mirror bridge: point and edge creation, point
 * movement, the undo envelope a direct mutation needs, and the snapshot/readback helpers the
 * bridge revalidates contributions against.
 *
 * <p>Everything here talks to the host through {@link NativeMeshMirrorReflectionOps} and fails
 * closed: a missing host member throws at the call site, and a best-effort step that may not
 * exist on every reviewed generation is contained instead of aborting the edit.
 */
final class NativeMeshMirrorGeometryOps {
    // ------------------------------------------------------------------
    // Point and edge creation.
    // ------------------------------------------------------------------

    static boolean canAddPoint(final Object mesh) {
        return addPointMethod(mesh) != null;
    }

    static int addPoint(final Object mesh, final float x, final float y) throws ReflectiveOperationException {
        final Method method = addPointMethod(mesh);
        if (method == null) throw new NoSuchMethodException("mesh point addition");
        final Object normal = enumConstant(method.getParameterTypes()[2], "NORMAL");
        if (normal == null) throw new NoSuchFieldException("NORMAL point type");
        final Object result = method.invoke(mesh, x, y, normal, -1L);
        return result instanceof Number number ? number.intValue() : -1;
    }

    static List<Integer> addClosedLoopPointsAndEdges(
            final Object editMode,
            final Object editableMesh,
            final Object completePack,
            final String label,
            final float[] pointsXY)
            throws ReflectiveOperationException {
        if (pointsXY == null || pointsXY.length < 6 || (pointsXY.length & 1) != 0) {
            throw new IllegalArgumentException("closed loop requires at least three finite XY point pairs");
        }
        final int count = pointsXY.length / 2;
        final int[] edgePairs = new int[count * 2];
        for (int index = 0; index < count; index++) {
            edgePairs[index * 2] = index;
            edgePairs[index * 2 + 1] = (index + 1) % count;
        }
        return addGeometricPointsAndEdges(editMode, editableMesh, completePack, label, pointsXY, edgePairs);
    }

    static List<Integer> addGeometricPointsAndEdges(
            final Object editMode,
            final Object editableMesh,
            final Object completePack,
            final String label,
            final float[] pointsXY,
            final int[] edgePairs)
            throws ReflectiveOperationException {
        if (editableMesh == null || pointsXY == null || pointsXY.length < 6 || (pointsXY.length & 1) != 0) {
            throw new IllegalArgumentException("geometric layout requires at least three finite XY point pairs");
        }
        if (edgePairs == null || (edgePairs.length & 1) != 0) {
            throw new IllegalArgumentException("geometric layout edge pairs must have even length");
        }
        if (!canAddPoint(editableMesh)) {
            throw new NoSuchMethodException("mesh point addition");
        }
        Object undoOwner = null;
        boolean started = false;
        try {
            Object actionPack = NativeMeshMirrorBridge.activeMeshActionPack();
            if (actionPack == null && completePack != null) {
                actionPack = resolveActionPackFromCompletePack(completePack, editMode);
            }
            if (actionPack != null && NativeMeshMirrorBridge.actionPackOwnsEditMode(actionPack, editMode)) {
                final Object group = NativeMeshMirrorBridge.beginUndoGroup(actionPack, label);
                if (group != null) {
                    undoOwner = actionPack;
                    started = true;
                    NativeMeshMirrorBridge.snapshotMeshesForUndo(actionPack, label, group);
                }
            }
            if (!started && editMode != null) {
                final Object group = NativeMeshMirrorBridge.beginUndoGroup(editMode, label);
                if (group != null) {
                    undoOwner = editMode;
                    started = true;
                    snapshotMeshOnEditMode(editMode, editableMesh, label, group);
                }
            }
            final int count = pointsXY.length / 2;
            removeCrossingInnerEdges(editableMesh, pointsXY, edgePairs);
            try {
                call(editableMesh, "clearAutoTriangulation", NO_PARAMS);
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
            }
            final List<Integer> addedIds = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                final int id = addPoint(editableMesh, pointsXY[index * 2], pointsXY[index * 2 + 1]);
                if (id < 0) {
                    throw new IllegalStateException("host did not return a valid point index");
                }
                addedIds.add(id);
            }
            for (int index = 0; index + 1 < edgePairs.length; index += 2) {
                final int u = edgePairs[index];
                final int v = edgePairs[index + 1];
                if (u >= 0 && u < count && v >= 0 && v < count && u != v) {
                    final int startId = addedIds.get(u);
                    final int endId = addedIds.get(v);
                    if (startId != endId) {
                        final MeshEdgeRef edge =
                                new MeshEdgeRef(startId, endId, dev.turboism.sdk.cubism.mesh.MeshEdgeKind.INNER);
                        if (canAddEdge(editableMesh, edge)) {
                            addEdge(editableMesh, edge);
                        }
                    }
                }
            }
            refreshTriangulationAndCaches(editMode, editableMesh);
            if (started && undoOwner != null) {
                NativeMeshMirrorBridge.commitUndoGroup(undoOwner);
                started = false;
            }
            disarmNativePointAddPreview(editableMesh.getClass().getClassLoader());
            if (completePack != null) {
                try {
                    invoke(completePack, "repaintCanvas", true);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Best-effort canvas refresh.
                }
            }
            return List.copyOf(addedIds);
        } catch (ReflectiveOperationException | RuntimeException | Error failure) {
            FatalErrors.rethrowIfFatal(failure);
            if (started && undoOwner != null) {
                try {
                    NativeMeshMirrorBridge.cancelUndoGroup(undoOwner);
                } catch (Throwable ignored) {
                    FatalErrors.rethrowIfFatal(ignored);
                }
            }
            throw failure;
        }
    }

    private static void snapshotMeshOnEditMode(
            final Object editMode, final Object editableMesh, final String label, final Object group)
            throws ReflectiveOperationException {
        if ("com.live2d.graphics3d.editableMesh.GEditableMesh2"
                .equals(editableMesh.getClass().getName())) {
            PopupMeshUndoContract.snapshotMeshes(
                    editMode.getClass().getClassLoader(), label, group, List.of(editableMesh));
        }
    }

    private static Object resolveActionPackFromCompletePack(final Object completePack, final Object editMode) {
        try {
            final Object viewContext = property(completePack, "currentViewContext");
            if (viewContext == null) return null;
            final Object cached = invoke(viewContext, "getLastActionPack");
            if (NativeMeshMirrorBridge.actionPackOwnsEditMode(cached, editMode)) return cached;
            final Object refreshed = PopupMeshUndoContract.refresh(viewContext, cached);
            return NativeMeshMirrorBridge.actionPackOwnsEditMode(refreshed, editMode) ? refreshed : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    static void disarmNativePointAddPreview(final ClassLoader loader) {
        if (loader == null) return;
        try {
            final Class<?> toolModeClass = Class.forName(
                    "com.live2d.cubism.view.palette.tool.toolMode.meshEditor.ToolMode_MeshEdit_Manual", false, loader);
            java.lang.reflect.Field subToolField = null;
            try {
                subToolField = toolModeClass.getDeclaredField("currentMeshEditSubTool");
            } catch (NoSuchFieldException ignored) {
                for (java.lang.reflect.Field candidate : toolModeClass.getDeclaredFields()) {
                    if (Modifier.isStatic(candidate.getModifiers())
                            && candidate.getType().isEnum()) {
                        subToolField = candidate;
                        break;
                    }
                }
            }
            if (subToolField == null) return;
            ReflectAccess.open(subToolField);
            final Class<?> subToolEnum = subToolField.getType();
            Object arrow = enumConstant(subToolEnum, "ARROW");
            if (arrow == null) {
                arrow = enumConstant(subToolEnum, "a");
            }
            if (arrow != null) {
                subToolField.set(null, arrow);
            }
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
    }

    private static void removeCrossingInnerEdges(
            final Object editableMesh, final float[] pointsXY, final int[] edgePairs) {
        try {
            final Object rawPositions = call(editableMesh, "getGlPositions", NO_PARAMS);
            if (!(rawPositions instanceof float[] existingXY) || existingXY.length < 4) return;
            final int pointCount = existingXY.length / 2;
            final int newPointCount = pointsXY.length / 2;
            final List<int[]> toRemove = new ArrayList<>();
            for (Object edge : edges(editableMesh)) {
                final Object type = call(edge, "getType", NO_PARAMS);
                if (type instanceof Enum<?> e && "LOCKED".equals(e.name())) {
                    continue;
                }
                final Object first = call(edge, "getIndex1", NO_PARAMS);
                final Object second = call(edge, "getIndex2", NO_PARAMS);
                if (!(first instanceof Number s) || !(second instanceof Number t)) continue;
                final int i1 = s.intValue();
                final int i2 = t.intValue();
                if (i1 < 0 || i1 >= pointCount || i2 < 0 || i2 >= pointCount) continue;
                final float p1x = existingXY[i1 * 2];
                final float p1y = existingXY[i1 * 2 + 1];
                final float p2x = existingXY[i2 * 2];
                final float p2y = existingXY[i2 * 2 + 1];
                boolean crosses = false;
                for (int k = 0; k + 1 < edgePairs.length; k += 2) {
                    final int u = edgePairs[k];
                    final int v = edgePairs[k + 1];
                    if (u < 0 || u >= newPointCount || v < 0 || v >= newPointCount) continue;
                    final float q1x = pointsXY[u * 2];
                    final float q1y = pointsXY[u * 2 + 1];
                    final float q2x = pointsXY[v * 2];
                    final float q2y = pointsXY[v * 2 + 1];
                    if (segmentsIntersect(p1x, p1y, p2x, p2y, q1x, q1y, q2x, q2y)) {
                        crosses = true;
                        break;
                    }
                }
                if (crosses) {
                    toRemove.add(new int[] {i1, i2});
                }
            }
            if (!toRemove.isEmpty()) {
                final Method removeEdge = declaredMethod(editableMesh.getClass(), "removeEdge", int.class, int.class);
                if (removeEdge != null) {
                    for (int[] pair : toRemove) {
                        try {
                            removeEdge.invoke(editableMesh, pair[0], pair[1]);
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
    }

    static boolean segmentsIntersect(
            final float ax,
            final float ay,
            final float bx,
            final float by,
            final float cx,
            final float cy,
            final float dx,
            final float dy) {
        final float d1 = cross(cx, cy, dx, dy, ax, ay);
        final float d2 = cross(cx, cy, dx, dy, bx, by);
        final float d3 = cross(ax, ay, bx, by, cx, cy);
        final float d4 = cross(ax, ay, bx, by, dx, dy);
        return ((d1 > 0.0f && d2 < 0.0f) || (d1 < 0.0f && d2 > 0.0f))
                && ((d3 > 0.0f && d4 < 0.0f) || (d3 < 0.0f && d4 > 0.0f));
    }

    private static float cross(
            final float ax, final float ay, final float bx, final float by, final float cx, final float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private static void refreshTriangulationAndCaches(final Object editMode, final Object editableMesh) {
        try {
            call(editableMesh, "setUpdated", NO_PARAMS);
            call(editableMesh, "getGlPositions", NO_PARAMS);
            for (Method method : MethodHandleCache.overloads(editableMesh.getClass(), "getGlIndices$default", 5)) {
                if (Modifier.isStatic(method.getModifiers())) {
                    method.invoke(null, editableMesh, null, false, 3, null);
                    break;
                }
            }
            call(editableMesh, "getLockedEdges", NO_PARAMS);
            call(editableMesh, "getNormalEdges", NO_PARAMS);
            call(editableMesh, "getUserTriangulationEdges", NO_PARAMS);
            call(editableMesh, "getAutoTriangulationEdges", NO_PARAMS);
            markSelectionBoundingBoxDirty(editMode, null);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
    }

    static void markSelectionBoundingBoxDirty(final Object editMode, final Object completePack) {
        try {
            if (editMode != null) {
                final Object selector = call(editMode, "getSelector", NO_PARAMS);
                final Object boundingBox = selector == null ? null : call(selector, "getBoundingBox", NO_PARAMS);
                if (boundingBox != null) {
                    call(boundingBox, "setDirty", NO_PARAMS);
                }
            }
            if (completePack != null) {
                invoke(completePack, "repaintCanvas", true);
            }
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
    }

    private static Method addPointMethod(final Object mesh) {
        for (Method method : MethodHandleCache.overloads(mesh.getClass(), "addPoint", 4)) {
            final Class<?>[] parameters = method.getParameterTypes();
            if (parameters[0] == float.class
                    && parameters[1] == float.class
                    && parameters[3] == long.class
                    && enumConstant(parameters[2], "NORMAL") != null) {
                return method;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Point movement.
    // ------------------------------------------------------------------

    static boolean canMovePoint(final Object point) throws ReflectiveOperationException {
        final Object current = call(point, "getPos", NO_PARAMS);
        if (current == null) return false;
        final Method move = movePointMethod(point.getClass(), current.getClass());
        return move != null && vectorConstructor(move.getParameterTypes()[0]) != null;
    }

    static void movePoint(final Object point, final float x, final float y) throws ReflectiveOperationException {
        final Object current = call(point, "getPos", NO_PARAMS);
        if (current == null) throw new NoSuchMethodException("mesh point position");
        final Method move = movePointMethod(point.getClass(), current.getClass());
        if (move == null) throw new NoSuchMethodException("mesh point movement");
        // Native getPos() returns MPoint2, but moveToOnLocal accepts GVector2.
        final Constructor<?> vector = vectorConstructor(move.getParameterTypes()[0]);
        if (vector == null) throw new NoSuchMethodException("mesh point vector construction");
        move.invoke(point, vector.newInstance(x, y), 1.0f);
    }

    static boolean canAddEdge(final Object mesh, final MeshEdgeRef edge) {
        return addEdgeMethod(mesh, edge) != null;
    }

    static void addEdge(final Object mesh, final MeshEdgeRef edge) throws ReflectiveOperationException {
        final Method method = addEdgeMethod(mesh, edge);
        if (method == null) throw new NoSuchMethodException("mesh edge addition");
        final Object type = edgeType(method, edge);
        final int before = edgeCount(mesh);
        method.invoke(mesh, edge.startPointId(), edge.endPointId(), type, false, false);
        if (edgeCount(mesh) != before + 1) throw new IllegalStateException("host added no edge");
    }

    private static Method addEdgeMethod(final Object mesh, final MeshEdgeRef edge) {
        if (edge.kind() == dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN) return null;
        for (Method method : MethodHandleCache.overloads(mesh.getClass(), "addEdge", 5)) {
            final Class<?>[] parameters = method.getParameterTypes();
            if (parameters[0] == int.class
                    && parameters[1] == int.class
                    && parameters[3] == boolean.class
                    && parameters[4] == boolean.class
                    && edgeType(method, edge) != null) return method;
        }
        return null;
    }

    private static Object edgeType(final Method method, final MeshEdgeRef edge) {
        return enumConstant(
                method.getParameterTypes()[2],
                switch (edge.kind()) {
                    case BORDER -> "LOCKED";
                    case INNER -> "NORMAL";
                    case UNKNOWN -> "";
                });
    }

    private static int edgeCount(final Object mesh) throws ReflectiveOperationException {
        final Object value = call(mesh, "getEdges", NO_PARAMS);
        return value instanceof Collection<?> edges ? edges.size() : -1;
    }

    // ------------------------------------------------------------------
    // Snapshot readback, and edge removal that attaches to the caller's own undo group.
    // ------------------------------------------------------------------

    static void collectSnapshot(final Object mesh, final List<MeshPointRef> points, final List<MeshEdgeRef> edges)
            throws ReflectiveOperationException {
        for (Object point : points(mesh)) {
            final int id = pointId(point);
            if (id < 0) continue;
            final Object position = call(point, "getPos", NO_PARAMS);
            final Object x = position == null ? null : call(position, "getX", NO_PARAMS);
            final Object y = position == null ? null : call(position, "getY", NO_PARAMS);
            if (x instanceof Number first && y instanceof Number second) {
                points.add(new MeshPointRef(id, first.floatValue(), second.floatValue()));
            }
        }
        final Object hostEdges = call(mesh, "getEdges", NO_PARAMS);
        if (!(hostEdges instanceof Iterable<?> iterable)) return;
        for (Object edge : iterable) {
            final Object first = call(edge, "getIndex1", NO_PARAMS);
            final Object second = call(edge, "getIndex2", NO_PARAMS);
            if (first instanceof Number start && second instanceof Number end && start.intValue() != end.intValue()) {
                final Object type = call(edge, "getType", NO_PARAMS);
                final dev.turboism.sdk.cubism.mesh.MeshEdgeKind kind = type instanceof Enum<?> value
                        ? switch (value.name()) {
                            case "LOCKED" -> dev.turboism.sdk.cubism.mesh.MeshEdgeKind.BORDER;
                            case "NORMAL" -> dev.turboism.sdk.cubism.mesh.MeshEdgeKind.INNER;
                            default -> dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN;
                        }
                        : dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN;
                edges.add(new MeshEdgeRef(start.intValue(), end.intValue(), kind));
            }
        }
    }

    static int countLiveEdges(final Object mesh, final List<MeshEdgeRef> refs) throws ReflectiveOperationException {
        final Object hostEdges = call(mesh, "getEdges", NO_PARAMS);
        if (!(hostEdges instanceof Iterable<?> iterable)) return 0;
        int count = 0;
        for (Object candidate : iterable) {
            final Object first = call(candidate, "getIndex1", NO_PARAMS);
            final Object second = call(candidate, "getIndex2", NO_PARAMS);
            if (!(first instanceof Number start) || !(second instanceof Number end)) continue;
            final int low = Math.min(start.intValue(), end.intValue());
            final int high = Math.max(start.intValue(), end.intValue());
            for (MeshEdgeRef ref : refs) {
                if (ref.startPointId() == low && ref.endPointId() == high) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** Removes the referenced edges through the host's undo-aware handler; returns how many. */
    static int removeEdgesInto(final Object mesh, final List<MeshEdgeRef> refs, final Object groupUndo)
            throws ReflectiveOperationException {
        final Object hostEdges = call(mesh, "getEdges", NO_PARAMS);
        if (!(hostEdges instanceof Iterable<?> iterable)) return 0;
        final List<Object> live = new ArrayList<>();
        for (Object candidate : iterable) {
            final Object first = call(candidate, "getIndex1", NO_PARAMS);
            final Object second = call(candidate, "getIndex2", NO_PARAMS);
            if (!(first instanceof Number start) || !(second instanceof Number end)) continue;
            final int low = Math.min(start.intValue(), end.intValue());
            final int high = Math.max(start.intValue(), end.intValue());
            for (MeshEdgeRef ref : refs) {
                if (ref.startPointId() == low && ref.endPointId() == high) {
                    if (!containsSame(live, candidate)) live.add(candidate);
                    break;
                }
            }
        }
        if (live.isEmpty()) return 0;
        final Object handler = call(mesh, "getHandler", NO_PARAMS);
        if (handler == null) return 0;
        final Method remove = declaredMethod(handler.getClass(), "a", List.class);
        if (remove == null || !hasUndoAttachmentMethod(groupUndo)) return 0;
        final Object undo = remove.invoke(handler, live);
        if (undo == null) return 0;
        final Method plusAssign = declaredMethod(groupUndo.getClass(), "plusAssign", undo.getClass());
        if (plusAssign == null) throw new NoSuchMethodException("mesh edge undo attachment");
        plusAssign.invoke(groupUndo, undo);
        return live.size();
    }
}
