package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshEditResult;
import dev.turboism.sdk.cubism.mesh.MeshEditService;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import dev.turboism.sdk.cubism.mesh.MeshSnapshot;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Plugin-initiated mesh deletion, each call its own undoable step.
 *
 * <p>Only the host's own undo-aware handler operations are used, so Undo restores exactly what
 * the host would restore. Everything is revalidated against the live mesh first: a reference to
 * a point or edge that no longer exists is reported as rejected, never guessed at.</p>
 */
public final class RuntimeMeshEditService implements MeshEditService {

    @Override
    public MeshSnapshot snapshot() {
        try {
            final Object editMode = NativeMeshMirrorBridge.activeMeshEditMode();
            if (editMode == null) return MeshSnapshot.empty();
            final List<MeshPointRef> points = new ArrayList<>();
            final List<MeshEdgeRef> edges = new ArrayList<>();
            for (Object mesh : meshes(editMode)) {
                NativeMeshMirrorBridge.collectSnapshot(mesh, points, edges);
            }
            return new MeshSnapshot(points, edges);
        } catch (Throwable failure) {
            NativeMeshMirrorBridge.diagnostic("MESH_EDIT_SNAPSHOT_FAILED reason=" + failure.getClass().getName());
            return MeshSnapshot.empty();
        }
    }

    @Override
    public MeshEditResult deletePoints(final List<MeshPointRef> points) {
        if (points == null || points.isEmpty()) return MeshEditResult.applied();
        try {
            final Object editMode = NativeMeshMirrorBridge.activeMeshEditMode();
            if (editMode == null) return MeshEditResult.refused("no mesh edit is active");
            final List<String> rejected = new ArrayList<>();
            final List<Object> live = new ArrayList<>();
            for (Object mesh : meshes(editMode)) {
                for (MeshPointRef ref : points) {
                    final Object point = NativeMeshMirrorBridge.pointById(mesh, ref.id());
                    if (point != null) live.add(point);
                }
            }
            for (MeshPointRef ref : points) {
                if (live.size() < points.size()) rejected.add("point " + ref.id() + " is not in the live mesh");
            }
            if (live.isEmpty()) return MeshEditResult.refused("no referenced point is in the live mesh");
            final Object group = NativeMeshMirrorBridge.beginUndoGroup(editMode, "Delete Points");
            if (group == null) return MeshEditResult.refused("the host refused an undo group");
            final Method delete = NativeMeshMirrorBridge.declaredMethod(
                editMode.getClass(), "delete_exe", List.class, group.getClass()
            );
            if (delete == null) return MeshEditResult.refused("the host exposes no point deletion");
            delete.invoke(editMode, List.of(live), group);
            return rejected.isEmpty() ? MeshEditResult.applied() : MeshEditResult.partiallyApplied(rejected);
        } catch (Throwable failure) {
            NativeMeshMirrorBridge.diagnostic("MESH_EDIT_DELETE_POINTS_FAILED reason=" + failure.getClass().getName());
            return MeshEditResult.refused(failure.getClass().getName());
        }
    }

    @Override
    public MeshEditResult deleteEdges(final List<MeshEdgeRef> edges) {
        if (edges == null || edges.isEmpty()) return MeshEditResult.applied();
        try {
            final Object editMode = NativeMeshMirrorBridge.activeMeshEditMode();
            if (editMode == null) return MeshEditResult.refused("no mesh edit is active");
            final Object group = NativeMeshMirrorBridge.beginUndoGroup(editMode, "Delete Edges");
            if (group == null) return MeshEditResult.refused("the host refused an undo group");
            int removed = 0;
            for (Object mesh : meshes(editMode)) {
                removed += NativeMeshMirrorBridge.removeEdgesInto(mesh, edges, group);
            }
            if (removed == 0) return MeshEditResult.refused("no referenced edge is in the live mesh");
            return removed == edges.size()
                ? MeshEditResult.applied()
                : MeshEditResult.partiallyApplied(List.of(removed + " of " + edges.size() + " edges were live"));
        } catch (Throwable failure) {
            NativeMeshMirrorBridge.diagnostic("MESH_EDIT_DELETE_EDGES_FAILED reason=" + failure.getClass().getName());
            return MeshEditResult.refused(failure.getClass().getName());
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
}
