package dev.turboism.validation.triweave.shadow;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Shape-rejection fixture: same class name as the shadow target but only TWO query sites —
 * the candidate weave must reject with an observable reason ({@code queries=2}). Loaded via
 * a loader whose URL order puts this directory ahead of the real shadow classes.
 */
public final class ShadowTriangleList {
    private final LinkedHashSet<ShadowL> b;

    public ShadowTriangleList(LinkedHashSet<ShadowL> set) {
        b = set;
    }

    public ShadowK produce() {
        ShadowK k = new ShadowK();
        for (ShadowL t : b) {
            ShadowJ j1 = t.d();
            ShadowJ j2 = t.e();
            if (!k.has(j1, false)) k.add(j1);
            if (!k.has(j2, false)) k.add(j2);
        }
        return k;
    }

    public Iterator<ShadowL> iterator() {
        return b.iterator();
    }
}
