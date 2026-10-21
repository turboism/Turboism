package dev.turboism.plugin.atlasnesting.layout;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.Random;
import org.junit.jupiter.api.Test;

class OutlineRasterizerTest {
    @Test
    void directCapsulesCoverExactRoundPaddingAtEveryIntersectedCell() {
        final Random random = new Random(9417);
        for (int sample = 0; sample < 14; sample++) {
            final Path2D.Double path = new Path2D.Double();
            final int points = sample == 0 ? 3 : 5 + random.nextInt(20);
            for (int i = 0; i < points; i++) {
                final double angle = 2 * Math.PI * i / points;
                final double r = 1 + random.nextDouble() * 15;
                final double x = -2.3 + Math.cos(angle) * r;
                final double y = sample == 0 ? x * .001 + i * .0001 : 3.7 + Math.sin(angle) * r;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.closePath();
            final Shape raw = new Area(path);
            for (final int gap : new int[] {0, 1, 6, 15}) {
                for (final int cell : new int[] {1, 4, 8}) {
                    final Area exact = new Area(raw);
                    if (gap > 0)
                        exact.add(new Area(new BasicStroke(gap, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                                .createStrokedShape(raw)));
                    final var raster = OutlineRasterizer.raster(raw, gap, cell);
                    assertCovered(raster, exact, cell);
                    for (int turn = 1; turn < 4; turn++) {
                        final var rotated = OutlineRasterizer.quarterTurn(raster, turn, cell);
                        assertCovered(
                                rotated,
                                exact.createTransformedArea(AffineTransform.getQuadrantRotateInstance(turn)),
                                cell);
                    }
                    final var clip = new Rectangle2D.Double(0, 0, 12.3, 17.2);
                    final Area clipped = new Area(exact);
                    clipped.intersect(new Area(clip));
                    if (!clipped.isEmpty())
                        assertCovered(OutlineRasterizer.raster(raw, gap, cell, clip), clipped, cell);
                }
            }
        }
    }

    private static void assertCovered(OutlineRasterizer.Raster raster, Area exact, int cell) {
        final var mask = raster.mask();
        for (int y = -1; y <= mask.height; y++) {
            for (int x = -1; x <= mask.width; x++) {
                if (!exact.intersects(raster.originX() + x * cell, raster.originY() + y * cell, cell, cell)) continue;
                final boolean occupied = x >= 0
                        && y >= 0
                        && x < mask.width
                        && y < mask.height
                        && (mask.words[y * mask.stride + x / 64] & (1L << (x & 63))) != 0;
                if (occupied) continue;
                final Area intersection = new Area(exact);
                intersection.intersect(new Area(
                        new Rectangle2D.Double(raster.originX() + x * cell, raster.originY() + y * cell, cell, cell)));
                // Area's clipping creates sub-picometre remnants at exact clip
                // boundaries. Reject real omitted cells, not these numeric slivers.
                final var overlap = intersection.getBounds2D();
                assertTrue(
                        intersection.isEmpty() || overlap.getWidth() < 1e-9 || overlap.getHeight() < 1e-9,
                        "Exact occupied cell omitted: " + x + "," + y + " cell=" + cell + " overlap=" + overlap);
            }
        }
    }
}
