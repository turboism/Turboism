package com.live2d.graphics3d.editableMesh.triangulation;

/**
 * Fixture with the host's exact name and the defective constant hash, so the agent's identity and
 * shape gates are exercised end to end in a real JVM.
 */
public final class l {
    private final TriPoint a;
    private final TriPoint b;
    private final TriPoint c;

    public l(final TriPoint a, final TriPoint b, final TriPoint c) {
        if (a == null || b == null || c == null) {
            throw new IllegalArgumentException("corner points must not be null");
        }
        this.a = a;
        this.b = b;
        this.c = c;
    }

    public TriPoint a() {
        return a;
    }

    public TriPoint b() {
        return b;
    }

    public TriPoint c() {
        return c;
    }

    @Override
    public int hashCode() {
        return 0;
    }
}
