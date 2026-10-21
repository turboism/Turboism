package dev.turboism.plugin.atlasdalsoo.dalsoo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class GeomTest {
    @Test
    void pointContainmentMatchesOriginalArithmeticIncludingBoundaryAndConcavity() {
        final Random random = new Random(62419);
        for (final double scale : new double[] {1e-160, 1e-8, 1, 1e8}) {
            for (int n = 3; n <= 30; n++) {
                final double[][] ring = new double[n][2];
                for (int i = 0; i < n; i++) {
                    final double angle = 2 * Math.PI * i / n;
                    final double radius = (i % 2 == 0 ? 10 : 4) * scale;
                    ring[i] = new double[] {radius * Math.cos(angle), radius * Math.sin(angle)};
                }
                for (int i = 0; i < 100; i++) {
                    final double[] point = {
                        (random.nextDouble() * 24 - 12) * scale, (random.nextDouble() * 24 - 12) * scale
                    };
                    assertEquals(originalInside(point, ring), Geom.insidePolygon(point, ring));
                }
                for (int i = 0; i < n; i++) {
                    assertEquals(originalInside(ring[i], ring), Geom.insidePolygon(ring[i], ring));
                    final double[] midpoint = Geom.mid(ring[i], ring[(i + 1) % n]);
                    assertEquals(originalInside(midpoint, ring), Geom.insidePolygon(midpoint, ring));
                }
            }
        }
    }

    // Frozen upstream implementation: keeps the differential oracle independent
    // of the allocation optimizations in the production point-containment path.
    private static boolean originalInside(final double[] p, final double[][] poly) {
        final int len = poly.length;
        final double[][] shifted = new double[len][];
        for (int i = 0; i < len; i++) {
            shifted[i] = new double[] {poly[i][0] - p[0], poly[i][1] - p[1]};
        }
        final double[][] trans = new double[len + 1][];
        int num = 0;
        for (int i = 0; i < len; i++) {
            final double[] a = shifted[i];
            final double[] b = shifted[(i + 1) % len];
            if (a[0] * b[1] - a[1] * b[0] == 0 && a[0] * b[0] + a[1] * b[1] <= 0) {
                return true;
            }
            if ((a[1] < 0 && b[1] >= 0) || (a[1] >= 0 && b[1] < 0)) {
                trans[num++] = new double[] {a[0] - a[1] * (b[0] - a[0]) / (b[1] - a[1]), 0};
            }
        }
        if (num < 2) {
            return false;
        }
        boolean inside = true;
        for (int i = 0; i < num - 1; i++) {
            for (int j = i + 1; j < num; j++) {
                if (trans[i][0] * trans[j][0] < 0) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }
}
