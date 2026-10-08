package dev.turboism.validation.triweave.shadow;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Official-shape shadow fixture: the {@code produce()} method body replicates the javap
 * verified bytecode shape of the official {@code TriangleList.b()} — fresh k via
 * NEW/DUP/INVOKESPECIAL+ASTORE anchor, LinkedHashSet field iteration, three edge getters
 * first, then three [ALOAD k, ALOAD jn, ICONST_0, INVOKEVIRTUAL query] sites each gated by
 * a conditional jump + append + POP, single ARETURN. Own package and member names only —
 * this is never the official class and must only be defined by the fixture loader.
 */
public final class ShadowTriangleList {
    private final LinkedHashSet<ShadowL> b;

    public ShadowTriangleList() {
        b = new LinkedHashSet<>();
    }

    /** Test seam for supplying the exact input set; ctor shape is not part of the pin. */
    public ShadowTriangleList(LinkedHashSet<ShadowL> set) {
        b = set;
    }

    public ShadowK produce() {
        ShadowK k = new ShadowK();
        for (ShadowL t : b) {
            ShadowJ j1 = t.d();
            ShadowJ j2 = t.e();
            ShadowJ j3 = t.f();
            if (!k.has(j1, false)) k.add(j1);
            if (!k.has(j2, false)) k.add(j2);
            if (!k.has(j3, false)) k.add(j3);
        }
        return k;
    }

    public Iterator<ShadowL> iterator() {
        return b.iterator();
    }
}
