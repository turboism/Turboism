package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Region extraction over a {@link DomainMask}: four-connected components become autonomous
 * regions; an elongated or pinched region is recursively split along its thinnest column, and the
 * cut it was split on is emitted as a separator polyline (in UV) so the triangulator can keep the
 * boundary between sibling regions as hard edges.
 */
public final class RegionSegmenter {

    /** One autonomous placement region, as a subset of mask cells. */
    public record Region(int id, int[] cells, int minX, int minY, int maxX, int maxY) {
        /** Number of mask cells belonging to this region. */
        public int cellCount() {
            return cells.length;
        }
    }

    /**
     * @param regions final leaf regions
     * @param separators cut polylines in UV coordinates; every adjacent region pair shares one
     */
    public record Segmentation(List<Region> regions, List<List<Point2>> separators) {}

    /**
     * Segments the mask into autonomous leaf regions plus separator polylines.
     *
     * @param mask rasterized placement domain
     * @param settings recursion bounds (max depth, minimum region size, split aspect)
     * @return leaf regions and the UV polylines separating sibling regions
     */
    public static Segmentation segment(final DomainMask mask, final PlacementSettings settings) {
        final List<int[]> components = components(mask);
        final List<Region> regions = new ArrayList<>();
        final List<List<Point2>> separators = new ArrayList<>();
        final int[] nextId = {0};
        for (final int[] component : components) {
            split(mask, component, settings, 0, regions, separators, nextId);
        }
        return new Segmentation(regions, separators);
    }

    private static List<int[]> components(final DomainMask mask) {
        final int w = mask.width();
        final int h = mask.height();
        final boolean[] seen = new boolean[w * h];
        final List<int[]> out = new ArrayList<>();
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < w * h; start++) {
            if (seen[start] || !mask.solidCell(start)) {
                continue;
            }
            seen[start] = true;
            queue.add(start);
            final List<Integer> cells = new ArrayList<>();
            while (!queue.isEmpty()) {
                final int cell = queue.pollFirst();
                cells.add(cell);
                final int x = cell % w;
                final int y = cell / w;
                if (x > 0) enqueue(mask, seen, queue, cell - 1);
                if (x + 1 < w) enqueue(mask, seen, queue, cell + 1);
                if (y > 0) enqueue(mask, seen, queue, cell - w);
                if (y + 1 < h) enqueue(mask, seen, queue, cell + w);
            }
            final int[] packed = new int[cells.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = cells.get(i);
            }
            out.add(packed);
        }
        return out;
    }

    private static void enqueue(
            final DomainMask mask, final boolean[] seen, final ArrayDeque<Integer> queue, final int cell) {
        if (!seen[cell] && mask.solidCell(cell)) {
            seen[cell] = true;
            queue.addLast(cell);
        }
    }

    private static void split(
            final DomainMask mask,
            final int[] cells,
            final PlacementSettings settings,
            final int depth,
            final List<Region> regions,
            final List<List<Point2>> separators,
            final int[] nextId) {
        if (cells.length < settings.minRegionCells() || depth >= settings.maxDepth()) {
            regions.add(leaf(mask, cells, nextId));
            return;
        }
        final int w = mask.width();
        int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
        for (final int cell : cells) {
            final int x = cell % w;
            final int y = cell / w;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        final int spanX = maxX - minX + 1;
        final int spanY = maxY - minY + 1;
        final boolean vertical = spanX >= spanY; // split along the major axis
        final double aspect = (double) Math.max(spanX, spanY) / Math.max(1, Math.min(spanX, spanY));

        // Thickness profile along the major axis: cells of this region per perpendicular slice.
        final int majorSpan = vertical ? spanX : spanY;
        final int[] profile = new int[majorSpan];
        final boolean[] membership = new boolean[mask.width() * mask.height()];
        for (final int cell : cells) {
            membership[cell] = true;
            profile[(vertical ? cell % w : cell / w) - (vertical ? minX : minY)]++;
        }
        final int margin = Math.max(1, majorSpan / 7);
        int cutAt = -1;
        int cutWidth = Integer.MAX_VALUE;
        int sum = 0;
        for (int i = margin; i + margin < majorSpan; i++) {
            sum += profile[i];
            if (profile[i] < cutWidth) {
                cutWidth = profile[i];
                cutAt = i;
            }
        }
        if (cutAt < 0) {
            regions.add(leaf(mask, cells, nextId));
            return;
        }
        final int interiorSlices = Math.max(1, majorSpan - 2 * margin);
        final double median = sum / (double) interiorSlices;
        final boolean choke = cutWidth > 0 && cutWidth <= Math.max(1.5, 0.45 * median);
        final boolean elongated = aspect >= settings.splitAspect();
        if (!choke && !elongated) {
            regions.add(leaf(mask, cells, nextId));
            return;
        }
        if (cutWidth == 0) {
            // A fully empty interior slice already separates the halves; still emit the split so
            // the two sides stay autonomous.
        }
        final int cutLine = (vertical ? minX : minY) + cutAt;
        final List<Integer> left = new ArrayList<>();
        final List<Integer> right = new ArrayList<>();
        for (final int cell : cells) {
            final int coord = vertical ? cell % w : cell / w;
            (coord < cutLine ? left : right).add(cell);
        }
        if (left.isEmpty() || right.isEmpty()) {
            regions.add(leaf(mask, cells, nextId));
            return;
        }
        separators.addAll(emitSeparators(mask, membership, vertical, cutLine, minY, maxY, minX, maxX));
        split(mask, toArray(left), settings, depth + 1, regions, separators, nextId);
        split(mask, toArray(right), settings, depth + 1, regions, separators, nextId);
    }

    private static Region leaf(final DomainMask mask, final int[] cells, final int[] nextId) {
        final int w = mask.width();
        int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
        for (final int cell : cells) {
            minX = Math.min(minX, cell % w);
            maxX = Math.max(maxX, cell % w);
            minY = Math.min(minY, cell / w);
            maxY = Math.max(maxY, cell / w);
        }
        return new Region(nextId[0]++, cells, minX, minY, maxX, maxY);
    }

    /**
     * Emits the shared boundary between the two halves of a cut as UV polylines. Each contiguous
     * run of adjacent cells across the cut line produces one segment of the cell-corner lattice.
     */
    private static List<List<Point2>> emitSeparators(
            final DomainMask mask,
            final boolean[] membership,
            final boolean vertical,
            final int cutLine,
            final int minA,
            final int maxA,
            final int minB,
            final int maxB) {
        final int w = mask.width();
        final List<List<Point2>> out = new ArrayList<>();
        final int lo = vertical ? minA : minB;
        final int hi = vertical ? maxA : maxB;
        int runStart = -1;
        final int majorBound = vertical ? w : mask.height();
        for (int i = lo; i <= hi + 1; i++) {
            boolean shared = false;
            if (i <= hi && cutLine - 1 >= 0 && cutLine < majorBound) {
                final int leftCell = vertical ? i * w + cutLine - 1 : (cutLine - 1) * w + i;
                final int rightCell = vertical ? i * w + cutLine : cutLine * w + i;
                shared = membership[leftCell] && membership[rightCell];
            }
            if (shared && runStart < 0) {
                runStart = i;
            } else if (!shared && runStart >= 0) {
                final List<Point2> polyline = new ArrayList<>();
                for (int k = runStart; k <= i; k++) {
                    polyline.add(vertical ? cornerUv(mask, cutLine, k) : cornerUv(mask, k, cutLine));
                }
                out.add(polyline);
                runStart = -1;
            }
        }
        return out;
    }

    /** Corner-lattice point ({@code cx}, {@code cy}) in cell corners, mapped to UV. */
    static Point2 cornerUv(final DomainMask mask, final int cx, final int cy) {
        final double[] base = mask.cellCenterUv(0);
        final double u0 = base[0] - 0.5 * mask.cellUv();
        final double v0 = base[1] - 0.5 * mask.cellUv();
        return new Point2((float) (u0 + cx * mask.cellUv()), (float) (v0 + cy * mask.cellUv()));
    }

    private static int[] toArray(final List<Integer> cells) {
        final int[] out = new int[cells.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = cells.get(i);
        }
        return out;
    }
}
