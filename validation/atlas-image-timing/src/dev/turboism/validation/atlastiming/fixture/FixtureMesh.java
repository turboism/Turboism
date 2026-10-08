package dev.turboism.validation.atlastiming.fixture;

import java.util.List;

/** Own fixture mirroring the updateMesh internals chain. Never a host class. */
public class FixtureMesh {
    /** Non-empty sentinel proving the woven ARETURN passes the real object through. */
    public static final List<int[]> SENTINEL = List.of(new int[]{7, 8, 9}, new int[]{4, 2});

    public int calls;

    public void updateVertices() {
        calls++;
    }

    public void updateIndices(final Object context) {
        calls++;
        delaunayApply(delaunayCompute(), true, context);
        autoTriangulate(context);
    }

    public List<int[]> delaunayCompute() {
        calls++;
        return SENTINEL;
    }

    public void delaunayApply(final List<int[]> triangles, final boolean flag,
                            final Object context) {
        calls++;
    }

    public void autoTriangulate(final Object context) {
        calls++;
    }

    /** Drives the whole chain once so every woven metric records exactly one call. */
    public void driveMesh(final Object context) {
        updateVertices();
        updateIndices(context);
    }
}
