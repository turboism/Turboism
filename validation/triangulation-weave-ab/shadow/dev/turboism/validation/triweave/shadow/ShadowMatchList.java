package dev.turboism.validation.triweave.shadow;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixture-side twin of the agent's {@code dev.turboism.validation.dweave.MatchList}
 * — same frozen contract (identity mirror; overrides ONLY contains(Object) and
 * add(Object); null-safe), plus test instrumentation counters like
 * {@link ShadowK#ORIGINAL_QUERIES}: the selfcheck asserts the woven allocation
 * really executed and the mirror really answered queries.
 *
 * Lives in the shadow fixture because the woven callsite resolves the helper
 * through the fixture loader — the agent jar's own MatchList is not reachable
 * there, mirroring how ShadowHelper stands in for triweave.Helper.
 */
public final class ShadowMatchList<E> extends ArrayList<E> {
    private static final long serialVersionUID = 1L;

    /** Test instrumentation: allocated instances (proves the woven NEW ran). */
    public static final AtomicInteger CREATED = new AtomicInteger();
    /** Test instrumentation: mirror queries actually answered. */
    public static final AtomicInteger CONTAINS_CALLS = new AtomicInteger();
    /** Test instrumentation: mirrored appends actually performed. */
    public static final AtomicInteger ADD_CALLS = new AtomicInteger();

    private final IdentityHashMap<E, Boolean> mirror = new IdentityHashMap<>();

    public ShadowMatchList() {
        CREATED.incrementAndGet();
    }

    /** O(1) identity membership — equals the add-only ArrayList answer. */
    @Override
    public boolean contains(Object o) {
        CONTAINS_CALLS.incrementAndGet();
        return mirror.containsKey(o);
    }

    /** Register in the mirror first, then perform the real append. */
    @Override
    public boolean add(E e) {
        ADD_CALLS.incrementAndGet();
        mirror.put(e, Boolean.TRUE);
        return super.add(e);
    }

    public static void reset() {
        CREATED.set(0);
        CONTAINS_CALLS.set(0);
        ADD_CALLS.set(0);
    }
}
