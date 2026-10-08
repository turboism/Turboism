package dev.turboism.validation.tlindex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * T029-TLINDEX candidate: edge-indexed TriangleList.a(j) with surgical
 * mutator interception. Pure JDK types only (LinkedHashSet, Object, ints)
 * so the identical class file can be shipped in the weave agent and called
 * from woven official code - no official-type references at compile time.
 *
 * Weave contract (see DESIGN.md):
 *  - a(l)Z:  the `LinkedHashSet.add` invoke is replaced by
 *            `Bridge.add(set, tri, l.a().getIndex(), l.b().getIndex(),
 *            l.c().getIndex())`. Official null-check + log preamble intact.
 *  - b(l)Z:  `LinkedHashSet.remove` invoke -> `Bridge.remove(set, tri)`.
 *  - c()V:   `LinkedHashSet.clear` invoke -> `Bridge.clear(set)`.
 *  - a(j)Ljava/util/List;: PREPEND `List r = Bridge.tryQuery(set,
 *            j.a().getIndex(), j.b().getIndex()); if (r != null) return r;`
 *    keeping the ENTIRE original scan body as the automatic fallback. Any
 *    index anomaly -> null -> original O(T) scan runs -> correctness is
 *    structural, not asserted.
 *
 * Consistency invariants (why this is airtight):
 *  - Every set element entered through add() -> its vertex-index keys are
 *    recorded in `keys` (IdentityHashMap; the STORED object is the arg).
 *    Keys are recorded unconditionally on a successful add, so a dirty-set
 *    rebuild still finds them.
 *  - Buckets are only touched when the precheck `sz == size-1` holds; any
 *    unweaved net mutation (iterator.remove shrinks the set) breaks the
 *    precheck -> dirty -> next tryQuery rebuilds or falls back dead.
 *  - remove() does NOT deindex by the ARGUMENT's keys: set.remove(x) may
 *    remove a different stored object that is l.equals(x) but carries
 *    different vertex indices (l.equals is a cyclic permutation of
 *    TriPoint-equals, and TriPoint.equals ignores index). Instead it
 *    iterates the set, finds the first element e with x.equals(e) - the
 *    SAME element HashMap.removeNode removes (bucket-0 chain preserves
 *    insertion order, arg.equals(stored) comparison) - removes it via the
 *    iterator, then deindexes e by e's OWN recorded keys. O(T), the same
 *    class as the official remove (l.hashCode() == 0 makes every Linked-
     *    HashSet op an O(T) equals scan anyway).
 *  - rebuild() detects elements lacking recorded keys (an unweaved add
 *    path) -> state becomes `dead` -> tryQuery returns null forever -> the
 *    original body answers every query at baseline cost.
 *  - Bookkeeping never throws into the host: everything after the real set
 *    op is wrapped; failures just mark dirty/dead.
 *  - STATES is IdentityHashMap, never hash/weak: LinkedHashSet.hashCode()
 *    is AbstractSet's O(T) element-sum and equals() is O(T^2) set equality;
 *    keying by value would reintroduce the scan we are removing.
 */
@SuppressWarnings({"rawtypes", "unchecked"})  // the woven descriptors ARE raw
public final class Bridge {
    private Bridge() {}

    /** Undirected endpoint-index key; safe for negative sentinel indices. */
    static long key(int i, int j) {
        return ((long) Math.min(i, j) << 32) | (Math.max(i, j) & 0xffffffffL);
    }

    static final class St {
        final HashMap<Long, ArrayList<Object>> byKey = new HashMap<>();
        final IdentityHashMap<Object, long[]> keys = new IdentityHashMap<>();
        int sz = 0;
        boolean dirty = true;
        boolean dead = false;
    }

    /** Per-set state, identity-keyed on the final `b` field. */
    static final IdentityHashMap<LinkedHashSet, St> STATES = new IdentityHashMap<>();

    static St st(LinkedHashSet s) {
        St t = STATES.get(s);
        if (t == null) { t = new St(); STATES.put(s, t); }
        return t;
    }

    /** Replaces `LinkedHashSet.add`. Runs the REAL add first (the official
     *  return value), then bookkeeping that can only degrade, never alter,
     *  the set's semantics. */
    public static boolean add(LinkedHashSet s, Object tri, int ia, int ib, int ic) {
        boolean ch = s.add(tri);
        try {
            St t = st(s);
            if (ch) {
                long k1 = key(ia, ib), k2 = key(ib, ic), k3 = key(ic, ia);
                t.keys.put(tri, new long[] {k1, k2, k3});
                if (!t.dead && !t.dirty && t.sz == s.size() - 1) {
                    put(t, k1, tri);
                    if (k2 != k1) put(t, k2, tri);
                    if (k3 != k1 && k3 != k2) put(t, k3, tri);
                } else {
                    t.dirty = true;
                }
                t.sz = s.size();
            }
        } catch (Throwable ignore) {
            st(s).dirty = true;
        }
        return ch;
    }

    /** Replaces `LinkedHashSet.remove`. Removes the first stored element e
     *  satisfying tri.equals(e) - identical to what HashMap.removeNode does
     *  under hashCode()==0 (single bin, insertion-ordered chain scan) - via
     *  iterator.remove(), then deindexes e by e's recorded keys. O(T). */
    public static boolean remove(LinkedHashSet s, Object tri) {
        Object victim = null;
        boolean removed = false;
        for (Iterator<?> it = s.iterator(); it.hasNext(); ) {
            Object e = it.next();
            if (tri == e || tri.equals(e)) { victim = e; it.remove(); removed = true; break; }
        }
        if (!removed) return false;
        try {
            St t = st(s);
            long[] ks = t.keys.remove(victim);
            if (ks == null || t.dead || t.dirty) {
                t.dirty = true;                       // unknown keys -> resync
            } else {
                for (int i = 0; i < 3; i++) {
                    if (i > 0 && (ks[i] == ks[0] || (i == 2 && ks[2] == ks[1]))) continue;
                    ArrayList<Object> bkt = t.byKey.get(ks[i]);
                    if (bkt == null) { t.dirty = true; break; }
                    int at = -1;
                    for (int j = 0; j < bkt.size(); j++)
                        if (bkt.get(j) == victim) { at = j; break; }   // identity!
                    if (at < 0) { t.dirty = true; break; }
                    bkt.remove(at);
                    if (bkt.isEmpty()) t.byKey.remove(ks[i]);
                }
            }
            t.sz = s.size();
        } catch (Throwable ignore) {
            st(s).dirty = true;
        }
        return true;
    }

    /** Replaces `LinkedHashSet.clear`. */
    public static void clear(LinkedHashSet s) {
        s.clear();
        try {
            St t = st(s);
            t.byKey.clear(); t.keys.clear();
            t.sz = 0; t.dirty = false;
        } catch (Throwable ignore) { /* empty set needs no index */ }
    }

    /** Woven into a(j) ahead of the original body. Returns the insertion-
     *  ordered hit list, or null meaning "run the original scan". */
    public static List tryQuery(LinkedHashSet s, int ja, int jb) {
        St t;
        try { t = st(s); }
        catch (Throwable e) { return null; }
        if (t.dead) return null;
        try {
            if (t.dirty || t.sz != s.size()) {
                if (!rebuild(t, s)) { t.dead = true; return null; }
            }
            ArrayList<Object> bucket = t.byKey.get(key(ja, jb));
            ArrayList<Object> out = new ArrayList<>(bucket == null ? 4 : bucket.size());
            if (bucket != null) out.addAll(bucket);
            return out;
        } catch (Throwable e) {
            t.dead = true;          // never trust a crashed index again
            return null;
        }
    }

    private static void put(St t, long k, Object tri) {
        t.byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(tri);
    }

    /** Rebuild from the live set, preserving LinkedHashSet insertion order
     *  in every bucket and pruning keys of removed elements. Returns false
     *  if any element lacks recorded keys (unweaved-add element). */
    static boolean rebuild(St t, LinkedHashSet s) {
        HashMap<Long, ArrayList<Object>> m = new HashMap<>();
        IdentityHashMap<Object, Boolean> live = new IdentityHashMap<>();
        for (Object o : s) {
            long[] ks = t.keys.get(o);
            if (ks == null) return false;          // unweaved-add element
            live.put(o, Boolean.TRUE);
            long k1 = ks[0], k2 = ks[1], k3 = ks[2];
            m.computeIfAbsent(k1, x -> new ArrayList<>()).add(o);
            if (k2 != k1) m.computeIfAbsent(k2, x -> new ArrayList<>()).add(o);
            if (k3 != k1 && k3 != k2) m.computeIfAbsent(k3, x -> new ArrayList<>()).add(o);
        }
        t.byKey.clear(); t.byKey.putAll(m);
        t.keys.keySet().retainAll(live.keySet());
        t.sz = s.size(); t.dirty = false;
        return true;
    }
}
