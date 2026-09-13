package com.live2d.graphics3d.editableMesh.triangulation;

/** Fixture with the host's exact name and accessor shape (equals on x/y, index mixed into hash). */
public final class TriPoint {
    private final int index;
    private final float x;
    private final float y;

    public TriPoint(final float x, final float y, final int index) {
        this.x = x;
        this.y = y;
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof TriPoint point)) return false;
        return Float.compare(x, point.x) == 0 && Float.compare(y, point.y) == 0;
    }

    @Override
    public int hashCode() {
        return ((index * 31) + Float.hashCode(x)) * 31 + Float.hashCode(y);
    }
}
