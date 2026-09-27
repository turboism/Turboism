package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-context mesh index scoped to a single dispatch frame on the host thread. Each
 * index is materialized lazily on first use so a dispatch only pays for the views it
 * actually consults; every view preserves the enumeration order and first-match rules
 * of the repeated reflective scans it replaces.
 */
final class MeshFrameIndex {

    private final Object mesh;
    private List<?> points;
    private Set<Object> pointIdentity;
    private Map<Integer, Object> pointsById;
    private List<Object> edgeList;
    private Set<Object> edgeIdentity;
    private Map<Long, Object> edgesByKey;
    private Set<Long> duplicatedEdgeKeys;
    private Object edgeExclusion;

    MeshFrameIndex(final Object mesh) {
        this.mesh = mesh;
    }

    /** Excludes one edge object (the dispatch's source) from edge-key indexing. */
    MeshFrameIndex excludingEdge(final Object excluded) {
        this.edgeExclusion = excluded;
        return this;
    }

    List<?> points() throws ReflectiveOperationException {
        if (points == null) {
            points = NativeMeshMirrorBridge.points(mesh);
        }
        return points;
    }

    Set<Object> pointIdentity() throws ReflectiveOperationException {
        if (pointIdentity == null) {
            pointIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
            pointIdentity.addAll(points());
        }
        return pointIdentity;
    }

    /** First point in enumeration order for each id, matching the linear scan rule. */
    Map<Integer, Object> pointsById() throws ReflectiveOperationException {
        if (pointsById == null) {
            final Map<Integer, Object> byId = new HashMap<>();
            for (Object candidate : points()) {
                byId.putIfAbsent(NativeMeshMirrorBridge.pointId(candidate), candidate);
            }
            pointsById = byId;
        }
        return pointsById;
    }

    List<Object> edgeList() throws ReflectiveOperationException {
        if (edgeList == null) {
            final List<Object> collected = new ArrayList<>();
            for (Object edge : NativeMeshMirrorBridge.edges(mesh)) {
                collected.add(edge);
            }
            edgeList = collected;
        }
        return edgeList;
    }

    Set<Object> edgeIdentity() throws ReflectiveOperationException {
        if (edgeIdentity == null) {
            edgeIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
            edgeIdentity.addAll(edgeList());
        }
        return edgeIdentity;
    }

    /**
     * First live edge per unordered endpoint pair. Keys that occur on more than one
     * edge are reported through {@link #duplicatedEdgeKeys()} so callers keep the
     * same ambiguity behaviour as a full scan.
     */
    Map<Long, Object> edgesByKey() throws ReflectiveOperationException {
        buildEdgeIndex();
        return edgesByKey;
    }

    Set<Long> duplicatedEdgeKeys() throws ReflectiveOperationException {
        buildEdgeIndex();
        return duplicatedEdgeKeys;
    }

    private void buildEdgeIndex() throws ReflectiveOperationException {
        if (edgesByKey != null) return;
        final Map<Long, Object> byKey = new HashMap<>();
        final Set<Long> duplicated = new HashSet<>();
        for (Object candidate : edgeList()) {
            if (candidate == edgeExclusion) continue;
            final long key = edgeKey(candidate);
            if (key < 0) continue;
            if (byKey.putIfAbsent(key, candidate) != null) duplicated.add(key);
        }
        edgesByKey = byKey;
        duplicatedEdgeKeys = duplicated;
    }

    /** The unordered endpoint-pair key of a live edge; {@code -1} when unreadable. */
    static long edgeKey(final Object candidate) throws ReflectiveOperationException {
        final Object first = NativeMeshMirrorBridge.call(candidate, "getIndex1", new Class<?>[0]);
        final Object second = NativeMeshMirrorBridge.call(candidate, "getIndex2", new Class<?>[0]);
        if (!(first instanceof Number start) || !(second instanceof Number end)) return -1;
        final long low = Math.min(start.intValue(), end.intValue()) & 0xffffffffL;
        final long high = Math.max(start.intValue(), end.intValue()) & 0xffffffffL;
        return high << 32 | low;
    }

    /** The ordered endpoint-pair key of a reference; matches only already-normalized refs. */
    static long refEdgeKey(final MeshEdgeRef ref) {
        return (ref.endPointId() & 0xffffffffL) << 32 | (ref.startPointId() & 0xffffffffL);
    }
}
