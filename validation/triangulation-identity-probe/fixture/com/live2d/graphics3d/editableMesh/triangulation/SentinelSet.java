package com.live2d.graphics3d.editableMesh.triangulation;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;

/** LinkedHashSet subtype whose iterator() returns one fixed sentinel — proves the weave returns
 * the original dispatch result unchanged. */
public final class SentinelSet extends LinkedHashSet<Object> {
    private static final long serialVersionUID = 1L;
    private final Iterator<Object> sentinel = new Iterator<>() {
        public boolean hasNext() { return false; }
        public Object next() { throw new NoSuchElementException(); }
    };

    @Override public Iterator<Object> iterator() {
        return sentinel;
    }
}
