package dev.turboism.validation.triweave.shadow;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Capture-shape rejection fixture: same class name as the shadow target but produce() has
 * an early return — two ARETURNs. The candidate pin also rejects this (areturns=2), while in
 * dump-only mode only the capture pin applies. Both paths must mark the leg INVALID.
 */
public final class ShadowTriangleList {
    private final LinkedHashSet<ShadowL> b;

    public ShadowTriangleList(LinkedHashSet<ShadowL> set) {
        b = set;
    }

    public ShadowK produce() {
        ShadowK k = new ShadowK();
        if (b.isEmpty()) return k;
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
