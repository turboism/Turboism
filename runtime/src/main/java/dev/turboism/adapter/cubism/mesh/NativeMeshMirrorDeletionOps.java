package dev.turboism.adapter.cubism.mesh;

import static dev.turboism.adapter.cubism.mesh.NativeMeshMirrorReflectionOps.*;

import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Applies mirror-linked deletions against the live host meshes.
 *
 * <p>Participants propose deletions as host-neutral references; this class revalidates every
 * proposal against the live mesh and drops anything stale, ambiguous or already gone. A point id
 * is mesh-local, so a reference that names more than one distinct live object is ambiguous and
 * must fail closed rather than widen a plugin contribution.
 */
final class NativeMeshMirrorDeletionOps {
    static List<MeshEdgeRef> edgeRefs(final List<Object> edges) throws ReflectiveOperationException {
        final List<MeshEdgeRef> refs = new ArrayList<>();
        for (Object edge : edges) {
            final Object first = call(edge, "getIndex1", NO_PARAMS);
            final Object second = call(edge, "getIndex2", NO_PARAMS);
            if (first instanceof Number start && second instanceof Number end && start.intValue() != end.intValue()) {
                refs.add(new MeshEdgeRef(
                        start.intValue(), end.intValue(), dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN));
            }
        }
        return refs;
    }

    /** Contributions are revalidated against the live mesh; a stale id is dropped, never guessed. */
    static PointDeletionResult applyPointDeletions(
            final Object pack,
            final Object groupUndo,
            final MeshEditContribution contribution,
            final List<Object> sources,
            final boolean removeIncidentEdges,
            final List<Object> resolved)
            throws ReflectiveOperationException {
        final List<Object> live = new ArrayList<>();
        if (resolved != null) live.addAll(resolved);
        final List<MeshPointRef> custom = NativeMeshMirrorBridge.customPointContributions(contribution);
        final List<Object> customMatches = uniqueCustomPointMatches(pack, custom, sources);
        live.addAll(customMatches);
        if (live.isEmpty()) return new PointDeletionResult(0, 0);

        final int edges = removeIncidentEdges ? removeIncidentEdgesInto(pack, live, groupUndo) : 0;
        final Object editMode = call(pack, "aP", NO_PARAMS);
        final Method delete = declaredMethod(editMode.getClass(), "delete_exe", List.class, groupUndo.getClass());
        if (delete == null) {
            NativeMeshMirrorBridge.diagnostic("PARTICIPATION_REJECTED kind=POINTS reason=NO_DELETE_EXE");
            return new PointDeletionResult(0, edges);
        }
        delete.invoke(editMode, List.of(live), groupUndo);
        return new PointDeletionResult(live.size(), edges);
    }

    private static int removeIncidentEdgesInto(final Object pack, final List<Object> points, final Object groupUndo)
            throws ReflectiveOperationException {
        int removed = 0;
        for (Object context : contexts(pack)) {
            final Object mesh = call(context, "b", NO_PARAMS);
            if (mesh == null) continue;
            final MeshFrameIndex index = new MeshFrameIndex(mesh);
            final Set<Object> livePoints = index.pointIdentity();
            final Set<Integer> ids = new HashSet<>();
            for (Object point : points) {
                if (livePoints.contains(point)) {
                    ids.add(pointId(point));
                }
            }
            if (ids.isEmpty()) continue;
            final List<Object> incident = new ArrayList<>();
            final Set<Object> incidentSeen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object edge : edges(mesh)) {
                final Object first = call(edge, "getIndex1", NO_PARAMS);
                final Object second = call(edge, "getIndex2", NO_PARAMS);
                if (!(first instanceof Number start) || !(second instanceof Number end)) continue;
                if ((ids.contains(start.intValue()) || ids.contains(end.intValue())) && incidentSeen.add(edge)) {
                    incident.add(edge);
                }
            }
            removed += removeLiveEdgesInto(mesh, incident, groupUndo);
        }
        return removed;
    }

    record PointDeletionResult(int points, int edges) {}

    /** Native-style endpoint reconstruction for eraser sources, plus plugin custom output. */
    static int applyEraserEdgeDeletions(
            final Object pack,
            final Object mirror,
            final Object groupUndo,
            final MeshEditContribution contribution,
            final List<Object> sources)
            throws ReflectiveOperationException {
        final List<MeshEdgeRef> custom = NativeMeshMirrorBridge.customEdgeContributions(contribution);
        int removed = 0;
        for (Object context : contexts(pack)) {
            final Object mesh = call(context, "b", NO_PARAMS);
            if (mesh == null) continue;
            final List<Object> live = new ArrayList<>();
            for (Object source : sources) {
                final Object counterpart =
                        NativeMeshMirrorCounterpartOps.counterpartEdge(mirror, source, mesh, pack, context);
                if (counterpart != null && !containsSame(live, counterpart)) {
                    live.add(counterpart);
                }
            }
            for (MeshEdgeRef ref : custom) {
                for (Object candidate : edges(mesh)) {
                    if (!edgeMatches(candidate, ref) || containsSame(live, candidate)) continue;
                    live.add(candidate);
                }
            }
            removed += removeLiveEdgesInto(mesh, live, groupUndo);
        }
        return removed;
    }

    private static int removeLiveEdgesInto(final Object mesh, final List<Object> live, final Object groupUndo)
            throws ReflectiveOperationException {
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

    /** Revalidates each contributed edge against the live mesh before removing it. */
    static int applyEdgeDeletions(
            final Object pack,
            final Object groupUndo,
            final MeshEditContribution contribution,
            final Object source,
            final List<Object> resolved)
            throws ReflectiveOperationException {
        int removed = 0;
        final List<MeshEdgeRef> custom = NativeMeshMirrorBridge.customEdgeContributions(contribution);
        final List<Object> customMatches = uniqueCustomEdgeMatches(pack, custom, source);
        for (Object context : contexts(pack)) {
            final Object mesh = call(context, "b", NO_PARAMS);
            if (mesh == null) continue;
            final Object hostEdges = call(mesh, "getEdges", NO_PARAMS);
            if (!(hostEdges instanceof Iterable<?> iterable)) continue;
            final Set<Object> liveEdgeSet = identitySet(iterable);
            final List<Object> live = new ArrayList<>();
            final Set<Object> liveSeen = Collections.newSetFromMap(new IdentityHashMap<>());
            if (resolved != null) {
                for (Object edge : resolved) {
                    if (liveEdgeSet.contains(edge)) {
                        live.add(edge);
                        liveSeen.add(edge);
                    }
                }
            }
            for (Object edge : customMatches) {
                if (liveEdgeSet.contains(edge) && liveSeen.add(edge)) live.add(edge);
            }
            if (live.isEmpty()) continue;
            final Object handler = call(mesh, "getHandler", NO_PARAMS);
            if (handler == null) continue;
            final Method remove = declaredMethod(handler.getClass(), "a", List.class);
            if (remove == null || !hasUndoAttachmentMethod(groupUndo)) continue;
            final Object undo = remove.invoke(handler, live);
            if (undo == null) continue;
            final Method plusAssign = declaredMethod(groupUndo.getClass(), "plusAssign", undo.getClass());
            if (plusAssign == null) throw new NoSuchMethodException("mesh edge undo attachment");
            plusAssign.invoke(groupUndo, undo);
            removed += live.size();
        }
        return removed;
    }

    /**
     * Resolves a host-neutral point reference only when it names one distinct live non-source
     * object across the whole edit. Point ids are mesh-local, so multiple matches are ambiguous
     * and must fail closed rather than widening a plugin contribution.
     */
    private static List<Object> uniqueCustomPointMatches(
            final Object pack, final List<MeshPointRef> refs, final List<Object> sources)
            throws ReflectiveOperationException {
        final List<MeshFrameIndex> indexes = new ArrayList<>();
        for (Object context : contexts(pack)) {
            final Object mesh = call(context, "b", NO_PARAMS);
            indexes.add(mesh == null ? null : new MeshFrameIndex(mesh));
        }
        final List<Object> unique = new ArrayList<>();
        for (MeshPointRef ref : refs) {
            Object match = null;
            boolean ambiguous = false;
            for (MeshFrameIndex index : indexes) {
                if (index == null) continue;
                final Object candidate = index.pointsById().get(ref.id());
                if (candidate == null || containsSame(sources, candidate)) continue;
                if (match != null && match != candidate) {
                    ambiguous = true;
                    break;
                }
                match = candidate;
            }
            if (!ambiguous && match != null && !containsSame(unique, match)) unique.add(match);
        }
        return unique;
    }

    /** Same fail-closed rule as points, using the endpoint pair as the mesh-local edge key. */
    private static List<Object> uniqueCustomEdgeMatches(
            final Object pack, final List<MeshEdgeRef> refs, final Object source) throws ReflectiveOperationException {
        final List<MeshFrameIndex> indexes = new ArrayList<>();
        for (Object context : contexts(pack)) {
            final Object mesh = call(context, "b", NO_PARAMS);
            indexes.add(mesh == null ? null : new MeshFrameIndex(mesh).excludingEdge(source));
        }
        final List<Object> unique = new ArrayList<>();
        for (MeshEdgeRef ref : refs) {
            final long key = MeshFrameIndex.refEdgeKey(ref);
            Object match = null;
            boolean ambiguous = false;
            for (MeshFrameIndex index : indexes) {
                if (index == null) continue;
                final Object candidate = index.edgesByKey().get(key);
                if (candidate == null) continue;
                if (match != null && match != candidate) {
                    ambiguous = true;
                    break;
                }
                match = candidate;
                if (index.duplicatedEdgeKeys().contains(key)) {
                    ambiguous = true;
                    break;
                }
            }
            if (!ambiguous && match != null && !containsSame(unique, match)) unique.add(match);
        }
        return unique;
    }

    private static boolean edgeMatches(final Object candidate, final MeshEdgeRef ref)
            throws ReflectiveOperationException {
        final Object first = call(candidate, "getIndex1", NO_PARAMS);
        final Object second = call(candidate, "getIndex2", NO_PARAMS);
        if (!(first instanceof Number start) || !(second instanceof Number end)) return false;
        return ref.startPointId() == Math.min(start.intValue(), end.intValue())
                && ref.endPointId() == Math.max(start.intValue(), end.intValue());
    }
}
