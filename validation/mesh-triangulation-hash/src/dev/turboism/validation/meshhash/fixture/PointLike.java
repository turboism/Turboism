package dev.turboism.validation.meshhash.fixture;

/**
 * Stand-in for the host's {@code com.live2d.graphics3d.editableMesh.triangulation.TriPoint}: an
 * immutable {@code index} plus mutable x/y. It deliberately mirrors two host properties that the
 * patch must respect:
 *
 * <ul>
 *   <li>{@code equals} compares <b>only x/y</b> — {@code index} is not part of equality;</li>
 *   <li>the inherited-style {@code hashCode} below mixes {@code index} in, so it is already
 *       inconsistent with {@code equals}. The patch must therefore never delegate to it.</li>
 * </ul>
 *
 * <p>Never enters production; it exists so the validation slice can reproduce and verify the
 * host's hash degeneracy without defining or executing any official class.</p>
 */
public final class PointLike {
    private final int index;
    private float x;
    private float y;

    public PointLike(final float x, final float y, final int index) {
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

    /** Mutable exactly like the host's {@code GVector2.setX}. */
    public void setX(final float value) {
        this.x = value;
    }

    public void setY(final float value) {
        this.y = value;
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof PointLike point)) return false;
        return Float.compare(x, point.x) == 0 && Float.compare(y, point.y) == 0;
    }

    /** Mirrors the host: mixes {@code index} in, which {@code equals} ignores. */
    @Override
    public int hashCode() {
        return ((index * 31) + Float.hashCode(x)) * 31 + Float.hashCode(y);
    }

    @Override
    public String toString() {
        return "PointLike[" + index + "](" + x + "," + y + ")";
    }
}
