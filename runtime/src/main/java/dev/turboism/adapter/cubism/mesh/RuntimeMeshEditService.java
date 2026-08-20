package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshEditResult;
import dev.turboism.sdk.cubism.mesh.MeshEditService;
import dev.turboism.sdk.cubism.mesh.MeshPointPosition;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import dev.turboism.sdk.cubism.mesh.MeshSnapshot;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Plugin-initiated mesh authoring, each call its own undoable step.
 *
 * <p>Direct mutations use the host's own whole-mesh snapshot helper before touching geometry;
 * native delete handlers keep their narrower undo objects. Everything is revalidated against the
 * live mesh first. Until the SDK carries a mesh identity, reads and writes fail closed unless
 * exactly one editable mesh is active, so equal point ids in different meshes can never be
 * guessed.</p>
 */
public final class RuntimeMeshEditService implements MeshEditService {

    @Override
    public MeshSnapshot snapshot() {
        return onEdt(() -> {
            try {
                final Object editMode = NativeMeshMirrorBridge.activeMeshEditMode();
                if (editMode == null) return MeshSnapshot.empty();
                final List<Object> meshes = meshes(editMode);
                if (meshes.size() != 1) return MeshSnapshot.empty();
                final List<MeshPointRef> points = new ArrayList<>();
                final List<MeshEdgeRef> edges = new ArrayList<>();
                NativeMeshMirrorBridge.collectSnapshot(meshes.get(0), points, edges);
                return new MeshSnapshot(points, edges);
            } catch (Throwable failure) {
                NativeMeshMirrorBridge.diagnostic("MESH_EDIT_SNAPSHOT_FAILED reason=" + failure.getClass().getName());
                return MeshSnapshot.empty();
            }
        });
    }

    @Override
    public MeshEditResult addPoints(final List<MeshPointPosition> points) {
        if (NativeMeshMirrorBridge.participationDispatchActive()) {
            return MeshEditResult.refused("direct mutation is unavailable during mesh edit participation");
        }
        if (points == null || points.isEmpty()) return MeshEditResult.applied();
        return mutate("Add Points", "MESH_EDIT_ADD_POINTS_FAILED", mesh -> {
            final List<String> rejected = new ArrayList<>();
            final List<MeshPointPosition> prepared = new ArrayList<>();
            for (MeshPointPosition point : points) {
                if (point == null) rejected.add("point position is null");
                else prepared.add(point);
            }
            if (!NativeMeshMirrorBridge.canAddPoint(mesh)) {
                return Preparation.refused("the host exposes no point addition");
            }
            if (prepared.isEmpty()) return Preparation.refused("no valid point position was supplied");
            return Preparation.ready(result(rejected), () -> {
                for (MeshPointPosition point : prepared) {
                    NativeMeshMirrorBridge.addPoint(mesh, point.x(), point.y());
                }
            });
        });
    }

    @Override
    public MeshEditResult deletePoints(final List<MeshPointRef> points) {
        if (NativeMeshMirrorBridge.participationDispatchActive()) {
            return MeshEditResult.refused("direct mutation is unavailable during mesh edit participation");
        }
        if (points == null || points.isEmpty()) return MeshEditResult.applied();
        return onEdt(() -> {
            Object editMode = null;
            boolean started = false;
            try {
                editMode = NativeMeshMirrorBridge.activeMeshEditMode();
                if (editMode == null) return MeshEditResult.refused("no mesh edit is active");
                final List<Object> meshes = meshes(editMode);
                if (meshes.size() != 1) {
                    return MeshEditResult.refused("point deletion requires exactly one active mesh");
                }
                final List<String> rejected = new ArrayList<>();
                final List<Object> live = new ArrayList<>();
                final Set<Integer> seen = new HashSet<>();
                for (MeshPointRef ref : points) {
                    if (ref == null) {
                        rejected.add("point reference is null");
                        continue;
                    }
                    if (!seen.add(ref.id())) {
                        rejected.add("point " + ref.id() + " is duplicated");
                        continue;
                    }
                    final Object point = NativeMeshMirrorBridge.pointById(meshes.get(0), ref.id());
                    if (point == null) rejected.add("point " + ref.id() + " is not in the live mesh");
                    else live.add(point);
                }
                if (live.isEmpty()) return MeshEditResult.refused("no referenced point is in the live mesh");
                final Object group = NativeMeshMirrorBridge.beginUndoGroup(editMode, "Delete Points");
                if (group == null) return MeshEditResult.refused("the host refused an undo group");
                started = true;
                final Method delete = NativeMeshMirrorBridge.declaredMethod(
                    editMode.getClass(), "delete_exe", List.class, group.getClass()
                );
                if (delete == null) {
                    final String rollback = rollback(editMode, "MESH_EDIT_DELETE_POINTS_FAILED");
                    started = false;
                    return rollback == null
                        ? MeshEditResult.refused("the host exposes no point deletion")
                        : MeshEditResult.refused("the host exposes no point deletion; " + rollback);
                }
                delete.invoke(editMode, List.of(live), group);
                NativeMeshMirrorBridge.commitUndoGroup(editMode);
                started = false;
                return result(rejected);
            } catch (Throwable failure) {
                final String rollback = started && editMode != null
                    ? rollback(editMode, "MESH_EDIT_DELETE_POINTS_FAILED")
                    : null;
                NativeMeshMirrorBridge.diagnostic("MESH_EDIT_DELETE_POINTS_FAILED reason=" + failure.getClass().getName());
                return refused(failure, rollback);
            }
        });
    }

    @Override
    public MeshEditResult movePoints(final List<MeshPointRef> points) {
        if (NativeMeshMirrorBridge.participationDispatchActive()) {
            return MeshEditResult.refused("direct mutation is unavailable during mesh edit participation");
        }
        if (points == null || points.isEmpty()) return MeshEditResult.applied();
        return mutate("Move Points", "MESH_EDIT_MOVE_POINTS_FAILED", mesh -> {
            final Map<Integer, Integer> counts = new HashMap<>();
            for (MeshPointRef ref : points) if (ref != null) counts.merge(ref.id(), 1, Integer::sum);
            final Set<Integer> reportedDuplicates = new HashSet<>();
            final List<String> rejected = new ArrayList<>();
            final List<PointMove> prepared = new ArrayList<>();
            for (MeshPointRef ref : points) {
                if (ref == null) {
                    rejected.add("point reference is null");
                    continue;
                }
                if (counts.get(ref.id()) > 1) {
                    if (reportedDuplicates.add(ref.id())) rejected.add("point " + ref.id() + " is duplicated");
                    continue;
                }
                final Object point = NativeMeshMirrorBridge.pointById(mesh, ref.id());
                if (point == null) {
                    rejected.add("point " + ref.id() + " is not in the live mesh");
                } else if (!NativeMeshMirrorBridge.canMovePoint(point)) {
                    rejected.add("point " + ref.id() + " cannot be moved by this host");
                } else {
                    prepared.add(new PointMove(point, ref.x(), ref.y()));
                }
            }
            if (prepared.isEmpty()) return Preparation.refused("no referenced point can be moved");
            return Preparation.ready(result(rejected), () -> {
                for (PointMove move : prepared) {
                    NativeMeshMirrorBridge.movePoint(move.point, move.x, move.y);
                }
            });
        });
    }

    @Override
    public MeshEditResult addEdges(final List<MeshEdgeRef> edges) {
        if (NativeMeshMirrorBridge.participationDispatchActive()) {
            return MeshEditResult.refused("direct mutation is unavailable during mesh edit participation");
        }
        if (edges == null || edges.isEmpty()) return MeshEditResult.applied();
        return mutate("Add Edges", "MESH_EDIT_ADD_EDGES_FAILED", mesh -> {
            final Map<EdgeKey, Integer> counts = new HashMap<>();
            for (MeshEdgeRef ref : edges) if (ref != null) counts.merge(EdgeKey.of(ref), 1, Integer::sum);
            final Set<EdgeKey> reportedDuplicates = new HashSet<>();
            final List<String> rejected = new ArrayList<>();
            final List<MeshEdgeRef> prepared = new ArrayList<>();
            for (MeshEdgeRef ref : edges) {
                if (ref == null) {
                    rejected.add("edge reference is null");
                    continue;
                }
                final EdgeKey key = EdgeKey.of(ref);
                if (counts.get(key) > 1) {
                    if (reportedDuplicates.add(key)) rejected.add("edge " + key + " is duplicated");
                    continue;
                }
                if (NativeMeshMirrorBridge.pointById(mesh, ref.startPointId()) == null
                    || NativeMeshMirrorBridge.pointById(mesh, ref.endPointId()) == null) {
                    rejected.add("edge " + key + " does not name two live points");
                } else if (NativeMeshMirrorBridge.countLiveEdges(mesh, List.of(ref)) != 0) {
                    rejected.add("edge " + key + " already exists");
                } else if (!NativeMeshMirrorBridge.canAddEdge(mesh, ref)) {
                    rejected.add("edge " + key + " has an unsupported host type");
                } else {
                    prepared.add(ref);
                }
            }
            if (prepared.isEmpty()) return Preparation.refused(
                rejected.isEmpty() ? "the host can add no requested edge" : String.join("; ", rejected)
            );
            return Preparation.ready(result(rejected), () -> {
                for (MeshEdgeRef ref : prepared) NativeMeshMirrorBridge.addEdge(mesh, ref);
            });
        });
    }

    @Override
    public MeshEditResult deleteEdges(final List<MeshEdgeRef> edges) {
        if (NativeMeshMirrorBridge.participationDispatchActive()) {
            return MeshEditResult.refused("direct mutation is unavailable during mesh edit participation");
        }
        if (edges == null || edges.isEmpty()) return MeshEditResult.applied();
        return onEdt(() -> {
            Object editMode = null;
            boolean started = false;
            try {
                editMode = NativeMeshMirrorBridge.activeMeshEditMode();
                if (editMode == null) return MeshEditResult.refused("no mesh edit is active");
                final List<Object> meshes = meshes(editMode);
                if (meshes.size() != 1) {
                    return MeshEditResult.refused("edge deletion requires exactly one active mesh");
                }
                final Object mesh = meshes.get(0);
                final List<MeshEdgeRef> requested = new ArrayList<>();
                final List<String> rejected = new ArrayList<>();
                final Set<EdgeKey> seen = new HashSet<>();
                for (MeshEdgeRef ref : edges) {
                    if (ref == null) {
                        rejected.add("edge reference is null");
                        continue;
                    }
                    final EdgeKey key = EdgeKey.of(ref);
                    if (!seen.add(key)) {
                        rejected.add("edge " + key + " is duplicated");
                        continue;
                    }
                    if (NativeMeshMirrorBridge.countLiveEdges(mesh, List.of(ref)) == 0) {
                        rejected.add("edge " + key + " is not in the live mesh");
                    } else {
                        requested.add(ref);
                    }
                }
                if (requested.isEmpty()) return MeshEditResult.refused("no referenced edge is in the live mesh");
                final Object group = NativeMeshMirrorBridge.beginUndoGroup(editMode, "Delete Edges");
                if (group == null) return MeshEditResult.refused("the host refused an undo group");
                started = true;
                final int removed = NativeMeshMirrorBridge.removeEdgesInto(mesh, requested, group);
                if (removed != requested.size()) {
                    final String rollback = rollback(editMode, "MESH_EDIT_DELETE_EDGES_FAILED");
                    started = false;
                    return rollback == null
                        ? MeshEditResult.refused("the host did not remove every prepared edge")
                        : MeshEditResult.refused("the host did not remove every prepared edge; " + rollback);
                }
                NativeMeshMirrorBridge.commitUndoGroup(editMode);
                started = false;
                return result(rejected);
            } catch (Throwable failure) {
                final String rollback = started && editMode != null
                    ? rollback(editMode, "MESH_EDIT_DELETE_EDGES_FAILED")
                    : null;
                NativeMeshMirrorBridge.diagnostic("MESH_EDIT_DELETE_EDGES_FAILED reason=" + failure.getClass().getName());
                return refused(failure, rollback);
            }
        });
    }

    private static MeshEditResult mutate(
        final String label,
        final String failureMarker,
        final MutationPreparation preparation
    ) {
        return onEdt(() -> {
            Object actionPack = null;
            boolean started = false;
            try {
                final Object editMode = NativeMeshMirrorBridge.activeMeshEditMode();
                if (editMode == null) return MeshEditResult.refused("no mesh edit is active");
                final List<Object> meshes = meshes(editMode);
                if (meshes.size() != 1) {
                    return MeshEditResult.refused("direct mutation requires exactly one active mesh");
                }
                final Preparation prepared = preparation.prepare(meshes.get(0));
                if (prepared.refusal != null) return MeshEditResult.refused(prepared.refusal);
                actionPack = NativeMeshMirrorBridge.activeMeshActionPack();
                if (actionPack == null) return MeshEditResult.refused("the host exposes no mesh action pack");
                if (!NativeMeshMirrorBridge.actionPackOwnsEditMode(actionPack, editMode)) {
                    return MeshEditResult.refused("the host mesh action pack is stale");
                }
                if (NativeMeshMirrorBridge.beginUndoGroup(actionPack, label) == null) {
                    return MeshEditResult.refused("the host refused an undo group");
                }
                started = true;
                NativeMeshMirrorBridge.snapshotMeshesForUndo(actionPack, label);
                prepared.mutation.apply();
                NativeMeshMirrorBridge.commitUndoGroup(actionPack);
                started = false;
                return prepared.result;
            } catch (Throwable failure) {
                final String rollback = started && actionPack != null ? rollback(actionPack, failureMarker) : null;
                NativeMeshMirrorBridge.diagnostic(failureMarker + " reason=" + failure.getClass().getName());
                return refused(failure, rollback);
            }
        });
    }

    private static MeshEditResult result(final List<String> rejected) {
        return rejected.isEmpty() ? MeshEditResult.applied() : MeshEditResult.partiallyApplied(rejected);
    }

    private static MeshEditResult refused(final Throwable failure, final String rollback) {
        final String reason = failure.getClass().getName();
        return MeshEditResult.refused(rollback == null ? reason : reason + "; " + rollback);
    }

    private static String rollback(final Object owner, final String marker) {
        try {
            NativeMeshMirrorBridge.cancelUndoGroup(owner);
            return null;
        } catch (Throwable failure) {
            final String detail = "rollback failed: " + failure.getClass().getName();
            NativeMeshMirrorBridge.diagnostic(marker + " rollback=" + failure.getClass().getName());
            return detail;
        }
    }

    private static List<Object> meshes(final Object editMode) throws ReflectiveOperationException {
        final Object data = NativeMeshMirrorBridge.call(editMode, "getEditDataList", new Class<?>[0]);
        final List<Object> meshes = new ArrayList<>();
        if (!(data instanceof Iterable<?> iterable)) return meshes;
        for (Object entry : iterable) {
            final Object mesh = NativeMeshMirrorBridge.call(entry, "b", new Class<?>[0]);
            if (mesh != null) meshes.add(mesh);
        }
        return meshes;
    }

    private static <T> T onEdt(final Supplier<T> operation) {
        if (SwingUtilities.isEventDispatchThread()) return operation.get();
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { result[0] = operation.get(); }
                catch (Throwable throwable) { failure[0] = throwable; }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mesh edit dispatch was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Mesh edit dispatch failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) throw exception;
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] != null) throw new IllegalStateException("Mesh edit dispatch failed", failure[0]);
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    private interface MutationPreparation {
        Preparation prepare(Object mesh) throws ReflectiveOperationException;
    }

    private interface PreparedMutation {
        void apply() throws ReflectiveOperationException;
    }

    private record Preparation(MeshEditResult result, PreparedMutation mutation, String refusal) {
        static Preparation ready(final MeshEditResult result, final PreparedMutation mutation) {
            return new Preparation(result, mutation, null);
        }

        static Preparation refused(final String reason) {
            return new Preparation(null, null, reason);
        }
    }

    private record PointMove(Object point, float x, float y) { }

    private record EdgeKey(int start, int end) {
        static EdgeKey of(final MeshEdgeRef ref) {
            return new EdgeKey(ref.startPointId(), ref.endPointId());
        }

        @Override
        public String toString() {
            return start + "-" + end;
        }
    }
}
