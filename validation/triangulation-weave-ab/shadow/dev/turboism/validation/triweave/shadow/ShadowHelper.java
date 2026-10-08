package dev.turboism.validation.triweave.shadow;

import java.util.HashSet;

/**
 * Woven-path helper for the shadow fixture — the same contract as the agent's official-type
 * Helper, typed on ShadowK/ShadowJ. Injected-failure knobs are system properties read at
 * call time so each selfcheck JVM can arm them without a recompile. RuntimeException,
 * ThreadDeath and VirtualMachineError propagate; only LinkageError is recoverable at the
 * woven callsite.
 */
public final class ShadowHelper {
    private ShadowHelper() {}

    private static final String P = "turboism.validation.triweave.shadow.";
    static final String FAIL_NEWBOX = P + "failNewBox";
    static final String FAIL_QUERY_AT = P + "failQueryAt";
    static final String INJECT_ERROR = P + "injectError";   // 1=RE 2=ThreadDeath 3=VMErr

    static final class Box {
        HashSet<Long> seen;                    // lazy: materialize on first query
    }

    public static Object newBox() {
        ShadowCounters.NEWBOX_CALLS.incrementAndGet();
        if (Boolean.getBoolean(FAIL_NEWBOX)) throw new LinkageError("injected-newbox");
        return new Box();
    }

    public static boolean query(ShadowK k, ShadowJ j, boolean directed, Object box) {
        ShadowCounters.QUERIES.incrementAndGet();
        int failAt = Integer.getInteger(FAIL_QUERY_AT, -1);
        if (failAt >= 0 && ShadowCounters.QUERIES.get() == failAt)
            throw new LinkageError("injected-query-" + failAt);
        switch (Integer.getInteger(INJECT_ERROR, 0)) {
            case 1: throw new RuntimeException("injected-re");
            case 2: throw new ThreadDeath();
            case 3: throw new OutOfMemoryError("injected-vme");
            default: break;
        }
        Box b = (Box) box;
        if (b.seen == null) b.seen = new HashSet<>();
        int i0 = j.x().index(), i1 = j.y().index();
        long key = directed
            ? ((long) i0 << 32) | (i1 & 0xffffffffL)
            : ((long) Math.min(i0, i1) << 32) | (Math.max(i0, i1) & 0xffffffffL);
        boolean contained = !b.seen.add(key);
        if (contained) ShadowCounters.HITS.incrementAndGet();
        return contained;
    }
}
