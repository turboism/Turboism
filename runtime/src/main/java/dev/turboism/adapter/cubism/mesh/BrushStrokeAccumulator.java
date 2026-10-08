package dev.turboism.adapter.cubism.mesh;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** In-memory stroke union with a radius snapshot captured at primary press. */
final class BrushStrokeAccumulator {
    private final TreeSet<Integer> hits = new TreeSet<>();
    private int radiusPixels;
    private boolean active;

    boolean active() {
        return active;
    }

    int radiusPixels() {
        if (!active) throw new IllegalStateException("no active brush stroke");
        return radiusPixels;
    }

    void press(final int radiusPixels) {
        if (radiusPixels < 0) throw new IllegalArgumentException("radiusPixels must be non-negative");
        this.radiusPixels = radiusPixels;
        hits.clear();
        active = true;
    }

    void addHits(final List<Integer> indices) {
        Objects.requireNonNull(indices, "indices");
        if (!active) return;
        for (Integer index : indices) {
            if (index == null || index < 0) throw new IllegalArgumentException("hit index must be non-negative");
            hits.add(index);
        }
    }

    List<Integer> release() {
        final List<Integer> result = active ? List.copyOf(hits) : List.of();
        hits.clear();
        active = false;
        return result;
    }

    void cancel() {
        hits.clear();
        active = false;
    }
}
