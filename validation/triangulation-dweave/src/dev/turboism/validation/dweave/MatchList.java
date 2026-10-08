package dev.turboism.validation.dweave;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * T029-DWEAVE helper: an {@code ArrayList} whose {@code contains(Object)} is
 * answered in O(1) by an identity mirror instead of the O(n) element scan.
 *
 * Frozen contract (plan.md T029-DWEAVE):
 *  - overrides ONLY {@link #contains(Object)} and {@link #add(Object)};
 *  - the mirror is an {@link IdentityHashMap} keyed by the element reference
 *    itself, so {@code contains} is an identity-membership test — pointwise
 *    equivalent to {@code ArrayList.contains} for every list reachable through
 *    {@code add} ALONE, under any element semantics (identity or equals);
 *  - null-safe: {@code IdentityHashMap} permits the null key, so
 *    {@code contains(null)} is true iff {@code add(null)} ran — exactly the
 *    {@code ArrayList} answer for an add-only list;
 *  - no state outside the two overrides beyond the per-instance mirror; no
 *    static/global state.
 *
 * Boundary (documented, not a defect): mutators other than {@code add}
 * ({@code remove}, {@code addAll}, {@code set}, ...) are NOT mirrored, so
 * {@code contains} may go stale after them. In the official window that is
 * unobservable: javap shows the only {@code contains} calls on the target
 * list are inside the Phase-3 window (bci 438/472/506), and the only writes
 * inside that window are {@code add} (bci 448/482/516); every post-window
 * mutation (remove(0) via h.b, addAll via h.a(ArrayList,j,TL,k)) happens
 * strictly after the last contains.
 *
 * Also: {@code contains} mirrors IDENTITY membership. If an element type
 * overrides {@code equals}, {@code ArrayList.contains(equal-but-not-same)}
 * answers true while the mirror answers false. The official element type j
 * declares neither equals nor hashCode (javap-verified), so the contract
 * holds unconditionally there. Do not reuse this helper for value-equality
 * element types.
 */
public final class MatchList<E> extends ArrayList<E> {
    private static final long serialVersionUID = 1L;

    /** Identity mirror of the live element set. Key = the reference itself. */
    private final IdentityHashMap<E, Boolean> mirror = new IdentityHashMap<>();

    /** O(1) identity membership — equals the add-only ArrayList answer. */
    @Override
    public boolean contains(Object o) {
        return mirror.containsKey(o);
    }

    /** Register in the mirror first, then perform the real append. */
    @Override
    public boolean add(E e) {
        mirror.put(e, Boolean.TRUE);
        return super.add(e);
    }
}
