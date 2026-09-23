package com.live2d.graphics3d.editableMesh;

import com.live2d.util.j.a;
import java.util.ArrayList;
import java.util.List;

/** Fixture with the host's exact name and the instrumented update method shape. */
public final class GEditableMesh2 {
    private int[] cached_indices = new int[] {0, 1, 2};
    private final List<Object> points = new ArrayList<>();
    private final List<Object> edges = new ArrayList<>();

    public GEditableMesh2() {
        points.add(new Object());
        points.add(new Object());
        points.add(new Object());
        edges.add(new Object());
    }

    private void updateMesh(final a input, final boolean reindex) {
        if (reindex) {
            cached_indices = new int[] {0, 1, 2, 2, 1, 0};
        }
        return;
    }

    /** Public wrapper so the harness can trigger the private instrumented method. */
    public void runUpdate(final a input, final boolean reindex) {
        updateMesh(input, reindex);
    }

    public int[] getCached_indices$core() {
        return cached_indices;
    }

    public List<Object> getPoints() {
        return points;
    }

    public List<Object> getEdges() {
        return edges;
    }
}
