package dev.turboism.validation.kmembership;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import dev.turboism.validation.kmembership.Fixture.EdgeJ;
import dev.turboism.validation.kmembership.Fixture.EdgeK;

/**
 * Woven-path helper (compiled to its own classes dir so a real missing-helper
 * classloader isolation scenario is possible). Lives in the fixture only.
 *
 * Host-call whitelist: j.a(), j.b(), TriPoint.getIndex() — pure reads only.
 * No k.* / l.* / iterator calls. The Box type never appears in any woven
 * descriptor/frame: it crosses the call boundary as java.lang.Object.
 */
public final class Helper {
    private Helper() {}

    // --- observable counters (fixture instrumentation) ----------------------
    public static final AtomicInteger NEWBOX_CALLS = new AtomicInteger();
    public static final AtomicInteger QUERIES = new AtomicInteger();
    public static final AtomicInteger BOX_ALLOCS = new AtomicInteger();
    public static final AtomicInteger SET_ALLOCS = new AtomicInteger();

    // --- injected-failure test knobs (fixture only) --------------------------
    public static volatile boolean failNewBox;          // newBox -> LinkageError
    public static volatile int failQueryAt = -1;        // query #N -> LinkageError
    public static volatile int injectError;             // 1=RE 2=ThreadDeath 3=VMErr

    static final class Box {
        HashSet<Long> seen;                    // lazy: materialize on first query
        Box() { BOX_ALLOCS.incrementAndGet(); }
    }

    /** Entry init: returns a fresh independent box, or fails with LinkageError. */
    public static Object newBox() {
        NEWBOX_CALLS.incrementAndGet();
        if (failNewBox) throw new LinkageError("injected-newbox");
        return new Box();
    }

    /** Membership query. Never sees null j (caller gates). RuntimeException,
     *  ThreadDeath and VirtualMachineError propagate by contract — only
     *  LinkageError is a recoverable infrastructure failure at the callsite. */
    public static boolean query(EdgeK k, EdgeJ j, boolean directed, Object box) {
        QUERIES.incrementAndGet();
        if (failQueryAt >= 0 && QUERIES.get() == failQueryAt)
            throw new LinkageError("injected-query-" + failQueryAt);
        switch (injectError) {
            case 1: throw new RuntimeException("injected-re");
            case 2: throw new ThreadDeath();
            case 3: throw new OutOfMemoryError("injected-vme");
            default: break;
        }
        Box b = (Box) box;
        if (b.seen == null) { b.seen = new HashSet<>(); SET_ALLOCS.incrementAndGet(); }
        int i0 = j.a().getIndex(), i1 = j.b().getIndex();
        long key = directed
            ? ((long) i0 << 32) | (i1 & 0xffffffffL)
            : ((long) Math.min(i0, i1) << 32) | (Math.max(i0, i1) & 0xffffffffL);
        return !b.seen.add(key);
    }

    public static void reset() {
        NEWBOX_CALLS.set(0); QUERIES.set(0);
        BOX_ALLOCS.set(0); SET_ALLOCS.set(0);
        failNewBox = false; failQueryAt = -1; injectError = 0;
    }
}
