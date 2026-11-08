package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.List;

/**
 * Direction guides derived from the principal axes of the solid silhouette.
 *
 * <p>Aspect ratio alone is a poor classifier: a spindle and a hard-surface strip share the same
 * elongation. The cross-section width near both tips separates them, because only a spindle
 * narrows. Round shapes receive concentric rings, spindles receive connecting arcs, and blunt
 * strips receive parallel guide lines.</p>
 */
final class DirectionGuides {

    /** Aspect ratio above which a shape counts as elongated. */
    static final double ELONGATED_ASPECT_MIN = 2.2;

    /** Tip width ratio under which an elongated shape counts as tapered. */
    static final double TAPERED_TIP_RATIO = 0.72;

    /** Width of the sampling band around each tip, as a fraction of the major extent. */
    static final double TAPER_SAMPLE_BAND_RATIO = 0.02;

    /** Inset of the outermost spindle cross-section from each tip, in pixels. */
    static final double SPINDLE_SECTION_MARGIN_PX = 0.1;

    /** Smallest number of spindle cross-sections. */
    static final int SPINDLE_MIN_SECTIONS = 5;

    /** Simplification tolerance of a concentric ring, in pixels. */
    static final double RING_TOLERANCE_PX = 1.5;

    /** Pieces shorter than this length are clipped fragments, in pixels. */
    static final double MIN_PIECE_LENGTH_PX = 1.0e-5;

    /** Relative positions of the two sections that decide tapering. */
    private static final double[] TAPER_SAMPLE_FRACTIONS = {0.1, 0.9};

    private DirectionGuides() {}

    /** @param paths guide polylines already clipped to the material */
    record Guides(List<BoundaryPath> paths, ShapeFamily family) {}

    /**
     * @param domain material outline; guides stay inside it so their ends land on real constraints
     * @param trackCount number of guide tracks; rings and strips use the same count
     */
    static Guides generate(
            final RegionRaster raster, final PolygonShape domain, final double spacing, final int trackCount) {
        final Shape shape = Shape.of(raster, domain.exterior());
        final List<BoundaryPath> guides = new ArrayList<>();
        final ShapeFamily family;
        if (shape.aspect() > ELONGATED_ASPECT_MIN && shape.tapered()) {
            family = ShapeFamily.SPINDLE_ARCS;
            addSpindleArcs(guides, domain, shape, spacing, trackCount);
        } else if (shape.aspect() > ELONGATED_ASPECT_MIN) {
            family = ShapeFamily.STRAIGHT_GUIDES;
            addStraightGuides(guides, domain, shape, trackCount);
        } else if (shape.aspect() > 0.0) {
            family = ShapeFamily.CONCENTRIC_RINGS;
            addRings(guides, domain, trackCount);
        } else {
            family = ShapeFamily.NONE;
        }
        return new Guides(List.copyOf(guides), family);
    }

    private static void addStraightGuides(
            final List<BoundaryPath> guides, final PolygonShape domain, final Shape shape, final int trackCount) {
        for (int track = 1; track <= trackCount; track++) {
            final double fraction = (double) track / (trackCount + 1);
            final double offset = shape.acrossMin() + shape.acrossSpan() * fraction;
            final double[] start = shape.point(shape.majorMin(), offset);
            final double[] end = shape.point(shape.majorMax(), offset);
            addClipped(
                    guides,
                    domain,
                    new Polyline(new double[] {start[0], end[0]}, new double[] {start[1], end[1]}, false));
        }
    }

    private static void addSpindleArcs(
            final List<BoundaryPath> guides,
            final PolygonShape domain,
            final Shape shape,
            final double spacing,
            final int trackCount) {
        final double low = shape.majorMin() + SPINDLE_SECTION_MARGIN_PX;
        final double high = shape.majorMax() - SPINDLE_SECTION_MARGIN_PX;
        final int sections = Math.max(SPINDLE_MIN_SECTIONS, (int) ((high - low) / spacing) + 1);
        final List<double[][]> crossSections = new ArrayList<>(sections);
        for (int index = 0; index < sections; index++) {
            final double fraction = sections == 1 ? 0.5 : (double) index / (sections - 1);
            final double[] base = shape.point(low + (high - low) * fraction, 0.0);
            final double[] chord = domain.longestInsideChord(base[0], base[1], shape.normalX(), shape.normalY());
            if (chord == null) {
                continue;
            }
            final double firstLateral = shape.lateral(chord[0], chord[1]);
            final double secondLateral = shape.lateral(chord[2], chord[3]);
            crossSections.add(
                    firstLateral <= secondLateral
                            ? new double[][] {{chord[0], chord[1]}, {chord[2], chord[3]}}
                            : new double[][] {{chord[2], chord[3]}, {chord[0], chord[1]}});
        }
        final double[] start = shape.vertexAtMajorExtreme(true);
        final double[] end = shape.vertexAtMajorExtreme(false);
        for (int track = 1; track <= trackCount; track++) {
            final double interpolation = (double) track / (trackCount + 1);
            final double[] xs = new double[crossSections.size() + 2];
            final double[] ys = new double[crossSections.size() + 2];
            xs[0] = start[0];
            ys[0] = start[1];
            for (int i = 0; i < crossSections.size(); i++) {
                final double[][] section = crossSections.get(i);
                xs[i + 1] = (1.0 - interpolation) * section[0][0] + interpolation * section[1][0];
                ys[i + 1] = (1.0 - interpolation) * section[0][1] + interpolation * section[1][1];
            }
            xs[xs.length - 1] = end[0];
            ys[ys.length - 1] = end[1];
            addClipped(guides, domain, new Polyline(xs, ys, false));
        }
    }

    private static void addRings(final List<BoundaryPath> guides, final PolygonShape domain, final int trackCount) {
        final Polyline exterior = domain.exterior();
        final double[] centroid = exterior.centroid();
        for (int track = 1; track <= trackCount; track++) {
            final double factor = (double) track / (trackCount + 1);
            final double[] xs = new double[exterior.size()];
            final double[] ys = new double[exterior.size()];
            for (int i = 0; i < exterior.size(); i++) {
                xs[i] = centroid[0] + factor * (exterior.x(i) - centroid[0]);
                ys[i] = centroid[1] + factor * (exterior.y(i) - centroid[1]);
            }
            addClipped(guides, domain, new Polyline(xs, ys, true).simplified(RING_TOLERANCE_PX));
        }
    }

    private static void addClipped(final List<BoundaryPath> guides, final PolygonShape domain, final Polyline path) {
        for (final Polyline piece : domain.clip(path)) {
            if (piece.length() > MIN_PIECE_LENGTH_PX) {
                guides.add(BoundaryPath.guide(piece));
            }
        }
    }

    /** Principal axes and extent of the solid silhouette. */
    private record Shape(
            double centerX,
            double centerY,
            double axisX,
            double axisY,
            double normalX,
            double normalY,
            double aspect,
            boolean tapered,
            double majorMin,
            double majorMax,
            double acrossMin,
            double acrossSpan,
            double[] vertexMajor,
            double[] vertexXs,
            double[] vertexYs) {

        double lateral(final double x, final double y) {
            return (x - centerX) * normalX + (y - centerY) * normalY;
        }

        double[] point(final double along, final double across) {
            return new double[] {centerX + axisX * along + normalX * across, centerY + axisY * along + normalY * across
            };
        }

        double[] vertexAtMajorExtreme(final boolean minimum) {
            int best = 0;
            for (int i = 1; i < vertexMajor.length; i++) {
                if (minimum ? vertexMajor[i] < vertexMajor[best] : vertexMajor[i] > vertexMajor[best]) {
                    best = i;
                }
            }
            return new double[] {vertexXs[best], vertexYs[best]};
        }

        static Shape of(final RegionRaster raster, final Polyline exterior) {
            final boolean[] mask = raster.mask();
            final int width = raster.width();
            double sumX = 0.0;
            double sumY = 0.0;
            double sumXX = 0.0;
            double sumYY = 0.0;
            double sumXY = 0.0;
            long count = 0;
            for (int i = 0; i < mask.length; i++) {
                if (!mask[i]) {
                    continue;
                }
                final double x = i % width;
                final double y = i / width;
                sumX += x;
                sumY += y;
                sumXX += x * x;
                sumYY += y * y;
                sumXY += x * y;
                count++;
            }
            if (count < 2) {
                return new Shape(
                        0.0,
                        0.0,
                        1.0,
                        0.0,
                        0.0,
                        1.0,
                        0.0,
                        false,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        new double[0],
                        new double[0],
                        new double[0]);
            }
            final double centerX = sumX / count;
            final double centerY = sumY / count;
            final double covXX = sumXX / count - centerX * centerX;
            final double covYY = sumYY / count - centerY * centerY;
            final double covXY = sumXY / count - centerX * centerY;
            final double halfTrace = 0.5 * (covXX + covYY);
            final double spread = Math.sqrt(Math.max(0.0, halfTrace * halfTrace - (covXX * covYY - covXY * covXY)));
            final double major = halfTrace + spread;
            final double minor = halfTrace - spread;
            final double aspect = minor > 1.0e-12 ? Math.sqrt(major / minor) : 0.0;
            double axisX = 1.0;
            double axisY = 0.0;
            if (Math.abs(covXY) > 1.0e-12) {
                axisX = covXY;
                axisY = major - covXX;
                final double length = Math.hypot(axisX, axisY);
                axisX /= length;
                axisY /= length;
            } else if (covYY > covXX) {
                axisX = 0.0;
                axisY = 1.0;
            }
            final double normalX = -axisY;
            final double normalY = axisX;
            final double[] vertexMajor = new double[exterior.size()];
            final double[] vertexXs = new double[exterior.size()];
            final double[] vertexYs = new double[exterior.size()];
            for (int i = 0; i < exterior.size(); i++) {
                vertexXs[i] = exterior.x(i);
                vertexYs[i] = exterior.y(i);
                vertexMajor[i] = (vertexXs[i] - centerX) * axisX + (vertexYs[i] - centerY) * axisY;
            }
            double majorMin = Double.POSITIVE_INFINITY;
            double majorMax = Double.NEGATIVE_INFINITY;
            for (final double value : vertexMajor) {
                majorMin = Math.min(majorMin, value);
                majorMax = Math.max(majorMax, value);
            }
            double projectedMin = Double.POSITIVE_INFINITY;
            double projectedMax = Double.NEGATIVE_INFINITY;
            double acrossMin = Double.POSITIVE_INFINITY;
            double acrossMax = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < mask.length; i++) {
                if (!mask[i]) {
                    continue;
                }
                final double dx = i % width - centerX;
                final double dy = i / width - centerY;
                final double projected = dx * axisX + dy * axisY;
                final double across = dx * normalX + dy * normalY;
                projectedMin = Math.min(projectedMin, projected);
                projectedMax = Math.max(projectedMax, projected);
                acrossMin = Math.min(acrossMin, across);
                acrossMax = Math.max(acrossMax, across);
            }
            final double projectedSpan = projectedMax - projectedMin;
            final double acrossSpan = acrossMax - acrossMin;
            double widestTip = 0.0;
            if (projectedSpan > 0.0 && acrossSpan > 0.0) {
                for (final double fraction : TAPER_SAMPLE_FRACTIONS) {
                    final double target = projectedMin + projectedSpan * fraction;
                    final double band = projectedSpan * TAPER_SAMPLE_BAND_RATIO;
                    double low = Double.POSITIVE_INFINITY;
                    double high = Double.NEGATIVE_INFINITY;
                    for (int i = 0; i < mask.length; i++) {
                        if (!mask[i]) {
                            continue;
                        }
                        final double dx = i % width - centerX;
                        final double dy = i / width - centerY;
                        if (Math.abs(dx * axisX + dy * axisY - target) >= band) {
                            continue;
                        }
                        final double across = dx * normalX + dy * normalY;
                        low = Math.min(low, across);
                        high = Math.max(high, across);
                    }
                    if (high >= low) {
                        widestTip = Math.max(widestTip, (high - low) / acrossSpan);
                    }
                }
            }
            return new Shape(
                    centerX,
                    centerY,
                    axisX,
                    axisY,
                    normalX,
                    normalY,
                    aspect,
                    widestTip < TAPERED_TIP_RATIO,
                    majorMin,
                    majorMax,
                    acrossMin,
                    acrossSpan,
                    vertexMajor,
                    vertexXs,
                    vertexYs);
        }
    }
}
