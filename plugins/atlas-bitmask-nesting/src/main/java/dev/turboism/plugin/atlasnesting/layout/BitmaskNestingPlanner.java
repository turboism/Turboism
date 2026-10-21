package dev.turboism.plugin.atlasnesting.layout;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutQuality;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlacement;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-page concave nesting with conservative masks and run-skipping BLF.
 * No host state, wall-clock cutoffs, global caches or common-pool tasks are used.
 * Search work is bounded by the page grid and a fixed number of scale trials.
 */
public final class BitmaskNestingPlanner implements TextureAtlasPolygonPlanner {
    private final int gapOverride;

    public BitmaskNestingPlanner() {
        gapOverride = -1;
    }

    /** Explicit minimum inter-item gap, independent of the page-edge margin. */
    public BitmaskNestingPlanner(final int itemGap) {
        if (itemGap < 0) throw new IllegalArgumentException("Negative item gap");
        gapOverride = itemGap;
    }

    private record Source(
            TextureAtlasPolygonItem item, Shape shape, double area, double boxAngle, boolean rectangular) {}

    private record Candidate(OutlineRasterizer.Raster raster, int x, int y, double angle, double scale) {}

    private record Result(List<TextureAtlasPolygonPlacement> placements, List<String> overflow, int height) {}

    @Override
    public TextureAtlasPolygonPlan plan(
            final List<TextureAtlasPolygonItem> items, final TextureAtlasPolygonConstraints constraints) {
        return plan(items, constraints, false);
    }

    @Override
    public TextureAtlasPolygonPlan plan(
            final List<TextureAtlasPolygonItem> items,
            final TextureAtlasPolygonConstraints constraints,
            final boolean parallel) {
        final List<Source> sources = new ArrayList<>();
        final HashSet<String> ids = new HashSet<>();
        for (final TextureAtlasPolygonItem item : items) {
            if (!ids.add(item.textureId())) throw new NestingFailure("duplicate-id", item.textureId());
            if (!item.policy().participate() && !item.currentlyPlaced()) continue;
            final Shape shape = OutlineRasterizer.shape(item.outline(), new AffineTransform());
            if (shape.getBounds2D().isEmpty()) throw new NestingFailure("degenerate-outline", item.textureId());
            sources.add(new Source(
                    item,
                    shape,
                    area(item),
                    constraints.rotationMode() == TextureAtlasRotationMode.FREE
                                    && !item.policy().preserveAngle()
                            ? boxAngle(item)
                            : 0,
                    !fixed(item)
                            && new Area(shape).isRectangular()
                            && Math.abs(Math.IEEEremainder(item.currentAngleDeg(), 90)) < 1e-9));
        }
        sources.sort(Comparator.<Source>comparingDouble(
                        s -> s.area() * effectiveScale(s.item(), 1) * effectiveScale(s.item(), 1))
                .reversed()
                .thenComparing(s -> s.item().textureId()));
        // Default speed preset uses at most 512 cells per page side; DENSE keeps
        // twice the linear resolution. Both are outward conservative at edges.
        final int gridLimit = constraints.quality() == TextureAtlasLayoutQuality.DENSE ? 1024 : 512;
        final int cell =
                Math.max(1, (Math.max(constraints.pageWidth(), constraints.pageHeight()) + gridLimit - 1) / gridLimit);
        final int gap = gapOverride < 0 ? constraints.margin() : gapOverride;
        double scale = constraints.automaticScale() ? 1 : constraints.requestedScale();
        Result best = pack(sources, constraints, scale, cell, gap);
        int trials = 1;
        if (constraints.automaticScale() && !best.overflow().isEmpty()) {
            double low = 0, high = 1;
            Result feasible = null;
            final int limit = constraints.quality() == TextureAtlasLayoutQuality.FAST ? 6 : 10;
            // First find a complete layout, then improve its scale. Failed greedy
            // trials are search hints, not proofs of mathematical infeasibility.
            while (trials < limit) {
                final double trialScale = low == 0 ? high * 0.8 : (low + high) * 0.5;
                final Result trial = pack(sources, constraints, trialScale, cell, gap);
                trials++;
                if (trial.overflow().isEmpty()) {
                    feasible = trial;
                    low = trialScale;
                } else {
                    high = trialScale;
                    if (feasible == null
                            && trial.overflow().size() < best.overflow().size()) {
                        best = trial;
                        scale = trialScale;
                    }
                }
                if (low > 0 && high - low < low * 0.005) break;
            }
            if (feasible != null) {
                best = feasible;
                scale = low;
            }
        }
        final Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("kernel", "bitmask-nesting");
        diagnostics.put("rotationMode", constraints.rotationMode().name());
        diagnostics.put("cellPixels", String.valueOf(cell));
        diagnostics.put("itemGapPixels", String.valueOf(gap));
        diagnostics.put("scaleTrials", String.valueOf(trials));
        diagnostics.put("usedHeightPixels", String.valueOf(best.height() * cell));
        diagnostics.put(
                "search", sources.stream().allMatch(Source::rectangular) ? "exact-rectangle-bssf" : "run-skipping-blf");
        return new TextureAtlasPolygonPlan(
                constraints.pageWidth(),
                constraints.pageHeight(),
                scale,
                best.placements(),
                best.overflow(),
                TextureAtlasLayoutBackend.AUTO,
                diagnostics);
    }

    private static Result pack(
            final List<Source> sources,
            final TextureAtlasPolygonConstraints c,
            final double scale,
            final int cell,
            final int gap) {
        if (sources.stream().allMatch(Source::rectangular)) {
            final List<RectanglePacker.Box> boxes = new ArrayList<>();
            for (final Source source : sources) {
                final var item = source.item();
                final double itemScale = effectiveScale(item, scale);
                final var transform = AffineTransform.getRotateInstance(Math.toRadians(item.currentAngleDeg()));
                transform.scale(itemScale, itemScale);
                boxes.add(new RectanglePacker.Box(
                        item.textureId(),
                        transform.createTransformedShape(source.shape()).getBounds2D(),
                        item.currentAngleDeg(),
                        itemScale,
                        c.rotationMode() != TextureAtlasRotationMode.NONE
                                && !item.policy().preserveAngle()
                                && !item.policy().preservePosition()));
            }
            final var result = RectanglePacker.pack(boxes, c.pageWidth(), c.pageHeight(), c.margin(), gap);
            final var sorted = new ArrayList<>(result.placements());
            sorted.sort(Comparator.comparing(TextureAtlasPolygonPlacement::textureId));
            return new Result(List.copyOf(sorted), result.overflow(), (result.height() + cell - 1) / cell);
        }
        final BitMask board = new BitMask(
                Math.max(1, (c.pageWidth() + cell - 1) / cell), Math.max(1, (c.pageHeight() + cell - 1) / cell));
        final List<TextureAtlasPolygonPlacement> placements = new ArrayList<>();
        final List<String> overflow = new ArrayList<>();
        int usedHeight = 0;
        final List<Area> fixedAreas = new ArrayList<>();
        // Fixed/excluded on-page items are obstacles with their exact issued
        // translation. Rasterize them in canvas coordinates to preserve phase.
        for (final Source source : sources) {
            final TextureAtlasPolygonItem item = source.item();
            if (!fixed(item)) continue;
            final double[] matrix = item.currentMatrix();
            if (matrix == null) throw new NestingFailure("missing-transform", item.textureId());
            final Shape raw = new AffineTransform(matrix).createTransformedShape(source.shape());
            final var raster = OutlineRasterizer.raster(
                    raw, gap, cell, new Rectangle2D.Double(0, 0, c.pageWidth(), c.pageHeight()));
            final int x = (int) Math.round(raster.originX() / cell), y = (int) Math.round(raster.originY() / cell);
            if (!inside(raw.getBounds2D(), c)
                    || x < 0
                    || y < 0
                    || x + raster.mask().width > board.width
                    || y + raster.mask().height > board.height) {
                throw new NestingFailure("fixed-margin", item.textureId());
            }
            // Fixed transforms have no grid phase freedom. A shared conservative
            // cell is not proof of an actual overlap or spacing violation.
            final Area expanded = new Area(raw);
            if (gap > 0)
                expanded.add(new Area(
                        new BasicStroke(gap, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(raw)));
            for (final Area previous : fixedAreas) {
                if (!expanded.getBounds2D().intersects(previous.getBounds2D())) continue;
                final Area overlap = new Area(expanded);
                overlap.intersect(previous);
                if (!overlap.isEmpty()) throw new NestingFailure("fixed-gap", item.textureId());
            }
            fixedAreas.add(expanded);
            board.add(raster.mask(), x, y);
            placements.add(new TextureAtlasPolygonPlacement(
                    item.textureId(), matrix[4], matrix[5], item.currentAngleDeg(), item.currentScale()));
            usedHeight = Math.max(usedHeight, y + raster.mask().height);
        }
        for (final Source source : sources) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Nesting interrupted");
            final TextureAtlasPolygonItem item = source.item();
            if (fixed(item)) continue;
            final double itemScale = effectiveScale(item, scale);
            Candidate best = null;
            final Map<Double, OutlineRasterizer.Raster> rasters = new LinkedHashMap<>();
            final int coarseStep = c.quality() == TextureAtlasLayoutQuality.DENSE ? 1 : Math.max(1, 8 / cell);
            for (final int step : coarseStep == 1 ? new int[] {1} : new int[] {coarseStep, 1}) {
                for (final double angle : angles(source, c)) {
                    final AffineTransform transform = AffineTransform.getRotateInstance(Math.toRadians(angle));
                    transform.scale(itemScale, itemScale);
                    final Shape raw = transform.createTransformedShape(source.shape());
                    if (raw.getBounds2D().getWidth() > c.pageWidth() - 2.0 * c.margin() + 1e-7
                            || raw.getBounds2D().getHeight() > c.pageHeight() - 2.0 * c.margin() + 1e-7) continue;
                    final var raster = rasterAt(raw, angle, gap, cell, rasters);
                    final int xMin = Math.max(
                            0,
                            ceil((c.margin()
                                            + raster.originX()
                                            - raster.rawBounds().getMinX())
                                    / cell));
                    final int yMin = Math.max(
                            0,
                            ceil((c.margin()
                                            + raster.originY()
                                            - raster.rawBounds().getMinY())
                                    / cell));
                    final int xMax = Math.min(
                            board.width - raster.mask().width,
                            floor((c.pageWidth()
                                            - c.margin()
                                            + raster.originX()
                                            - raster.rawBounds().getMaxX())
                                    / cell));
                    int yMax = Math.min(
                            board.height - raster.mask().height,
                            floor((c.pageHeight()
                                            - c.margin()
                                            + raster.originY()
                                            - raster.rawBounds().getMaxY())
                                    / cell));
                    if (best != null)
                        yMax = Math.min(
                                yMax,
                                Math.max(usedHeight, best.y() + best.raster().mask().height) - raster.mask().height);
                    final Candidate found = find(board, raster, xMin, xMax, yMin, yMax, angle, itemScale, step);
                    if (found != null && better(found, best, usedHeight)) best = found;
                    // Filling an existing-height pocket already avoids all container
                    // growth. Do not re-scan the whole page at every other angle just
                    // to improve a y/x tie in the default speed-oriented preset.
                    if (best != null
                            && c.quality() != TextureAtlasLayoutQuality.DENSE
                            && best.y() + best.raster().mask().height <= usedHeight) break;
                }
                // Retry at full grid resolution when coarse candidate positions miss
                // every fit. The collision masks themselves are NEVER downsampled.
                if (best != null) break;
            }
            if (best == null) {
                overflow.add(item.textureId());
                continue;
            }
            board.add(best.raster().mask(), best.x(), best.y());
            usedHeight = Math.max(usedHeight, best.y() + best.raster().mask().height);
            placements.add(new TextureAtlasPolygonPlacement(
                    item.textureId(),
                    best.x() * (double) cell - best.raster().originX(),
                    best.y() * (double) cell - best.raster().originY(),
                    best.angle(),
                    best.scale()));
        }
        placements.sort(Comparator.comparing(TextureAtlasPolygonPlacement::textureId));
        return new Result(List.copyOf(placements), List.copyOf(overflow), usedHeight);
    }

    private static OutlineRasterizer.Raster rasterAt(
            final Shape raw,
            final double angle,
            final int gap,
            final int cell,
            final Map<Double, OutlineRasterizer.Raster> rasters) {
        if (rasters.containsKey(angle)) return rasters.get(angle);
        OutlineRasterizer.Raster result = null;
        for (final var entry : rasters.entrySet()) {
            final double turns = Math.IEEEremainder(angle - entry.getKey(), 360) / 90;
            final int rounded = (int) Math.round(turns);
            if (Math.abs(turns - rounded) < 1e-9) {
                result = OutlineRasterizer.quarterTurn(entry.getValue(), Math.floorMod(rounded, 4), cell);
                break;
            }
        }
        if (result == null) result = OutlineRasterizer.raster(raw, gap, cell);
        rasters.put(angle, result);
        return result;
    }

    private static Candidate find(
            final BitMask board,
            final OutlineRasterizer.Raster raster,
            final int xMin,
            final int xMax,
            final int yMin,
            final int yMax,
            final double angle,
            final double scale,
            final int step) {
        if (xMin > xMax) return null;
        for (int y = yMin; y <= yMax; y += step) {
            if ((y & 63) == 0 && Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Nesting interrupted");
            }
            for (int x = xMin; x <= xMax; ) {
                final BitMask mask = raster.mask();
                // A necessary condition: the longest solid row segment must
                // fit a clear canvas run. This rejects crowded rows without
                // inspecting every candidate or weakening collision accuracy.
                final int anchor = board.clearRun(
                        y + mask.anchorY,
                        x + mask.anchorX,
                        xMax + mask.anchorX + mask.anchorLength - 1,
                        mask.anchorLength);
                if (anchor < 0) {
                    // The solid anchor cannot fit anywhere in this row. Every
                    // identical canvas row proves the same rejection for all x.
                    if (x == xMin) {
                        final int rows = board.sameRowEnd(y + mask.anchorY) - (y + mask.anchorY);
                        y += rows / step * step;
                    }
                    break;
                }
                x = xMin + ((anchor - mask.anchorX - xMin + step - 1) / step) * step;
                if (x > xMax) break;
                final int filtered = board.filterSkip(mask, x, y, xMax);
                if (filtered > 0) {
                    x += ((filtered + step - 1) / step) * step;
                    continue;
                }
                final int skip = board.collisionSkip(raster.mask(), x, y);
                if (skip == 0) return new Candidate(raster, x, y, angle, scale);
                x += ((skip + step - 1) / step) * step;
            }
        }
        return null;
    }

    private static boolean better(final Candidate a, final Candidate b, final int usedHeight) {
        if (b == null) return true;
        final int ah = Math.max(usedHeight, a.y() + a.raster().mask().height);
        final int bh = Math.max(usedHeight, b.y() + b.raster().mask().height);
        if (ah != bh) return ah < bh;
        if (a.y() != b.y()) return a.y() < b.y();
        return a.x() < b.x();
    }

    private static boolean fixed(final TextureAtlasPolygonItem item) {
        return item.currentlyPlaced()
                && (!item.policy().participate() || item.policy().preservePosition());
    }

    private static double effectiveScale(final TextureAtlasPolygonItem item, final double requested) {
        return item.policy().preserveScale() || !item.policy().participate() ? item.currentScale() : requested;
    }

    private static List<Double> angles(final Source source, final TextureAtlasPolygonConstraints c) {
        final TextureAtlasPolygonItem item = source.item();
        final TextureAtlasItemLayoutPolicy policy = item.policy();
        final double issued = item.currentAngleDeg();
        if (policy.preserveAngle() || policy.preservePosition() || c.rotationMode() == TextureAtlasRotationMode.NONE)
            return List.of(issued);
        final List<Double> result = new ArrayList<>();
        for (int i = 0; i < 4; i++) result.add(issued + i * 90);
        if (c.rotationMode() == TextureAtlasRotationMode.FREE) {
            for (int i = 0; i < 4; i++) addAngle(result, source.boxAngle() + i * 90);
            if (c.quality() == TextureAtlasLayoutQuality.DENSE) {
                for (int i = 0; i < 12; i++) addAngle(result, i * 30);
            }
        }
        return result;
    }

    private static void addAngle(final List<Double> angles, final double angle) {
        for (final double other : angles) {
            if (Math.abs(Math.IEEEremainder(other - angle, 360)) < 0.5) return;
        }
        angles.add(angle);
    }

    /** Convex hull is ONLY used for the candidate OBB direction, never collision. */
    private static double boxAngle(final TextureAtlasPolygonItem item) {
        final List<double[]> points = new ArrayList<>();
        for (final double[][] ring : item.outline().rings()) for (final double[] p : ring) points.add(p);
        points.sort(Comparator.<double[]>comparingDouble(p -> p[0]).thenComparingDouble(p -> p[1]));
        final List<double[]> hull = new ArrayList<>();
        for (final double[] p : points) {
            while (hull.size() > 1 && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p) <= 0) {
                hull.remove(hull.size() - 1);
            }
            hull.add(p);
        }
        final int lower = hull.size();
        for (int i = points.size() - 2; i >= 0; i--) {
            final double[] p = points.get(i);
            while (hull.size() > lower && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p) <= 0) {
                hull.remove(hull.size() - 1);
            }
            hull.add(p);
        }
        double best = Double.POSITIVE_INFINITY, angle = 0;
        for (int i = 0; i + 1 < hull.size(); i++) {
            final double[] a = hull.get(i), b = hull.get(i + 1);
            final double theta = Math.atan2(b[1] - a[1], b[0] - a[0]);
            final double cos = Math.cos(theta), sin = Math.sin(theta);
            double minX = Double.POSITIVE_INFINITY, minY = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
            for (final double[] p : hull) {
                final double x = cos * p[0] + sin * p[1], y = -sin * p[0] + cos * p[1];
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            final double area = (maxX - minX) * (maxY - minY);
            if (area < best) {
                best = area;
                angle = -Math.toDegrees(theta);
            }
        }
        return angle;
    }

    private static double cross(final double[] a, final double[] b, final double[] p) {
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
    }

    private static double area(final TextureAtlasPolygonItem item) {
        double sum = 0;
        for (final double[][] ring : item.outline().rings()) {
            double signed = 0;
            for (int i = 0; i < ring.length; i++) {
                final double[] a = ring[i], b = ring[(i + 1) % ring.length];
                signed += a[0] * b[1] - a[1] * b[0];
            }
            sum += Math.abs(signed) * 0.5;
        }
        return sum;
    }

    private static boolean inside(final Rectangle2D bounds, final TextureAtlasPolygonConstraints c) {
        return bounds.getMinX() >= c.margin() - 1e-7
                && bounds.getMinY() >= c.margin() - 1e-7
                && bounds.getMaxX() <= c.pageWidth() - c.margin() + 1e-7
                && bounds.getMaxY() <= c.pageHeight() - c.margin() + 1e-7;
    }

    private static int ceil(final double value) {
        return (int) Math.ceil(value - 1e-9);
    }

    private static int floor(final double value) {
        return (int) Math.floor(value + 1e-9);
    }
}
