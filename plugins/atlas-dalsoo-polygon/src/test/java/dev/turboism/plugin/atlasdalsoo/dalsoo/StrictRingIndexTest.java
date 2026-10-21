package dev.turboism.plugin.atlasdalsoo.dalsoo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Random;
import org.junit.jupiter.api.Test;

class StrictRingIndexTest {
    @Test
    void indexedPredicatesMatchExhaustiveSearchForConcaveRingsAndContacts() {
        final Random random = new Random(180021);
        final StrictRingIndex moving = new StrictRingIndex();
        for (int run = 0; run < 500; run++) {
            final double[][] a = ring(random, 32 + random.nextInt(96), 0, 0);
            final double[][] b =
                    ring(random, 32 + random.nextInt(96), random.nextDouble() * 200, random.nextDouble() * 200);
            final StrictRingIndex fixed = new StrictRingIndex();
            moving.prepare(a);
            fixed.prepare(b);
            assertEquals(
                    Geom.overlapStrict(
                            a, Geom.boundingBox(a), Geom.centroid(a), b, Geom.boundingBox(b), Geom.centroid(b)),
                    Geom.overlapStrict(
                            a,
                            Geom.boundingBox(a),
                            Geom.centroid(a),
                            moving,
                            b,
                            Geom.boundingBox(b),
                            Geom.centroid(b),
                            fixed),
                    "pair " + run);
            for (final double[] point : a) assertEquals(Geom.insideStrict(point, b), fixed.inside(point));
            for (final double[] point : b) assertEquals(Geom.insideStrict(point, b), fixed.inside(point));
        }
    }

    @Test
    void disjointNearlyCollinearHeavyEdgesDoNotCrossThroughRoundoff() {
        assertFalse(Geom.segmentsCrossStrict(
                new double[] {125.86969280748866, 155.64172219766255},
                new double[] {125.93276841719424, 155.03792609705792},
                new double[] {130.14367600915472, 114.72869094420116},
                new double[] {130.13525099985966, 114.8093399903801}));
    }

    @Test
    void scratchCanBeReusedAcrossSizesAndDegenerateSlabs() {
        final StrictRingIndex index = new StrictRingIndex();
        for (final double[][] ring : new double[][][] {
            {{0, 0}, {100, 0}, {100, 100}, {0, 100}},
            {{0, 0}, {0, 0}, {10, 0}},
            {{-1e-8, -1e-8}, {1e-8, -1e-8}, {1e-8, 1e-8}, {-1e-8, 1e-8}}
        }) {
            index.reset(ring);
            for (double x : new double[] {-100, -1e-8, 0, 1e-8, 50, 100, 101}) {
                for (double y : new double[] {-100, -1e-8, 0, 1e-8, 50, 100, 101}) {
                    assertEquals(Geom.insideStrict(new double[] {x, y}, ring), index.inside(new double[] {x, y}));
                }
            }
        }
    }

    private static double[][] ring(final Random random, final int count, final double x, final double y) {
        final double[][] result = new double[count][2];
        for (int i = 0; i < count; i++) {
            final double angle = i * Math.PI * 2 / count;
            final double radius = 20 + random.nextDouble() * 80;
            result[i][0] = x + radius * Math.cos(angle);
            result[i][1] = y + radius * Math.sin(angle);
        }
        return result;
    }
}
