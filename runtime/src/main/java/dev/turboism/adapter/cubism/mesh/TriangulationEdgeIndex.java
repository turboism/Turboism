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
import java.util.concurrent.ConcurrentHashMap;

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
 *   <li>{@link #remove} runs the native set operation first. Up to eight known removal
 *       arguments await one shared identity scan before the next indexed answer. Only
 *       cardinality and absence from the surviving set prove the actual removed identities;
 *       ambiguous victims, reinsertion or drift make the state dirty. Pending buckets are
 *       never used to answer a query or shortcut membership.</li>
 *   <li>Bookkeeping never throws into the host: everything after the real set operation is
 *       guarded; failures only mark the index dirty or dead.</li>
 *   <li>{@code STATES} uses weak identity keys, not {@code WeakHashMap} or set value equality:
 *       {@code LinkedHashSet.hashCode()} is AbstractSet's O(T) element sum and set equality is
 *       O(T²), so keying by value would reintroduce the scan being removed. The pinned official
 *       triangles/points held in the state have no back-reference to the owning set.</li>
 *   <li>The registry is a {@link java.util.concurrent.ConcurrentHashMap}, so a registered set
 *       resolves without a class monitor. Independent triangulations therefore never serialize
 *       on each other, and only registration, discard and invalidation mutate the registry.</li>
 * </ul>
 */
@SuppressWarnings({"rawtypes", "unchecked"}) // the woven descriptors ARE raw
public final class TriangulationEdgeIndex {

    private TriangulationEdgeIndex() {}

    /** Undirected endpoint-index key; safe for negative sentinel indices. */
    static long key(final int i, final int j) {
        return ((long) Math.min(i, j) << 32) | (Math.max(i, j) & 0xffffffffL);
    }

    // Long.hashCode reduces an endpoint pair to i ^ j: neighboring point indices
    // produce huge collision buckets. Mix all bits; equality still uses the exact pair.
    private static int edgeHash(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (int) (value ^ (value >>> 32));
    }

    private static final class EdgeKey {
        private final long value;
        private final int hash;

        EdgeKey(final long value) {
            this.value = value;
            hash = edgeHash(value);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof EdgeKey key && value == key.value
                    || other instanceof EdgeLookup lookup && value == lookup.value;
        }
    }

    private static final class EdgeLookup {
        private long value;
        private int hash;

        void bind(final long value) {
            this.value = value;
            hash = edgeHash(value);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof EdgeKey key && value == key.value
                    || other instanceof EdgeLookup lookup && value == lookup.value;
        }
    }

    static final class St {
        final HashMap<EdgeKey, ArrayList<Object>> byKey = new HashMap<>();
        private final EdgeLookup lookup = new EdgeLookup();
        final IdentityHashMap<Object, long[]> keys = new IdentityHashMap<>();
        final Object[] pending = new Object[8];
        int pendingSize;
        int sz;
        boolean dirty = true;
        boolean dead;
    }

    /** Weak identity registration on the target's final {@code b} field instance. */
    private static final ReferenceQueue<LinkedHashSet> COLLECTED = new ReferenceQueue<>();

    /**
     * Process-wide registry of index states, keyed by weak identity of the owning set.
     *
     * <p>A registered set answers {@link #st} with a plain map read, so independent triangulations
     * no longer serialize on one class monitor. Only a miss, {@link #discard} and
     * {@link #invalidate} take the mutation path, which drains the collection queue and updates the
     * entry atomically. The transient {@link LookupKey} probe is never stored: the map holds only
     * {@link SetKey} values, so no strong owner reference can leak.</p>
     */
    static final ConcurrentHashMap<SetKey, St> STATES = new ConcurrentHashMap<>();

    /**
     * Read-only probe for {@link #STATES}.
     *
     * <p>It carries the same identity hash and reference equality as {@link SetKey} but holds no
     * queue registration, so it can never become a map key and no strong owner reference is
     * retained. Each thread reuses one instance and clears it after every lookup.</p>
     */
    private static final class LookupKey {
        private int hash;
        private LinkedHashSet set;

        void bind(final LinkedHashSet set) {
            this.set = java.util.Objects.requireNonNull(set);
            hash = System.identityHashCode(set);
        }

        void clear() {
            set = null;
            hash = 0;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        @SuppressWarnings("ReferenceEquality") // identity is the registry's explicit key contract
        public boolean equals(final Object other) {
            if (this == other) return true;
            if (other instanceof SetKey key) return set == key.get();
            return other instanceof LookupKey key && set == key.set;
        }
    }

    /**
     * Reusable per-thread probe, so a hot lookup allocates nothing.
     *
     * <p>The owner reference is cleared before the probe leaves the calling method: a parked
     * thread must not keep a released set alive. Only the lookup path uses this probe; the
     * registering path allocates a {@link SetKey} with its queue registration.</p>
     */
    private static final ThreadLocal<LookupKey> PROBE = ThreadLocal.withInitial(() -> new LookupKey());

    private static LookupKey bind(final LinkedHashSet set) {
        final LookupKey probe = PROBE.get();
        probe.bind(set);
        return probe;
    }

    private static void unbind(final LookupKey probe) {
        probe.clear();
    }

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
            if (other instanceof LookupKey probe) return probe.set == get();
            if (!(other instanceof SetKey key)) return false;
            final LinkedHashSet set = get();
            return set != null && set == key.get();
        }
    }

    /** Drains collected keys; an empty queue costs one unsynchronized read. */
    private static void expungeCollected() {
        Reference<? extends LinkedHashSet> key;
        while ((key = COLLECTED.poll()) != null) STATES.remove(key);
    }

    /**
     * Returns the index state of {@code s}, registering one on first use.
     *
     * <p>A registered set resolves through one concurrent map read, so independent triangulations
     * no longer serialize on a class monitor. Only a miss, {@link #discard} and {@link #invalidate}
     * create or mutate an entry.</p>
     */
    @SuppressWarnings("CollectionIncompatibleType") // Probe and stored weak key share identity equals/hash.
    static St st(final LinkedHashSet s) {
        final LinkedHashSet owner = java.util.Objects.requireNonNull(s);
        final LookupKey probe = bind(owner);
        final St existing;
        try {
            existing = STATES.get(probe);
        } finally {
            unbind(probe);
        }
        if (existing != null) {
            Reference.reachabilityFence(owner);
            return existing;
        }
        return register(owner);
    }

    private static St register(final LinkedHashSet s) {
        expungeCollected();
        final St created = new St();
        final St existing = STATES.putIfAbsent(new SetKey(s, COLLECTED), created);
        Reference.reachabilityFence(s);
        return existing != null ? existing : created;
    }

    @SuppressWarnings("CollectionIncompatibleType") // ConcurrentHashMap.remove accepts the transient equal-key probe.
    private static void discard(final LinkedHashSet s) {
        expungeCollected();
        final LookupKey probe = bind(s);
        final St removed;
        try {
            removed = STATES.remove(probe);
        } finally {
            unbind(probe);
        }
        if (removed != null) {
            removed.byKey.clear();
            removed.keys.clear();
            clearPending(removed);
            // Once inconsistency was proven, keep only a weak-keyed tombstone:
            // clear may release triangles, but must not reactivate a dead index.
            if (removed.dead) STATES.putIfAbsent(new SetKey(s, COLLECTED), removed);
        }
        Reference.reachabilityFence(s);
    }

    @SuppressWarnings("CollectionIncompatibleType") // Never store the strong lookup probe.
    private static void invalidate(final LinkedHashSet s) {
        expungeCollected();
        final LookupKey probe = bind(s);
        final St existing;
        try {
            existing = STATES.get(probe);
        } finally {
            unbind(probe);
        }
        if (existing != null) {
            existing.dead = true;
            clearPending(existing);
        }
        Reference.reachabilityFence(s);
    }

    /**
     * Replaces {@code LinkedHashSet.add}. Runs the real add first (its return value is the
     * official one), then bookkeeping that can only degrade — never alter — set semantics.
     */
    public static boolean add(final LinkedHashSet s, final Object tri, final int ia, final int ib, final int ic) {
        final boolean changed = s.add(tri);
        try {
            final St t = st(s);
            if (changed && !t.dead) {
                final boolean incremental =
                        !t.dirty && t.sz == s.size() - 1 && t.keys.size() == t.sz + t.pendingSize && !isPending(t, tri);
                final long k1 = key(ia, ib), k2 = key(ib, ic), k3 = key(ic, ia);
                t.keys.put(tri, new long[] {k1, k2, k3});
                if (incremental) {
                    put(t, k1, tri);
                    if (k2 != k1) put(t, k2, tri);
                    if (k3 != k1 && k3 != k2) put(t, k3, tri);
                } else {
                    markDirty(t);
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
     * Bookkeeping defers a bounded batch of identities, never invokes element equality again.
     */
    public static boolean remove(final LinkedHashSet s, final Object tri) {
        final boolean removed = s.remove(tri);
        if (!removed) return false;
        try {
            final St t = st(s);
            if (!t.dead
                    && !t.dirty
                    && t.sz == s.size() + 1
                    && t.keys.size() == t.sz + t.pendingSize
                    && t.keys.containsKey(tri)
                    && !isPending(t, tri)
                    && t.pendingSize < t.pending.length) {
                t.pending[t.pendingSize++] = tri;
            } else {
                markDirty(t);
            }
            t.sz = s.size();
        } catch (Throwable bookkeeping) {
            FatalErrors.rethrowIfFatal(bookkeeping);
            invalidate(s);
        }
        return true;
    }

    @SuppressWarnings("ReferenceEquality") // Pending identities are not geometric equality keys.
    private static boolean isPending(final St t, final Object tri) {
        for (int i = 0; i < t.pendingSize; i++) {
            if (t.pending[i] == tri) return true;
        }
        return false;
    }

    private static void clearPending(final St t) {
        java.util.Arrays.fill(t.pending, 0, t.pendingSize, null);
        t.pendingSize = 0;
    }

    private static void markDirty(final St t) {
        t.dirty = true;
        clearPending(t);
    }

    /** Resolve known native removals once before any indexed result becomes visible. */
    @SuppressWarnings("ReferenceEquality") // Prove physical absence without repeating host equals.
    private static boolean settle(final St t, final LinkedHashSet s) {
        if (t.dirty || t.sz != s.size() || t.keys.size() != s.size() + t.pendingSize) {
            markDirty(t);
            return false;
        }
        if (t.pendingSize == 1) {
            final Object first = t.pending[0];
            for (final Object survivor : s) {
                if (survivor == first) {
                    markDirty(t);
                    return false;
                }
            }
        } else if (t.pendingSize == 2) {
            final Object first = t.pending[0], second = t.pending[1];
            for (final Object survivor : s) {
                if (survivor == first || survivor == second) {
                    markDirty(t);
                    return false;
                }
            }
        } else {
            for (final Object survivor : s) {
                if (isPending(t, survivor)) {
                    markDirty(t);
                    return false;
                }
            }
        }
        // The clean-state cardinality covers every live identity plus these distinct
        // absent arguments. If an argument survives, native remove picked an equal
        // different victim; the dirty path above rebuilds from the actual set instead.
        for (int i = 0; i < t.pendingSize; i++) {
            if (!deindex(t, t.pending[i])) {
                markDirty(t);
                return false;
            }
        }
        clearPending(t);
        return true;
    }

    private static boolean deindex(final St t, final Object victim) {
        final long[] ks = t.keys.remove(victim);
        if (ks == null) return false;
        for (int i = 0; i < 3; i++) {
            if (i > 0 && (ks[i] == ks[0] || (i == 2 && ks[2] == ks[1]))) continue;
            if (!removeFromBucket(t, ks[i], victim)) return false;
        }
        return true;
    }

    /**
     * Positive identity shortcut for the pinned host's constant-hash triangles.
     * Unknown identities still require native geometric equality; never infer a negative
     * answer from the index. Cardinality and cleanliness use the same mutation protocol
     * as removal: all additions/clears are woven, iterator removal only shrinks the set.
     */
    public static boolean contains(final LinkedHashSet s, final Object tri) {
        try {
            final St t = st(s);
            if (!t.dead && t.pendingSize > 0) settle(t, s);
            if (!t.dead && !t.dirty && t.sz == s.size() && t.keys.size() == s.size() && t.keys.containsKey(tri))
                return true;
        } catch (Throwable bookkeeping) {
            FatalErrors.rethrowIfFatal(bookkeeping);
            if (s != null) invalidate(s); // settle may have partially changed bookkeeping
        }
        return s.contains(tri);
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
            if (t.pendingSize > 0) settle(t, s);
            if (t.dirty || t.sz != s.size()) {
                if (!rebuild(t, s)) {
                    t.dead = true;
                    return null;
                }
            }
            final ArrayList<Object> bucket = bucket(t, key(ja, jb));
            // Collection construction adopts its private toArray copy. Preallocating
            // another backing array and calling addAll would copy through two arrays.
            return bucket == null ? new ArrayList<>(4) : new ArrayList<>(bucket);
        } catch (Throwable indexFailure) {
            FatalErrors.rethrowIfFatal(indexFailure);
            t.dead = true; // never trust a crashed index again
            clearPending(t);
            return null;
        }
    }

    private static void put(final St t, final long k, final Object tri) {
        put(t.byKey, t.lookup, k, tri);
    }

    @SuppressWarnings("CollectionIncompatibleType") // Equal-key probe avoids transient boxed keys.
    private static ArrayList<Object> bucket(final St t, final long k) {
        // Concurrent read-only queries on a clean set remain safe: one query must
        // not rebind the probe while another is inside HashMap.get.
        synchronized (t.lookup) {
            t.lookup.bind(k);
            return t.byKey.get(t.lookup);
        }
    }

    @SuppressWarnings({"CollectionIncompatibleType", "ReferenceEquality"}) // Exact key/physical victim.
    private static boolean removeFromBucket(final St t, final long k, final Object victim) {
        synchronized (t.lookup) {
            t.lookup.bind(k);
            final ArrayList<Object> bucket = t.byKey.get(t.lookup);
            if (bucket == null) return false;
            for (int i = 0; i < bucket.size(); i++) {
                if (bucket.get(i) == victim) {
                    bucket.remove(i);
                    // Keep the probe bound until empty-bucket deletion: no second
                    // hash mix or monitor entry, and no reader can rebind it midway.
                    if (bucket.isEmpty()) t.byKey.remove(t.lookup);
                    return true;
                }
            }
            return false;
        }
    }

    @SuppressWarnings("CollectionIncompatibleType") // The mutable probe is never stored in the map.
    private static void put(
            final HashMap<EdgeKey, ArrayList<Object>> map, final EdgeLookup lookup, final long k, final Object tri) {
        synchronized (lookup) {
            lookup.bind(k);
            ArrayList<Object> bucket = map.get(lookup);
            if (bucket == null) {
                // Most mesh edges have one or two incident triangles. Larger
                // nonmanifold buckets still grow normally and preserve their order.
                bucket = new ArrayList<>(2);
                map.put(new EdgeKey(k), bucket);
            }
            bucket.add(tri);
        }
    }

    /**
     * Rebuilds the index from the live set, preserving insertion order in every bucket and
     * pruning keys of removed elements.
     *
     * @return false when any element lacks recorded keys (an unwoven-add element); the caller
     *     marks the index dead
     */
    static boolean rebuild(final St t, final LinkedHashSet s) {
        clearPending(t);
        final HashMap<EdgeKey, ArrayList<Object>> map = new HashMap<>();
        final EdgeLookup lookup = new EdgeLookup();
        final IdentityHashMap<Object, Boolean> live = new IdentityHashMap<>();
        for (final Object o : s) {
            final long[] ks = t.keys.get(o);
            if (ks == null) return false; // unwoven-add element: cannot index it faithfully
            live.put(o, Boolean.TRUE);
            final long k1 = ks[0], k2 = ks[1], k3 = ks[2];
            put(map, lookup, k1, o);
            if (k2 != k1) put(map, lookup, k2, o);
            if (k3 != k1 && k3 != k2) put(map, lookup, k3, o);
        }
        t.byKey.clear();
        t.byKey.putAll(map);
        t.keys.keySet().retainAll(live.keySet());
        t.sz = s.size();
        t.dirty = false;
        return true;
    }
}
