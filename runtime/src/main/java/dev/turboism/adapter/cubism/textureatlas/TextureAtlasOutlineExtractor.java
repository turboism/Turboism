package dev.turboism.adapter.cubism.textureatlas;

import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;

/**
 * Converts host {@code drawDataShapes} (material-local {@link Shape}s) into
 * {@link TextureAtlasOutline} rings.
 *
 * <p>Shapes are unioned with {@link Area} so overlapping regions merge exactly;
 * winding separates outer rings from hole rings. Disjoint outer rings stay
 * separate in the outline (the v2 contract models multi-region items natively).
 * Holes are not representable: when the union contains hole rings the extractor
 * returns the outer rings and flags {@link Extraction#holesFilled()} so callers
 * can record the conservative fill instead of silently dropping topology.</p>
 */
final class TextureAtlasOutlineExtractor {

    private static final double FLATNESS = 0.5;
    private static final double MIN_AREA = 1e-6;

    private TextureAtlasOutlineExtractor() {
    }

    /** Outcome of converting one item's host shapes. */
    record Extraction(TextureAtlasOutline outline, boolean holesFilled,
        int ringCount) {
    }

    /**
     * Extracts outer rings from the given shapes; {@code null}/empty input yields
     * {@code null} so the caller can fall back to the item bounds.
     */
    static Extraction extract(final List<? extends Shape> shapes) {
        if (shapes == null || shapes.isEmpty()) {
            return null;
        }
        Area union = null;
        for (final Shape shape : shapes) {
            if (shape == null) {
                continue;
            }
            final Area part = new Area(shape);
            if (union == null) {
                union = part;
            } else {
                union.add(part);
            }
        }
        if (union == null || union.isEmpty()) {
            return null;
        }
        final List<double[][]> rings = new ArrayList<>();
        for (final double[][] ring : flatten(union)) {
            if (Math.abs(signedArea(ring)) >= MIN_AREA && ring.length >= 3) {
                rings.add(ring);
            }
        }
        // classify by containment rather than winding convention: a ring whose
        // interior lies inside another ring is a hole (conservative, does not
        // rely on Area's orientation output)
        final List<Area> areas = new ArrayList<>();
        for (final double[][] ring : rings) {
            areas.add(toArea(ring));
        }
        final List<double[][]> outer = new ArrayList<>();
        boolean holes = false;
        for (int i = 0; i < rings.size(); i++) {
            boolean contained = false;
            for (int j = 0; j < rings.size(); j++) {
                if (i != j && contains(areas.get(j), rings.get(i))) {
                    contained = true;
                    break;
                }
            }
            if (contained) {
                holes = true;
            } else {
                outer.add(rings.get(i));
            }
        }
        if (outer.isEmpty()) {
            return null;
        }
        return new Extraction(new TextureAtlasOutline(List.copyOf(outer)),
            holes, outer.size());
    }

    private static List<double[][]> flatten(final Shape shape) {
        final List<double[][]> rings = new ArrayList<>();
        final PathIterator it = shape.getPathIterator(null, FLATNESS);
        final double[] seg = new double[6];
        List<double[]> current = null;
        while (!it.isDone()) {
            switch (it.currentSegment(seg)) {
                case PathIterator.SEG_MOVETO -> {
                    current = new ArrayList<>();
                    current.add(new double[] {seg[0], seg[1]});
                }
                case PathIterator.SEG_LINETO -> {
                    if (current != null) {
                        current.add(new double[] {seg[0], seg[1]});
                    }
                }
                case PathIterator.SEG_CLOSE -> {
                    if (current != null && current.size() >= 3) {
                        rings.add(current.toArray(new double[0][]));
                    }
                    current = null;
                }
                default -> { /* flatness eliminates curve segments */ }
            }
            it.next();
        }
        if (current != null && current.size() >= 3) {
            rings.add(current.toArray(new double[0][]));
        }
        return rings;
    }

    private static Area toArea(final double[][] ring) {
        final java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
        path.moveTo(ring[0][0], ring[0][1]);
        for (int i = 1; i < ring.length; i++) {
            path.lineTo(ring[i][0], ring[i][1]);
        }
        path.closePath();
        return new Area(path);
    }

    private static boolean contains(final Area outer, final double[][] ring) {
        for (final double[] vertex : ring) {
            if (!outer.contains(vertex[0], vertex[1])) {
                return false;
            }
        }
        return true;
    }

    private static double signedArea(final double[][] ring) {
        double sum = 0;
        for (int i = 0; i < ring.length; i++) {
            final double[] a = ring[i];
            final double[] b = ring[(i + 1) % ring.length];
            sum += a[0] * b[1] - b[0] * a[1];
        }
        return sum * 0.5;
    }
}
