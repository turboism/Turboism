package dev.turboism.validation.tlindex;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import dev.turboism.validation.tlindex.Shadow.RefTriangleList;
import dev.turboism.validation.tlindex.Shadow.ShadowJ;
import dev.turboism.validation.tlindex.Shadow.ShadowL;
import dev.turboism.validation.tlindex.Shadow.ShadowTriPoint;

/**
 * Event-by-event differential driver: runs the reference (brute-force
 * ShadowTriangleList) and the candidate (IdxList over Bridge) on the SAME
 * ShadowL references and identical operation streams, and compares every
 * observable result exactly:
 *  - a(j) result: size + element IDENTITY sequence (order and duplicates).
 *  - a(l)/b(l) return booleans, c(l) containment, size().
 *  - iterator() snapshot identity order.
 * Any divergence throws AssertionError. Negative controls (Negatives.*)
 * must each be REJECTED on their discriminating stream.
 */
public final class SelfCheck {
    private static int checks = 0;
    private static void ok(boolean cond, String name) {
        checks++;
        if (!cond) throw new AssertionError("CHECK FAILED: " + name);
    }

    /* ---------------- op stream ---------------- */

    /** One driver op. `q` queries edge (ia,ib) of shared point pool entries;
     *  flips model the Lawson pattern: query neighbours of a shared edge,
     *  remove both, add the two diagonal-flipped triangles. */
    interface Stream { void run(Ctx c); }

    static final class Ctx {
        final RefTriangleList ref = new RefTriangleList();
        final IdxList idx;
        final List<ShadowTriPoint> pool = new ArrayList<>();   // vertex pool
        final List<ShadowL> live = new ArrayList<>();          // mirror set view
        Ctx(IdxList idx) { this.idx = idx; }
        ShadowTriPoint pt(int i) { return pool.get(Math.floorMod(i, pool.size())); }
        ShadowJ edge(int ia, int ib) { return new ShadowJ(pt(ia), pt(ib)); }
    }

    /* ------------- comparison ------------- */

    static void sameList(String tag, List<ShadowL> a, List<ShadowL> b) {
        checks++;
        if (a.size() != b.size())
            throw new AssertionError("SIZE " + tag + " ref=" + a.size() + " idx=" + b.size());
        for (int i = 0; i < a.size(); i++)
            if (a.get(i) != b.get(i))
                throw new AssertionError("ORDER/IDENTITY " + tag + " slot " + i);
    }

    static void sameBool(String tag, boolean a, boolean b) {
        checks++;
        if (a != b) throw new AssertionError("BOOL " + tag + " ref=" + a + " idx=" + b);
    }

    static void query(Ctx c, int ia, int ib) {
        ShadowJ e = c.edge(ia, ib);
        sameList("a(j)(" + ia + "," + ib + ")", c.ref.a(e), c.idx.a(e));
    }

    static void addTri(Ctx c, ShadowL t) {
        sameBool("a(l)", c.ref.a(t), c.idx.a(t));
    }

    static void removeTri(Ctx c, ShadowL t) {
        sameBool("b(l)", c.ref.b(t), c.idx.b(t));
    }

    static void clearAll(Ctx c) { c.ref.c(); c.idx.c(); }

    static void sameIter(Ctx c) {
        ArrayList<ShadowL> a = new ArrayList<>(), b = new ArrayList<>();
        c.ref.iterator().forEachRemaining(a::add);
        c.idx.iterator().forEachRemaining(b::add);
        sameList("iterator()", a, b);
    }

    /* ------------- streams ------------- */

    /** Fill the pool: n real vertices + sentinel negatives (like dumps). */
    static void pool(Ctx c, int n) {
        for (int i = 0; i < n; i++)
            c.pool.add(new ShadowTriPoint((i * 37) % 101, (i * 53) % 97, i));
        c.pool.add(new ShadowTriPoint(-999, -999, -1));
        c.pool.add(new ShadowTriPoint(-998, -998, -2));
        c.pool.add(new ShadowTriPoint(-997, -997, -3));
    }

    /** Grid-mesh growth + Lawson flips + dense interleaved queries.
     *  Each inserted quad adds two triangles; each flip does
     *  a(j)-neighbour-query, remove both, add the two flipped triangles. */
    static Stream meshFlip(long seed, int side, int flipRounds) {
        return c -> {
            pool(c, side * side);
            java.util.Random r = new java.util.Random(seed);
            // build a grid mesh: cell (x,y) -> tris (v00,v10,v11),(v00,v11,v01)
            java.util.function.IntBinaryOperator at = (x, y) -> y * side + x;
            for (int y = 0; y < side - 1; y++) {
                for (int x = 0; x < side - 1; x++) {
                    ShadowL t1 = new ShadowL(c.pt(at.applyAsInt(x, y)), c.pt(at.applyAsInt(x + 1, y)), c.pt(at.applyAsInt(x + 1, y + 1)));
                    ShadowL t2 = new ShadowL(c.pt(at.applyAsInt(x, y)), c.pt(at.applyAsInt(x + 1, y + 1)), c.pt(at.applyAsInt(x, y + 1)));
                    addTri(c, t1); addTri(c, t2);
                    c.live.add(t1); c.live.add(t2);
                    // query the diagonal edge + two border edges after every add
                    query(c, at.applyAsInt(x, y), at.applyAsInt(x + 1, y + 1));
                    query(c, at.applyAsInt(x, y), at.applyAsInt(x + 1, y));
                    query(c, at.applyAsInt(x, y + 1), at.applyAsInt(x + 1, y + 1));
                }
            }
            sameIter(c);
            // Lawson-like flips: pick an interior diagonal edge, find its
            // two triangles via a(j) (exercises the hot path itself),
            // remove both, add flipped pair.
            for (int f = 0; f < flipRounds; f++) {
                int x = r.nextInt(side - 1), y = r.nextInt(side - 1);
                int d = r.nextInt(2);   // 0: keep diag (x,y)-(x+1,y+1); 1: other diag
                int i1 = d == 0 ? at.applyAsInt(x, y) : at.applyAsInt(x + 1, y);
                int i2 = d == 0 ? at.applyAsInt(x + 1, y + 1) : at.applyAsInt(x, y + 1);
                ShadowJ diag = c.edge(i1, i2);
                List<ShadowL> hit = new ArrayList<>(c.ref.a(diag));
                // a few probing queries first (Lawson evaluates many edges)
                for (int q = 0; q < 5; q++) query(c, r.nextInt(side * side), r.nextInt(side * side));
                if (hit.size() == 2) {
                    ShadowL u = hit.get(0), v = hit.get(1);
                    removeTri(c, u); removeTri(c, v);
                    c.live.remove(u); c.live.remove(v);
                    // flipped pair across the other diagonal
                    int o1 = d == 0 ? at.applyAsInt(x + 1, y) : at.applyAsInt(x, y);
                    int o2 = d == 0 ? at.applyAsInt(x, y + 1) : at.applyAsInt(x + 1, y + 1);
                    ShadowL w1 = new ShadowL(c.pt(i1), c.pt(o1), c.pt(o2));
                    ShadowL w2 = new ShadowL(c.pt(i1), c.pt(o2), c.pt(i2));
                    addTri(c, w1); addTri(c, w2);
                    c.live.add(w1); c.live.add(w2);
                }
                query(c, i1, i2);
            }
            sameIter(c);
        };
    }

    /* ------------- negative-control streams (against BrokenIdx) ------------- */

    /** Drive any Ops variant through a stream and compare vs reference. */
    static boolean diverges(IdxList.Ops ops, Stream s) {
        Ctx c = new Ctx(new IdxList(ops));
        try { s.run(c); } catch (AssertionError | RuntimeException e) { return true; }
        return false;
    }

    static void mustDiverge(String name, IdxList.Ops ops, Stream s) {
        checks++;
        if (!diverges(ops, s))
            throw new AssertionError("NEGATIVE NOT REJECTED: " + name);
    }

    /* ------------- targeted semantic cases ------------- */

    static void targeted() {
        // 1) undirected key: reversed direction edge must hit identically.
        {
            Ctx c = new Ctx(new IdxList());
            pool(c, 8);
            ShadowL t = new ShadowL(c.pt(1), c.pt(2), c.pt(3));
            addTri(c, t);
            query(c, 1, 2); query(c, 2, 1);           // reversed
            query(c, 2, 3); query(c, 3, 1); query(c, 1, 3);
            query(c, 4, 5);                            // absent
            query(c, 1, 1);                            // degenerate edge
        }
        // 2) duplicate add (equal object, different instance): cyclic-perm
        //    equals -> set.add false; index must not double-list.
        {
            Ctx c = new Ctx(new IdxList());
            pool(c, 8);
            ShadowL t = new ShadowL(c.pt(1), c.pt(2), c.pt(3));
            ShadowL dup = new ShadowL(c.pt(2), c.pt(3), c.pt(1));  // rotation: equal
            addTri(c, t);
            addTri(c, dup);                            // both sides must return false
            query(c, 1, 2); query(c, 2, 3); query(c, 3, 1);
            sameIter(c);
        }
        // 3) equal-coords-different-index TriPoints: l.equals ignores index.
        //    remove(arg) removes the equal stored element whose keys DIFFER;
        //    dirty-rebuild must absorb it.
        {
            Ctx c = new Ctx(new IdxList());
            c.pool.add(new ShadowTriPoint(10, 10, 1));
            c.pool.add(new ShadowTriPoint(20, 20, 2));
            c.pool.add(new ShadowTriPoint(30, 30, 3));
            c.pool.add(new ShadowTriPoint(10, 10, 9));  // same xy, index 9
            c.pool.add(new ShadowTriPoint(20, 20, 8));
            c.pool.add(new ShadowTriPoint(30, 30, 7));
            ShadowL stored = new ShadowL(c.pt(0), c.pt(1), c.pt(2));
            ShadowL equalDiffIdx = new ShadowL(c.pt(3), c.pt(4), c.pt(5)); // equal (xy), keys 9,8,7
            addTri(c, stored);
            addTri(c, new ShadowL(c.pt(1), c.pt(2), new ShadowTriPoint(5, 5, 40)));
            query(c, 1, 2);
            sameBool("eq-coords-remove", c.ref.b(equalDiffIdx), c.idx.b(equalDiffIdx));
            query(c, 1, 2); query(c, 9, 8); query(c, 8, 7);   // post-remove probes
            sameIter(c);
        }
        // 4) degenerate triangle with repeated vertex index: two edges share
        //    one undirected key -> must appear ONCE per query (l.b(j) is bool).
        {
            Ctx c = new Ctx(new IdxList());
            pool(c, 6);
            ShadowL deg = new ShadowL(c.pt(2), c.pt(2), c.pt(4)); // verts 2,2,4
            addTri(c, deg);
            ShadowL normal = new ShadowL(c.pt(2), c.pt(4), c.pt(5));
            addTri(c, normal);
            query(c, 2, 4);   // both hit; deg once
            query(c, 2, 2);   // deg's (a,b) edge
            query(c, 4, 2);
            sameIter(c);
        }
        // 5) iterator().remove() side path (no woven mutator): next query
        //    must resync via size/epoch and stay correct.
        {
            Ctx c = new Ctx(new IdxList());
            pool(c, 12);
            ShadowL[] ts = new ShadowL[4];
            for (int i = 0; i < 4; i++) {
                ts[i] = new ShadowL(c.pt(i), c.pt(i + 1), c.pt(i + 2));
                addTri(c, ts[i]);
            }
            query(c, 1, 2);
            // side-path remove on BOTH impls (identical set semantics)
            Iterator<ShadowL> ri = c.ref.iterator(), ii = c.idx.iterator();
            ri.next(); ri.remove(); ii.next(); ii.remove();
            c.live.remove(ts[0]);
            query(c, 1, 2); query(c, 0, 1); query(c, 2, 3); query(c, 3, 4);
            sameIter(c);
            // side-path remove followed by a WOVEN add (net-zero size change
            // is the adversarial case for the sz precheck)
            ri = c.ref.iterator(); ii = c.idx.iterator();
            ri.next(); ri.remove(); ii.next(); ii.remove();
            c.live.remove(ts[1]);
            ShadowL fresh = new ShadowL(c.pt(5), c.pt(6), c.pt(7));
            addTri(c, fresh);   // idx: sz precheck must fail -> dirty -> rebuild
            query(c, 5, 6); query(c, 6, 7); query(c, 2, 3); query(c, 1, 2);
            sameIter(c);
        }
        // 6) clear mid-flight, then repopulate and query.
        {
            Ctx c = new Ctx(new IdxList());
            pool(c, 9);
            for (int i = 0; i < 6; i++)
                addTri(c, new ShadowL(c.pt(i), c.pt(i + 1), c.pt(i + 2)));
            query(c, 1, 2);
            clearAll(c);
            query(c, 1, 2); query(c, 0, 1);
            addTri(c, new ShadowL(c.pt(0), c.pt(1), c.pt(2)));
            query(c, 0, 1); query(c, 1, 2);
            sameIter(c);
        }
        // 7) sentinel negative indices (as in the real dump edges -1/-2/-3).
        {
            Ctx c = new Ctx(new IdxList());
            pool(c, 4);   // adds sentinels -1,-2,-3 at indices 4..6
            addTri(c, new ShadowL(c.pt(0), c.pt(1), c.pt(4)));   // (-1 vertex)
            addTri(c, new ShadowL(c.pt(4), c.pt(5), c.pt(6)));   // all sentinels
            query(c, 0, 4); query(c, 4, 5);            // (4,5) i.e. -1,-2
            query(c, 5, 6); query(c, 6, 4);
            sameIter(c);
        }
        // 8) remove-miss (not present) then queries; boolean parity too.
        {
            Ctx c = new Ctx(new IdxList());
            pool(c, 6);
            ShadowL a = new ShadowL(c.pt(0), c.pt(1), c.pt(2));
            addTri(c, a);
            ShadowL ghost = new ShadowL(c.pt(3), c.pt(4), c.pt(5));
            sameBool("rm-miss", c.ref.b(ghost), c.idx.b(ghost));
            sameBool("contains-miss", c.ref.c(ghost), c.idx.c(ghost));
            query(c, 0, 1); query(c, 3, 4);
        }
    }

    public static void main(String[] args) {
        // targeted semantics
        for (int i = 0; i < 3; i++) targeted();

        // randomized mesh+flip streams at several scales/seeds
        for (long seed = 1; seed <= 4; seed++) {
            for (int side : new int[] {4, 8, 16, 34}) {
                Ctx c = new Ctx(new IdxList());
                meshFlip(seed, side, side * side).run(c);
            }
        }

        // negative controls: every broken candidate must diverge from ref
        mustDiverge("directed-keys", Negatives.directedKeys(), meshFlip(7, 8, 64));
        mustDiverge("no-dedup-keys", Negatives.noKeyDedup(), c -> {
            pool(c, 6);
            addTri(c, new ShadowL(c.pt(2), c.pt(2), c.pt(4)));
            query(c, 2, 4);
        });
        mustDiverge("no-dirty-on-remove", Negatives.noDirtyOnRemove(), meshFlip(11, 8, 64));
        mustDiverge("no-sidepath-check", Negatives.noSidePathCheck(), c -> {
            pool(c, 12);
            for (int i = 0; i < 4; i++) addTri(c, new ShadowL(c.pt(i), c.pt(i + 1), c.pt(i + 2)));
            query(c, 1, 2);
            Iterator<ShadowL> ri = c.ref.iterator(), ii = c.idx.iterator();
            ri.next(); ri.remove(); ii.next(); ii.remove();
            query(c, 1, 2); query(c, 2, 3);
        });
        mustDiverge("unordered-bucket", Negatives.unorderedBucket(), c -> {
            pool(c, 8);
            // one edge shared by many triangles -> bucket order observable
            // (unique third-vertex coords so l.equals never dedups them)
            for (int i = 0; i < 30; i++)
                addTri(c, new ShadowL(c.pt(1), c.pt(2),
                    new ShadowTriPoint(500 + i, 600 + i, 100 + i)));
            query(c, 1, 2); query(c, 2, 1);
        });
        mustDiverge("coord-keys", Negatives.coordKeys(), c -> {
            pool(c, 6);
            // two edges same coord pair different index pair -> coord-keyed
            // index merges distinct edges (emulated: indices congruent mod 7)
            ShadowTriPoint pa = new ShadowTriPoint(1, 1, 1), pb = new ShadowTriPoint(2, 2, 2);
            ShadowTriPoint qa = new ShadowTriPoint(1, 1, 8), qb = new ShadowTriPoint(2, 2, 9);
            addTri(c, new ShadowL(pa, pb, c.pt(3)));
            addTri(c, new ShadowL(qa, qb, c.pt(4)));
            query(c, 1, 2); query(c, 8, 9);
        });
        mustDiverge("shared-bucket-list", Negatives.sharedBucketList(), c -> {
            pool(c, 6);
            addTri(c, new ShadowL(c.pt(0), c.pt(1), c.pt(2)));
            List<ShadowL> r = c.idx.a(c.edge(0, 1));
            r.clear();                       // caller mutation must not corrupt
            sameList("after-caller-clear", c.ref.a(c.edge(0, 1)), c.idx.a(c.edge(0, 1)));
        });

        System.out.println("TLINDEX_SELFCHECK PASS checks=" + checks);
    }
}
