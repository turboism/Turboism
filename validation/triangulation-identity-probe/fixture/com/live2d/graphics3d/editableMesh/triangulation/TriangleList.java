package com.live2d.graphics3d.editableMesh.triangulation;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Own-fixture stand-in carrying the same structural shape as the reviewed official class:
 * private final {@code LinkedHashSet b} assigned in the default constructor, and
 * {@code iterator()} dispatching on that field. It is not the official class and must only ever
 * be loaded by the fixture loader, never from the official jar.
 */
public final class TriangleList {
    private final LinkedHashSet<Object> b;

    public TriangleList() {
        b = new LinkedHashSet<>();
    }

    /** Fixture-only seam for sentinel/throwing/demoted set variants; the no-arg ctor keeps the
     * official {@code new LinkedHashSet} shape. */
    public TriangleList(LinkedHashSet<Object> set) {
        b = set;
    }

    public Iterator<Object> iterator() {
        return b.iterator();
    }
}
