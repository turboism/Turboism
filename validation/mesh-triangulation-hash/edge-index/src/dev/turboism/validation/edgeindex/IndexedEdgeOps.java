package dev.turboism.validation.edgeindex;

import java.util.HashMap;
import java.util.Map;

/**
 * Candidate: replace the per-call linear first-hit scan inside one apply batch
 * with a temporary order-preserving first-hit index, built once after
 * {@link #clearAutoTriangulation()} and consulted per edge insertion.
 *
 * <p>Equivalence argument (simulation level): the linear scan returns the
 * lowest index whose endpoints match; building the map with
 * {@code putIfAbsent} in list order keeps exactly that lowest index. In-place
 * retype never changes endpoints or order, appends extend the map immediately,
 * so lookup results are identical to scanning the live list.</p>
 *
 * <p>Deliberately scoped to the apply loop: the index is batch-local and never
 * mutates the shared list semantics; outside callers keep the linear path.</p>
 */
final class IndexedEdgeOps extends EdgeOps {
    private Map<Long, Integer> index;
    /** Diagnostic only — how many initial entries shared an endpoint key. */
    int initialDuplicateKeys;

    /** Build the first-hit index once per apply batch. */
    @Override
    void beginBatch() {
        index = new HashMap<>(edges.size() * 2 + 16);
        for (int i = 0; i < edges.size(); i++) {
            comparisons++; // index-build scan cost is counted like the baseline
            final MEdge e = edges.get(i);
            index.putIfAbsent(key(e.index1, e.index2), i);
        }
        initialDuplicateKeys = edges.size() - index.size();
    }

    @Override
    void clearAutoTriangulation() {
        // removeIf compacts the list; any index built before it is stale.
        // In the apply order (clear → build → adds) this is a no-op, but a
        // standalone clear between batches must invalidate.
        index = null;
        super.clearAutoTriangulation();
    }

    @Override
    void endBatch() {
        index = null;
    }

    private static long key(final int lo, final int hi) {
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    @Override
    int addEdgeIfNotExists(final int i1, final int i2, final EdgeType type) {
        if (type == null) {
            throw nullTypeException();
        }
        if (i1 == i2) {
            logIllegalEdge(i1, i2);
            return -1;
        }
        final int lo = Math.min(i1, i2);
        final int hi = Math.max(i1, i2);
        version++;
        if (index == null) {
            beginBatch(); // defensive: indexless call falls back to same semantics
        }
        comparisons++;
        final Integer hit = index.get(key(lo, hi));
        if (hit != null) {
            final MEdge existing = edges.get(hit);
            if (existing.type.priority() < type.priority()) {
                edges.set(hit, new MEdge(existing.index1, existing.index2, type));
                events.add("retype:" + hit);
            }
            return hit;
        }
        final int idx = edges.size();
        edges.add(new MEdge(lo, hi, type));
        index.put(key(lo, hi), idx);
        return idx;
    }
}
