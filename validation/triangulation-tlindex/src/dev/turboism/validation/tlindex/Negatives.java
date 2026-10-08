package dev.turboism.validation.tlindex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Deliberately-broken candidate variants, each injected with exactly one
 * defect on its discriminating domain. Every one must be REJECTED by the
 * SelfCheck comparator (element identity + order on every a(j) call).
 * They model the realistic failure modes of an edge index:
 *   directedKeys       - misses reversed-direction queries
 *   noKeyDedup         - degenerate triangles double-listed
 *   noDirtyOnRemove    - stale entries after removes (incl. the
 *                        l.equals-vs-keys divergence hazard)
 *   noSidePathCheck    - iterator.remove()/other side paths undetected
 *   unorderedBucket    - HashSet buckets lose insertion order
 *   coordKeys          - TriPoint.equals ignores index; a coord-domain key
 *                        merges distinct edges sharing coordinates
 *   sharedBucketList   - returning the internal bucket lets callers corrupt
 *                        the index (official a(j) returns a fresh list)
 */
public final class Negatives {
    private Negatives() {}

    static long ukey(int i, int j) {              // undirected (correct shape)
        return ((long) Math.min(i, j) << 32) | (Math.max(i, j) & 0xffffffffL);
    }
    static long dkey(int i, int j) {              // directed (BROKEN)
        return ((long) i << 32) | (j & 0xffffffffL);
    }

    /** Parametric broken index. Each boolean switches one defect. */
    static IdxList.Ops broken(boolean directed, boolean dedup,
            boolean dirtyOnRemove, boolean sidePathCheck,
            boolean orderedBucket, boolean sharedList) {
        return new IdxList.Ops() {
            final class St {
                final Map<Long, java.util.Collection<Object>> m = new HashMap<>();
                final IdentityHashMap<Object, long[]> keys = new IdentityHashMap<>();
                int sz; boolean dirty = true;
            }
            final WeakHashMap<LinkedHashSet, St> S = new WeakHashMap<>();
            St st(LinkedHashSet s) {
                St t = S.get(s);
                if (t == null) { t = new St(); S.put(s, t); }
                return t;
            }

            public boolean add(LinkedHashSet s, Object tri, int ia, int ib, int ic) {
                boolean ch = s.add(tri);
                St t = st(s);
                if (ch) {
                    long k1 = directed ? dkey(ia, ib) : ukey(ia, ib);
                    long k2 = directed ? dkey(ib, ic) : ukey(ib, ic);
                    long k3 = directed ? dkey(ic, ia) : ukey(ic, ia);
                    t.keys.put(tri, new long[] {k1, k2, k3});
                    if (!t.dirty && (!sidePathCheck || t.sz == s.size() - 1)) {
                        putAll(t.m, t.keys.get(tri), tri, dedup, orderedBucket);
                    } else {
                        t.dirty = true;
                    }
                    t.sz = s.size();
                }
                return ch;
            }

            public boolean remove(LinkedHashSet s, Object tri) {
                boolean ch = s.remove(tri);
                if (ch && dirtyOnRemove) st(s).dirty = true;
                else if (ch) st(s).sz--;
                return ch;
            }

            public void clear(LinkedHashSet s) {
                s.clear();
                St t = st(s);
                t.m.clear(); t.keys.clear(); t.sz = 0; t.dirty = false;
            }

            @SuppressWarnings("unchecked")
            public List tryQuery(LinkedHashSet s, int ja, int jb) {
                St t = st(s);
                if (t.dirty || (sidePathCheck && t.sz != s.size())) {
                    if (!rebuild(t, s, dedup, orderedBucket)) return null;
                }
                long k = directed ? dkey(ja, jb) : ukey(ja, jb);
                java.util.Collection<Object> b = t.m.get(k);
                if (sharedList)
                    return b == null ? new ArrayList<>() : (List) b;   // alias!
                ArrayList<Object> out = new ArrayList<>(b == null ? 0 : b.size());
                if (b != null) out.addAll(b);
                return out;
            }

            boolean rebuild(St t, LinkedHashSet s, boolean dedup, boolean ordered) {
                Map<Long, java.util.Collection<Object>> m = new HashMap<>();
                for (Object o : s) {
                    long[] ks = t.keys.get(o);
                    if (ks == null) return false;
                    putAll(m, ks, o, dedup, ordered);
                }
                t.m.clear(); t.m.putAll(m);
                t.sz = s.size(); t.dirty = false;
                return true;
            }

            void putAll(Map<Long, java.util.Collection<Object>> m, long[] ks,
                        Object o, boolean dedup, boolean ordered) {
                for (int i = 0; i < ks.length; i++) {
                    if (dedup) {
                        boolean seen = false;
                        for (int j = 0; j < i; j++) if (ks[j] == ks[i]) { seen = true; break; }
                        if (seen) continue;
                    }
                    m.computeIfAbsent(ks[i],
                        x -> ordered ? new ArrayList<>() : new HashSet<>()).add(o);
                }
            }
        };
    }

    /* ---------- named broken variants ---------- */

    public static IdxList.Ops directedKeys() {
        return broken(true, true, true, true, true, false);
    }
    public static IdxList.Ops noKeyDedup() {
        return broken(false, false, true, true, true, false);
    }
    public static IdxList.Ops noSidePathCheck() {
        return broken(false, true, true, false, true, false);
    }
    public static IdxList.Ops unorderedBucket() {
        return broken(false, true, true, true, false, false);
    }
    public static IdxList.Ops sharedBucketList() {
        return broken(false, true, true, true, true, true);
    }

    /** BROKEN: remove leaves buckets untouched and never marks dirty. */
    public static IdxList.Ops noDirtyOnRemove() {
        return new IdxList.Ops() {
            final WeakHashMap<LinkedHashSet, Map<Long, ArrayList<Object>>> M = new WeakHashMap<>();
            Map<Long, ArrayList<Object>> m(LinkedHashSet s) {
                return M.computeIfAbsent(s, x -> new HashMap<>());
            }
            public boolean add(LinkedHashSet s, Object tri, int a, int b, int c) {
                boolean ch = s.add(tri);
                if (ch) {
                    Map<Long, ArrayList<Object>> m = m(s);
                    for (long k : new long[] {ukey(a, b), ukey(b, c), ukey(c, a)})
                        m.computeIfAbsent(k, x -> new ArrayList<>()).add(tri);
                }
                return ch;
            }
            public boolean remove(LinkedHashSet s, Object tri) {
                return s.remove(tri);                    // BROKEN: stale index
            }
            public void clear(LinkedHashSet s) { s.clear(); m(s).clear(); }
            public List tryQuery(LinkedHashSet s, int ja, int jb) {
                ArrayList<Object> b = m(s).get(ukey(ja, jb));
                ArrayList<Object> out = new ArrayList<>(b == null ? 0 : b.size());
                if (b != null) out.addAll(b);
                return out;
            }
        };
    }

    /** BROKEN: key the index in TriPoint.equals' domain (coordinates), not
     *  getIndex(). Vertices sharing (x,y) but carrying different indices
     *  merge into one bucket - exactly the equals-vs-keys hazard. Emulated
     *  by collapsing high indices to a shared coord-pool key. */
    public static IdxList.Ops coordKeys() {
        return new IdxList.Ops() {
            final WeakHashMap<LinkedHashSet, Map<Long, ArrayList<Object>>> M = new WeakHashMap<>();
            Map<Long, ArrayList<Object>> m(LinkedHashSet s) {
                return M.computeIfAbsent(s, x -> new HashMap<>());
            }
            long badKey(int i, int j) {          // emulate coord-domain keys:
                return ukey(Math.floorMod(i, 7), Math.floorMod(j, 7));
            }                                    // indices congruent mod 7 merge
                                                 // (the same-coords hazard)
            public boolean add(LinkedHashSet s, Object tri, int a, int b, int c) {
                boolean ch = s.add(tri);
                if (ch) {
                    Map<Long, ArrayList<Object>> m = m(s);
                    for (long k : new long[] {badKey(a, b), badKey(b, c), badKey(c, a)})
                        m.computeIfAbsent(k, x -> new ArrayList<>()).add(tri);
                }
                return ch;
            }
            public boolean remove(LinkedHashSet s, Object tri) { return s.remove(tri); }
            public void clear(LinkedHashSet s) { s.clear(); m(s).clear(); }
            public List tryQuery(LinkedHashSet s, int ja, int jb) {
                ArrayList<Object> b = m(s).get(badKey(ja, jb));
                ArrayList<Object> out = new ArrayList<>(b == null ? 0 : b.size());
                if (b != null) out.addAll(b);
                return out;
            }
        };
    }
}
