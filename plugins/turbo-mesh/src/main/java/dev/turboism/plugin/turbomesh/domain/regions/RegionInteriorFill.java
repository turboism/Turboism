package dev.turboism.plugin.turbomesh.domain.regions;

/**
 * Fills each leaf region with a staggered interior lattice.
 *
 * <p>Interior sites stay one clearance step away from every border, so the triangulation never
 * needs slivers against a hard constraint. The lattice is independent per leaf because a shared
 * border already carries its own samples.</p>
 */
final class RegionInteriorFill {

    /** Row pitch of the staggered lattice, as a fraction of the interior spacing. */
    static final double ROW_PITCH_RATIO = 0.866_025_403_784_438_6;

    /** Column offset of every second row, as a fraction of the interior spacing. */
    static final double ROW_STAGGER_RATIO = 0.5;

    /** Clearance below which a candidate would create a sliver, as a fraction of the spacing. */
    static final double CLEARANCE_RATIO = 0.32;

    private RegionInteriorFill() {}

    /**
     * @param clearance chamfer distance to the nearest border pixel, in pixels
     * @param interiorSpacing lattice pitch in pixels
     */
    static void fill(
            final SiteGraph graph,
            final int[] labels,
            final int width,
            final int height,
            final double[] clearance,
            final double interiorSpacing,
            final PolygonShape domain) {
        final int labelCount = maxLabel(labels) + 1;
        final int[] minX = new int[labelCount];
        final int[] minY = new int[labelCount];
        final int[] maxX = new int[labelCount];
        final int[] maxY = new int[labelCount];
        for (int i = 0; i < labelCount; i++) {
            minX[i] = Integer.MAX_VALUE;
            minY[i] = Integer.MAX_VALUE;
            maxX[i] = Integer.MIN_VALUE;
            maxY[i] = Integer.MIN_VALUE;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int label = labels[y * width + x];
                if (label == 0) {
                    continue;
                }
                minX[label] = Math.min(minX[label], x);
                maxX[label] = Math.max(maxX[label], x);
                minY[label] = Math.min(minY[label], y);
                maxY[label] = Math.max(maxY[label], y);
            }
        }
        final double minimumClearance = interiorSpacing * CLEARANCE_RATIO;
        for (int label = 1; label < labelCount; label++) {
            if (maxX[label] < minX[label]) {
                continue;
            }
            final double rowPitch = interiorSpacing * ROW_PITCH_RATIO;
            int row = 0;
            for (double y = minY[label]; y <= maxY[label]; y += rowPitch, row++) {
                final int rowY = (int) Math.round(y);
                final double start = minX[label] + interiorSpacing * ROW_STAGGER_RATIO * (row & 1);
                for (double x = start; x <= maxX[label]; x += interiorSpacing) {
                    final int columnX = (int) Math.round(x);
                    if (columnX < 0 || rowY < 0 || columnX >= width || rowY >= height) {
                        continue;
                    }
                    final int cell = rowY * width + columnX;
                    if (labels[cell] != label || clearance[cell] <= minimumClearance) {
                        continue;
                    }
                    if (domain.contains(x, y)) {
                        graph.site(x, y);
                    }
                }
            }
        }
    }

    private static int maxLabel(final int[] labels) {
        int max = 0;
        for (final int label : labels) {
            max = Math.max(max, label);
        }
        return max;
    }
}
