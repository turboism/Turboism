package com.live2d.type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Test-only stand-in for the host's {@code com.live2d.type.CArrayList}: the
 * real type is iterable and appears as the return of
 * {@code CAnimationFileContent.getSceneDocs()}.
 */
public final class CArrayList implements Iterable<Object> {
    private final List<Object> elements = new ArrayList<>();

    public CArrayList() {
    }

    public CArrayList(final Collection<?> elements) {
        this.elements.addAll(elements);
    }

    public void add(final Object element) {
        elements.add(element);
    }


    /** Host class {@code LayerSet} calls this member during class initialization. */
    /** Host class {@code LayerSet} calls this member during class initialization; index &lt; 0 appends. */
    public void addOrInsertAt(final Object element, final int index) {
        if (index < 0 || index >= elements.size()) {
            elements.add(element);
        } else {
            elements.add(index, element);
        }
    }
    public int size() {
        return elements.size();
    }

    @Override
    public Iterator<Object> iterator() {
        return elements.iterator();
    }
}
