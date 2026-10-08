package dev.turboism.validation.tlindex;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

import dev.turboism.validation.tlindex.Shadow.ShadowJ;
import dev.turboism.validation.tlindex.Shadow.ShadowL;

/**
 * Shadow of the WOVEN TriangleList shape: identical LinkedHashSet store and
 * identical public semantics, but the four entry points delegate exactly as
 * the planned bytecode weave would (add/remove/clear call-sites swapped to
 * Bridge; a(j) prepends tryQuery and falls back to the original scan on
 * null). The fallback is retained verbatim so the fixture exercises the
 * same control flow the woven class will.
 */
public class IdxList {
    /** Overridable Bridge seam so Negatives can inject broken variants. */
    public interface Ops {
        boolean add(LinkedHashSet s, Object tri, int ia, int ib, int ic);
        boolean remove(LinkedHashSet s, Object tri);
        void clear(LinkedHashSet s);
        List tryQuery(LinkedHashSet s, int ja, int jb);
    }

    static final Ops REAL = new Ops() {
        public boolean add(LinkedHashSet s, Object tri, int a, int b, int c) {
            return Bridge.add(s, tri, a, b, c);
        }
        public boolean remove(LinkedHashSet s, Object tri) { return Bridge.remove(s, tri); }
        public void clear(LinkedHashSet s) { Bridge.clear(s); }
        public List tryQuery(LinkedHashSet s, int ja, int jb) { return Bridge.tryQuery(s, ja, jb); }
    };

    final LinkedHashSet<ShadowL> b = new LinkedHashSet<>();
    final Ops ops;

    public IdxList() { this(REAL); }
    IdxList(Ops ops) { this.ops = ops; }

    public boolean a(ShadowL tri) {      // woven a(l): Bridge.add at the add site
        return ops.add(b, tri, tri.a().getIndex(), tri.b().getIndex(), tri.c().getIndex());
    }
    public boolean b(ShadowL tri) {      // woven b(l): Bridge.remove at the remove site
        return ops.remove(b, tri);
    }
    public void c() { ops.clear(b); }    // woven c(): Bridge.clear at the clear site
    public boolean c(ShadowL tri) { return b.contains(tri); }   // untouched

    @SuppressWarnings("unchecked")
    public java.util.List<ShadowL> a(ShadowJ e) {   // woven a(j): prepend + fallback
        List r = ops.tryQuery(b, e.a().getIndex(), e.b().getIndex());
        if (r != null) return (java.util.List<ShadowL>) r;
        // ---- original body, verbatim ----
        java.util.ArrayList<ShadowL> out = new java.util.ArrayList<>();
        for (Iterator<ShadowL> it = b.iterator(); it.hasNext(); ) {
            ShadowL t = it.next();
            if (t.b(e)) out.add(t);
        }
        return out;
    }

    public Iterator<ShadowL> iterator() { return b.iterator(); }
    public int size() { return b.size(); }
}
