package com.live2d.graphics3d.editableMesh.triangulation;

import java.util.HashSet;
import java.util.Iterator;

/** Shape-rejection fixture: field b is a plain HashSet — must fail the field-b gate. */
public final class TriangleList {
    private final HashSet<Object> b = new HashSet<>();

    public Iterator<Object> iterator() {
        return b.iterator();
    }
}
