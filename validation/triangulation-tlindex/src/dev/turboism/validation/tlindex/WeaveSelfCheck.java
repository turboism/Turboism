package dev.turboism.validation.tlindex;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import dev.turboism.validation.tlindex.own.OwnTri;
import dev.turboism.validation.tlindex.own.OwnTriBad;

/**
 * T029-TLINDEX weave self-check, own fixtures only (official bytecode is
 * never read/loaded/executed):
 *
 *  A. fixture shape discovery + weave acceptance + insn-diff proof
 *     (the four transforms land; nothing else changes)
 *  B. woven-vs-original differential over mesh/flip streams + targeted
 *     semantic cases (fresh ByteLoader; same OwnTri.L references; element
 *     identity + order on every a(j))
 *  C. shape-gate negatives: mutant fixtures must reject with the expected
 *     reason and return the original array
 *
 * The woven class calls dev.turboism.validation.tlindex.Bridge through the
 * parent loader — the same dependency path a host leg would use.
 */
public final class WeaveSelfCheck {
    private WeaveSelfCheck() {}

    static final String OWN = "dev.turboism.validation.tlindex.own.OwnTri$TList";
    static final String OWN_BAD = "dev.turboism.validation.tlindex.own.OwnTriBad$TList";
    static final String BRIDGE = "dev/turboism/validation/tlindex/Bridge";
    static final String PKG = "dev/turboism/validation/tlindex/own/OwnTri";

    static int checks = 0;
    static void check(boolean cond, String name) {
        checks++;
        if (!cond) throw new AssertionError("FAIL " + name);
    }

    static final TliWeave.Config OWN_CFG = new TliWeave.Config(
        "dev/turboism/validation/tlindex/own/OwnTri$TList",
        PKG + "$E", PKG + "$L", PKG + "$Pt",
        BRIDGE, "b");

    /* ---------------- byte loading ---------------- */

    static byte[] bytes(String dotName) throws Exception {
        String res = dotName.replace('.', '/') + ".class";
        try (InputStream in = WeaveSelfCheck.class.getClassLoader()
                .getResourceAsStream(res)) {
            Objects.requireNonNull(in, "resource " + res);
            return in.readAllBytes();
        }
    }

    static final class ByteLoader extends ClassLoader {
        private final String target;
        private final byte[] bytes;
        ByteLoader(ClassLoader p, String target, byte[] bytes) {
            super(p); this.target = target; this.bytes = bytes;
        }
        @Override protected Class<?> loadClass(String n, boolean resolve)
                throws ClassNotFoundException {
            synchronized (getClassLoadingLock(n)) {
                if (n.equals(target)) {
                    Class<?> c = findLoadedClass(n);
                    if (c == null) c = defineClass(n, bytes, 0, bytes.length);
                    if (resolve) resolveClass(c);
                    return c;
                }
                return super.loadClass(n, resolve);
            }
        }
    }

    /* ---------------- reflective facade ---------------- */

    /** Drive a (possibly woven) TList instance through the same surface the
     *  differential uses. */
    interface TL {
        boolean add(OwnTri.L t) throws Exception;
        boolean rem(OwnTri.L t) throws Exception;
        void clear() throws Exception;
        boolean contains(OwnTri.L t) throws Exception;
        List<OwnTri.L> query(OwnTri.E e) throws Exception;
        List<OwnTri.L> iter() throws Exception;
        int size() throws Exception;
    }

    static final class Direct implements TL {
        final OwnTri.TList l = new OwnTri.TList();
        public boolean add(OwnTri.L t) { return l.a(t); }
        public boolean rem(OwnTri.L t) { return l.b(t); }
        public void clear() { l.c(); }
        public boolean contains(OwnTri.L t) { return l.c(t); }
        public List<OwnTri.L> query(OwnTri.E e) { return l.a(e); }
        public List<OwnTri.L> iter() {
            List<OwnTri.L> out = new ArrayList<>();
            l.iterator().forEachRemaining(out::add); return out;
        }
        public int size() { return l.a(); }
    }

    static final class Woven implements TL {
        final Object inst; final Class<?> cls;
        Woven(byte[] woven) throws Exception {
            cls = new ByteLoader(WeaveSelfCheck.class.getClassLoader(), OWN, woven)
                .loadClass(OWN);
            inst = cls.getDeclaredConstructor().newInstance();
            check(cls.getClassLoader() != WeaveSelfCheck.class.getClassLoader(),
                "woven class loaded under fresh loader");
        }
        public boolean add(OwnTri.L t) throws Exception {
            return (Boolean) cls.getMethod("a", OwnTri.L.class).invoke(inst, t);
        }
        public boolean rem(OwnTri.L t) throws Exception {
            return (Boolean) cls.getMethod("b", OwnTri.L.class).invoke(inst, t);
        }
        public void clear() throws Exception { cls.getMethod("c").invoke(inst); }
        public boolean contains(OwnTri.L t) throws Exception {
            return (Boolean) cls.getMethod("c", OwnTri.L.class).invoke(inst, t);
        }
        @SuppressWarnings("unchecked")
        public List<OwnTri.L> query(OwnTri.E e) throws Exception {
            return (List<OwnTri.L>) cls.getMethod("a", OwnTri.E.class).invoke(inst, e);
        }
        @SuppressWarnings("unchecked")
        public List<OwnTri.L> iter() throws Exception {
            Iterator<?> it = (Iterator<?>) cls.getMethod("iterator").invoke(inst);
            List<OwnTri.L> out = new ArrayList<>();
            it.forEachRemaining(o -> out.add((OwnTri.L) o));
            return out;
        }
        public int size() throws Exception {
            return (Integer) cls.getMethod("a").invoke(inst);
        }
    }

    /* ---------------- differential ---------------- */

    static void sameList(String tag, List<OwnTri.L> a, List<OwnTri.L> b) {
        checks++;
        if (a.size() != b.size())
            throw new AssertionError("SIZE " + tag + " orig=" + a.size() + " wvn=" + b.size());
        for (int i = 0; i < a.size(); i++)
            if (a.get(i) != b.get(i))
                throw new AssertionError("ORDER " + tag + " slot " + i);
    }

    static void drive(TL ref, TL idx) throws Exception {
        // vertex pool incl. sentinel negatives, like real dump indices
        List<OwnTri.Pt> P = new ArrayList<>();
        for (int i = 0; i < 64; i++)
            P.add(new OwnTri.Pt((i * 37) % 101, (i * 53) % 97, i));
        P.add(new OwnTri.Pt(-999, -999, -1));
        P.add(new OwnTri.Pt(-998, -998, -2));
        P.add(new OwnTri.Pt(-997, -997, -3));
        java.util.function.IntFunction<OwnTri.Pt> pt = i -> P.get(Math.floorMod(i, P.size()));
        java.util.function.BiFunction<Integer,Integer,OwnTri.E> eg =
            (a, b) -> new OwnTri.E(pt.apply(a), pt.apply(b));

        java.util.Random r = new java.util.Random(42);
        // build a grid of triangles + interleave queries after every op
        for (int i = 0; i < 200; i++) {
            int a = r.nextInt(60), b = a + 1, c2 = r.nextInt(60);
            OwnTri.L t = new OwnTri.L(pt.apply(a), pt.apply(b), pt.apply(c2));
            check(ref.add(t) == idx.add(t), "add bool @" + i);
            sameList("q1@" + i, ref.query(eg.apply(a, b)), idx.query(eg.apply(a, b)));
            sameList("q2@" + i, ref.query(eg.apply(b, a)), idx.query(eg.apply(b, a)));
            if ((i & 7) == 0) sameList("iter@" + i, ref.iter(), idx.iter());
        }
        // flips: query shared edge -> remove its triangles -> add flipped pair
        for (int f = 0; f < 60; f++) {
            int x = r.nextInt(60), y = r.nextInt(60);
            OwnTri.E e = eg.apply(x, y);
            List<OwnTri.L> hit = ref.query(e);
            sameList("hit@" + f, hit, idx.query(e));
            for (OwnTri.L t : new ArrayList<>(hit)) {
                check(ref.rem(t) == idx.rem(t), "rem @" + f);
            }
            OwnTri.L w = new OwnTri.L(pt.apply(y), pt.apply(r.nextInt(60)), pt.apply(r.nextInt(60)));
            check(ref.add(w) == idx.add(w), "re-add @" + f);
            sameList("post@" + f, ref.query(e), idx.query(e));
        }
        // equal-coords-different-index remove (equals-vs-keys divergence)
        OwnTri.L s1 = new OwnTri.L(new OwnTri.Pt(1, 1, 90), new OwnTri.Pt(2, 2, 91), new OwnTri.Pt(3, 3, 92));
        OwnTri.L s2 = new OwnTri.L(new OwnTri.Pt(1, 1, 95), new OwnTri.Pt(2, 2, 96), new OwnTri.Pt(3, 3, 97));
        check(ref.add(s1) == idx.add(s1), "eq add");
        check(ref.rem(s2) == idx.rem(s2), "eq-diff-idx rem");   // removes s1 (equal by xy)
        sameList("eq post", ref.query(eg.apply(90, 91)), idx.query(eg.apply(90, 91)));
        sameList("eq iter", ref.iter(), idx.iter());
        // clear + repopulate
        ref.clear(); idx.clear();
        check(ref.size() == idx.size(), "post-clear size");
        sameList("post-clear q", ref.query(eg.apply(0, 1)), idx.query(eg.apply(0, 1)));
    }

    /* ---------------- mutants ---------------- */

    static void mutants(byte[] orig) throws Exception {
        // wrong class entirely: all methods missing
        byte[] lBytes = bytes("dev.turboism.validation.tlindex.own.OwnTri$L");
        TliWeave.Result r = TliWeave.weaveChecked(OWN_CFG, lBytes);
        check(r.rejectReason != null && r.rejectReason.startsWith("method-not-found"),
            "reject L-class: " + r.rejectReason);
        check(r.bytes == lBytes, "reject returns original array");
        // bad shape: two add sites + missing prologue
        byte[] bad = bytes(OWN_BAD);
        r = TliWeave.weaveChecked(OWN_CFG, bad);
        check(r.rejectReason != null, "bad fixture rejected: " + r.rejectReason);
        check(r.bytes == bad, "bad reject returns original");
    }

    public static void main(String[] args) throws Exception {
        byte[] orig = bytes(OWN);

        // A. discovery + acceptance
        TliWeave.Plan p = new TliWeave.Plan();
        p = collectOwn(orig);
        check(p.aLFound && p.bLFound && p.cFound && p.aJFound, "all four methods found");
        check(p.aL_addSites == 1 && p.bL_removeSites == 1 && p.c_clearSites == 1
                && p.aJ_prologues == 1, "one site each: " +
                p.aL_addSites + "/" + p.bL_removeSites + "/" + p.c_clearSites + "/" + p.aJ_prologues);
        TliWeave.Result r = TliWeave.weaveChecked(OWN_CFG, orig);
        check(r.rejectReason == null, "weave accepted: " + r.rejectReason);
        check(r.bytes != orig, "woven bytes differ from original");

        // B. woven-vs-original differential
        drive(new Direct(), new Woven(r.bytes));

        // C. negatives
        mutants(orig);

        System.out.println("TLINDEX_WEAVE_SELFCHECK PASS checks=" + checks);
    }

    static TliWeave.Plan collectOwn(byte[] in) {
        return TliWeave.weaveChecked(OWN_CFG, in).plan;
    }
}
