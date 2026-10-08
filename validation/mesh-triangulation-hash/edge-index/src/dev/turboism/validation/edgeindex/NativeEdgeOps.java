package dev.turboism.validation.edgeindex;

/**
 * Faithful simulation of the official addEdgeIfNotExists/apply edge-list
 * behavior: linear first-hit endpoint scan, in-place retype by priority,
 * tail append, per-call version bump, degenerate-edge log. No hash index.
 */
class NativeEdgeOps extends EdgeOps {
    /** Endpoint-pair comparisons performed by the linear first-hit scan. */
    long endpointComparisons;

    @Override
    int addEdgeIfNotExists(final int i1, final int i2, final EdgeType type) {
        // Intrinsics.checkNotNullParameter(type, ...) — NPE with Kotlin prefix.
        if (type == null) {
            throw nullTypeException();
        }
        // Degenerate endpoints: log and return -1 before normalization/version.
        if (i1 == i2) {
            logIllegalEdge(i1, i2);
            return -1;
        }
        final int lo = Math.min(i1, i2);
        final int hi = Math.max(i1, i2);
        version++; // setEdgeUpdated() — unconditional on this path
        // chechExistingEdge_exe: linear 0..size-1 first endpoint match (any type).
        int hit = -1;
        for (int i = 0; i < edges.size(); i++) {
            endpointComparisons++;
            final MEdge e = edges.get(i);
            if (e.index1 == lo && e.index2 == hi) {
                hit = i;
                break;
            }
        }
        if (hit >= 0) {
            // checkExitingTypedEdge: retype in place only when the new type has
            // strictly higher priority than the existing one.
            final MEdge existing = edges.get(hit);
            if (existing.type.priority() < type.priority()) {
                edges.set(hit, new MEdge(existing.index1, existing.index2, type));
                events.add("retype:" + hit);
            }
            return hit;
        }
        final int index = edges.size();
        edges.add(new MEdge(lo, hi, type));
        return index;
    }
}
