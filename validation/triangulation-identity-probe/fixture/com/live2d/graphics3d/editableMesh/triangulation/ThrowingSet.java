package com.live2d.graphics3d.editableMesh.triangulation;

import java.util.Iterator;
import java.util.LinkedHashSet;

/** LinkedHashSet subtype whose iterator() always throws — proves exception passthrough. */
public final class ThrowingSet extends LinkedHashSet<Object> {
    private static final long serialVersionUID = 1L;

    public static final class MarkerException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public MarkerException(String m) { super(m); }
    }

    @Override public Iterator<Object> iterator() {
        throw new MarkerException("fixture-marker");
    }
}
