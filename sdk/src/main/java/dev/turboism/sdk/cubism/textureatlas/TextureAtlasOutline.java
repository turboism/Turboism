package dev.turboism.sdk.cubism.textureatlas;

import java.util.List;
import java.util.Objects;

/**
 * Polygon outline of one atlas item in material-local coordinates.
 *
 * <p>{@code rings} holds one or more closed rings; each ring is an array of
 * {@code [x,y]} vertices in ring order. Rings are outer boundaries only - holes are
 * not representable and callers must either fill them conservatively or reject the
 * item rather than silently replacing a concave outline with a convex hull.
 * Several disjoint rings describe a multi-region item and stay disjoint: backends
 * may stitch them with minimal bridge edges for a single simple polygon but must
 * never inflate them.</p>
 */
public record TextureAtlasOutline(List<double[][]> rings) {

    public TextureAtlasOutline {
        Objects.requireNonNull(rings, "rings");
        if (rings.isEmpty()) {
            throw new IllegalArgumentException("outline requires at least one ring");
        }
        final java.util.ArrayList<double[][]> copies = new java.util.ArrayList<>(rings.size());
        for (final double[][] ring : rings) {
            Objects.requireNonNull(ring, "ring");
            if (ring.length < 3) {
                throw new IllegalArgumentException("ring requires at least three vertices");
            }
            final double[][] copy = new double[ring.length][];
            for (int i = 0; i < ring.length; i++) {
                final double[] point = ring[i];
                if (point == null || point.length != 2
                    || !Double.isFinite(point[0]) || !Double.isFinite(point[1])) {
                    throw new IllegalArgumentException("ring vertices must be finite [x,y]");
                }
                copy[i] = point.clone();
            }
            copies.add(copy);
        }
        rings = List.copyOf(copies);
    }

    /** Single-ring outline. */
    public static TextureAtlasOutline of(final double[][] ring) {
        return new TextureAtlasOutline(List.<double[][]>of(ring.clone()));
    }

    /** Axis-aligned rectangle outline {@code [0,0]-[width,height]}. */
    public static TextureAtlasOutline rect(final double width, final double height) {
        if (!(width > 0) || !(height > 0)) {
            throw new IllegalArgumentException("rect outline requires positive size");
        }
        return of(new double[][] {{0, 0}, {width, 0}, {width, height}, {0, height}});
    }

}
