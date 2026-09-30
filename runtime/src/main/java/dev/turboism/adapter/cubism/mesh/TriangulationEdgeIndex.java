package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Edge-indexed adjunct for the host triangulator's per-edge triangle lookup.
 *
 * <p>Woven call contract ({@link TriangulationEdgeIndexPatcher} emits these call sites):
 * {@code a(l)}'s {@code LinkedHashSet.add} becomes {@link #add}, {@code b(l)}'s remove becomes
 * {@link #remove}, {@code c()}'s clear becomes {@link #clear}, and {@code a(j)} prepends
 * {@code List r = tryQuery(set, j.a().getIndex(), j.b().getIndex()); if (r != null) return r;}
 * ahead of the untouched original scan body. Any index anomaly returns {@code null} and the
 * original O(T) scan answers, so correctness is structural, not asserted.</p>
 *
 * <p>Signatures are raw JDK types on purpose: the woven descriptors are raw, and this class must
 * carry no compile-time reference to any host class so it can never perturb host class loading
 * during a transform callback.</p>
 *
 * <p>Consistency invariants:</p>
 * <ul>
 *   <li>Every element that entered through {@link #add} has its three undirected endpoint-index
 *       pairs recorded in {@code keys} (identity-keyed on the stored object). Recording is
 *       unconditional on a successful add, so a later dirty-set rebuild still finds them.</li>
 *   <li>Buckets are only touched when the precheck {@code sz == size-1} holds; any unwoven
 *       mutation (an {@code iterator().remove()} shrinking the set) breaks the precheck and the
 *       index falls back: dirty triggers a rebuild, an unweaved element makes the index dead.</li>
 *   <li>{@link #remove} runs the native set operation first. It deindexes the argument
 *       only when cardinality and a scan of surviving identities prove it is the single
 *       missing recorded object. Equal-but-distinct arguments, stale state or unexpected
 *       survivors mark the index dirty; the next query rebuilds from the live set.</li>
 *   <li>Bookkeeping never throws into the host: everything after the real set operation is
 *       guarded; failures only mark the index dirty or dead.</li>
 *   <li>{@code STATES} uses weak identity keys, not {@code WeakHashMap} or set value equality:
 *       {@code LinkedHashSet.hashCode()} is AbstractSet's O(T) element sum and set equality is
 *       O(T²), so keying by value would reintroduce the scan being removed. The pinned official
 *       triangles/points held in the state have no back-reference to the owning set.</li>
 * </ul>
 */
@SuppressWarnings({"rawtypes", "unchecked"}) // the woven descriptors ARE raw
public final class TriangulationEdgeIndex {

    private TriangulationEdgeIndex() {}

    /** Undirected endpoint-index key; safe for negative sentinel indices. */
    static long key(final int i, final int j) {
        return ((long) Math.min(i, j) << 32) | (Math.max(i, j) & 0xffffffffL);
    }

    static final class St {
        final HashMap<Long, ArrayList<Object>> byKey = new HashMap<>();
        final IdentityHashMap<Object, long[]> keys = new IdentityHashMap<>();
        int sz;
        boolean dirty = true;
        boolean dead;
    }

    /** Weak identity registration on the target's final {@code b} field instance. */
    private static final ReferenceQueue<LinkedHashSet> COLLECTED = new ReferenceQueue<>();
    static final Map<SetKey, St> STATES = new HashMap<>();

    static final class SetKey extends WeakReference<LinkedHashSet> {
        private final int hash;

        SetKey(final LinkedHashSet set, final ReferenceQueue<LinkedHashSet> queue) {
            super(java.util.Objects.requireNonNull(set), queue);
            hash = System.identityHashCode(set);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        @SuppressWarnings("ReferenceEquality") // identity is the registry's explicit key contract
        public boolean equals(final Object other) {
            if (this == other) return true; // allows removal after the reference is cleared
            if (!(other instanceof SetKey key)) return false;
            final LinkedHashSet set = get();
            return set != null && set == key.get();
        }
    }

    private static void expungeCollected() {
        Reference<? extends LinkedHashSet> key;
        while ((key = COLLECTED.poll()) != null) STATES.remove(key);
    }

    // Synchronize only the process-wide registry, not the original set's operations.
    // Independent TriangleList instances may be used concurrently; a single original
    // LinkedHashSet retains its original thread-safety contract.
    static synchronized St st(final LinkedHashSet s) {
        expungeCollected();
        final SetKey key = new SetKey(s, COLLECTED);
        St t = STATES.get(key);
        if (t == null) {
            t = new St();
            STATES.put(key, t);
        }
        Reference.reachabilityFence(s);
        return t;
    }

    private static synchronized void discard(final LinkedHashSet s) {
        expungeCollected();
        final St t = STATES.remove(new SetKey(s, null));
        if (t != null) {
            t.byKey.clear();
            t.keys.clear();
            // Once inconsistency was proven, keep only a weak-keyed tombstone:
            // clear may release triangles, but must not reactivate a dead index.
            if (t.dead) STATES.put(new SetKey(s, COLLECTED), t);
        }
        Reference.reachabilityFence(s);
    }

    private static synchronized void invalidate(final LinkedHashSet s) {
        expungeCollected();
        final St t = STATES.get(new SetKey(s, null));
        if (t != null) t.dead = true;
        Reference.reachabilityFence(s);
    }

    /**
     * Replaces {@code LinkedHashSet.add}. Runs the real add first (its return value is the
     * official one), then bookkeeping that can only degrade — never alter — set semantics.
     */
    public static boolean add(
            final LinkedHashSet s, final Object tri, final int ia, final int ib, final int ic) {
        final boolean changed = s.add(tri);
        try {
            final St t = st(s);
            if (changed && !t.dead) {
                final long k1 = key(ia, ib), k2 = key(ib, ic), k3 = key(ic, ia);
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
        } catch (Throwable bookkeeping) {
            FatalErrors.rethrowIfFatal(bookkeeping);
            invalidate(s);
        }
        return changed;
    }

    /**
     * Preserves the exact native removal (including its victim in a treeified hash bucket).
     * Bookkeeping scans only identities, never invokes element equality a second time.
     */
    public static boolean remove(final LinkedHashSet s, final Object tri) {
        final boolean removed = s.remove(tri);
        if (!removed) return false;
        try {
            final St t = st(s);
            final Object victim = tri;
            boolean exactVictim = !t.dead && !t.dirty && t.sz == s.size() + 1
                    && t.keys.size() == s.size() + 1 && t.keys.containsKey(tri);
            if (exactVictim) {
                for (final Object survivor : s) {
                    if (survivor == tri || !t.keys.containsKey(survivor)) {
                        exactVictim = false;
                        break;
                    }
                }
            }
            // All n-1 surviving identities belong to the n recorded identities, and tri
            // is absent: tri is exactly the missing entry. Otherwise do not guess from
            // equals or the argument's vertex indices; rebuild prunes the actual victim.
            final long[] ks = exactVictim ? t.keys.remove(victim) : null;
            if (ks == null || t.dead || t.dirty) {
                t.dirty = true; // unknown keys or stale index -> resync on next query
            } else {
                for (int i = 0; i < 3; i++) {
                    if (i > 0 && (ks[i] == ks[0] || (i == 2 && ks[2] == ks[1]))) continue;
                    final ArrayList<Object> bucket = t.byKey.get(ks[i]);
                    if (bucket == null) {
                        t.dirty = true;
                        break;
                    }
                    int at = -1;
                    for (int j = 0; j < bucket.size(); j++) {
                        if (bucket.get(j) == victim) {
                            at = j;
                            break;
                        }
                    }
                    if (at < 0) {
                        t.dirty = true;
                        break;
                    }
                    bucket.remove(at);
                    if (bucket.isEmpty()) t.byKey.remove(ks[i]);
                }
            }
            t.sz = s.size();
        } catch (Throwable bookkeeping) {
            FatalErrors.rethrowIfFatal(bookkeeping);
            invalidate(s);
        }
        return true;
    }

    /** Replaces {@code LinkedHashSet.clear}. */
    public static void clear(final LinkedHashSet s) {
        s.clear();
        try {
            discard(s);
        } catch (Throwable bookkeeping) {
            FatalErrors.rethrowIfFatal(bookkeeping);
            // An empty set needs no index; a later add rebuilds state from scratch.
        }
    }

    /**
     * Woven into {@code a(j)} ahead of the original body.
     *
     * @return the insertion-ordered hit list snapshot, or {@code null} meaning "run the
     *     original scan"
     */
    public static List tryQuery(final LinkedHashSet s, final int ja, final int jb) {
        final St t;
        try {
            t = st(s);
        } catch (Throwable stateFailure) {
            FatalErrors.rethrowIfFatal(stateFailure);
            return null;
        }
        if (t.dead) return null;
        try {
            if (t.dirty || t.sz != s.size()) {
                if (!rebuild(t, s)) {
                    t.dead = true;
                    return null;
                }
            }
            final ArrayList<Object> bucket = t.byKey.get(key(ja, jb));
            final ArrayList<Object> out = new ArrayList<>(bucket == null ? 4 : bucket.size());
            if (bucket != null) out.addAll(bucket);
            return out;
        } catch (Throwable indexFailure) {
            FatalErrors.rethrowIfFatal(indexFailure);
            t.dead = true; // never trust a crashed index again
            return null;
        }
    }

    private static void put(final St t, final long k, final Object tri) {
        t.byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(tri);
    }

    /**
     * Rebuilds the index from the live set, preserving insertion order in every bucket and
     * pruning keys of removed elements.
     *
     * @return false when any element lacks recorded keys (an unwoven-add element); the caller
     *     marks the index dead
     */
    static boolean rebuild(final St t, final LinkedHashSet s) {
        final HashMap<Long, ArrayList<Object>> map = new HashMap<>();
        final IdentityHashMap<Object, Boolean> live = new IdentityHashMap<>();
        for (final Object o : s) {
            final long[] ks = t.keys.get(o);
            if (ks == null) return false; // unwoven-add element: cannot index it faithfully
            live.put(o, Boolean.TRUE);
            final long k1 = ks[0], k2 = ks[1], k3 = ks[2];
            map.computeIfAbsent(k1, x -> new ArrayList<>()).add(o);
            if (k2 != k1) map.computeIfAbsent(k2, x -> new ArrayList<>()).add(o);
            if (k3 != k1 && k3 != k2) map.computeIfAbsent(k3, x -> new ArrayList<>()).add(o);
        }
        t.byKey.clear();
        t.byKey.putAll(map);
        t.keys.keySet().retainAll(live.keySet());
        t.sz = s.size();
        t.dirty = false;
        return true;
    }
}
