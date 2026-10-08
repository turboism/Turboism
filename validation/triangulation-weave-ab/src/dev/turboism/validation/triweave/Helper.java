package dev.turboism.validation.triweave;

import java.util.HashSet;

import com.live2d.graphics3d.editableMesh.triangulation.j;
import com.live2d.graphics3d.editableMesh.triangulation.k;

/**
 * Woven-path helper typed on the OFFICIAL k/j/TriPoint signatures (javap-verified, see
 * DESIGN.md). Compiled against the official jar read-only; the jar is never executed.
 *
 * Host-call whitelist: j.a(), j.b(), TriPoint.getIndex() — pure reads only. No k.* writes,
 * no iterator access. The Box type never appears in any woven descriptor/frame: it crosses
 * the call boundary as java.lang.Object. RuntimeException, ThreadDeath and
 * VirtualMachineError propagate by contract — only LinkageError is recoverable at the
 * woven callsite.
 */
public final class Helper {
    private Helper() {}

    static final class Box {
        HashSet<Long> seen;                    // lazy: materialize on first query
    }

    /** Entry init: returns a fresh independent box per call of the target method. */
    public static Object newBox() {
        Counters.NEWBOX_CALLS.incrementAndGet();
        return new Box();
    }

    /**
     * Membership query: undirected endpoint-index pair membership in the per-call window.
     * Never sees a null j (the woven gate falls through to the original query first).
     */
    public static boolean query(k kk, j jj, boolean directed, Object box) {
        Counters.QUERIES.incrementAndGet();
        Box b = (Box) box;
        if (b.seen == null) b.seen = new HashSet<>();
        int i0 = jj.a().getIndex(), i1 = jj.b().getIndex();
        long key = directed
            ? ((long) i0 << 32) | (i1 & 0xffffffffL)
            : ((long) Math.min(i0, i1) << 32) | (Math.max(i0, i1) & 0xffffffffL);
        boolean contained = !b.seen.add(key);
        if (contained) Counters.HITS.incrementAndGet();   // undirected key already present
        return contained;
    }
}
