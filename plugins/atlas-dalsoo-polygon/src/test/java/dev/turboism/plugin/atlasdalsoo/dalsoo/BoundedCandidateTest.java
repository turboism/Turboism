package dev.turboism.plugin.atlasdalsoo.dalsoo;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedCandidateTest {
    @Test
    void sampledAnchorsRetainUnsampledConcaveEdgesForCollisionChecks() {
        for (final boolean abey : new boolean[] {true, false}) {
            final List<SourcePoly> sources = new ArrayList<>();
            for (int id = 0; id < 12; id++) {
                final double[][] ring = new double[80][2];
                for (int vertex = 0; vertex < ring.length; vertex++) {
                    final double angle = 2 * Math.PI * vertex / ring.length;
                    final double radius = vertex % 2 == 0 ? 20 : 8;
                    ring[vertex] = new double[] {20 + radius * Math.cos(angle), 20 + radius * Math.sin(angle)};
                }
                sources.add(SourcePoly.builder(
                                id,
                                "star-" + id,
                                ring,
                                java.util.Arrays.stream(ring)
                                        .map(double[]::clone)
                                        .toArray(double[][]::new))
                        .build());
            }
            final var result = new PolygonPack(300, 300, TextureAtlasRotationMode.FREE, abey, .7, null, 8)
                    .pack(sources, () -> false, null);
            assertTrue(
                    result.outcomes.size() >= 2,
                    "Expected multiple collision-checked placements; abey=" + abey + ", placed="
                            + result.outcomes.size());
            final List<Area> placed = new ArrayList<>();
            for (final var outcome : result.outcomes) {
                final var path = new Path2D.Double();
                final double[][] ring = sources.get(outcome.id).inpts;
                for (int vertex = 0; vertex < ring.length; vertex++) {
                    final double x = outcome.cosSin[0] * ring[vertex][0]
                            - outcome.cosSin[1] * ring[vertex][1]
                            + outcome.trans[0];
                    final double y = outcome.cosSin[1] * ring[vertex][0]
                            + outcome.cosSin[0] * ring[vertex][1]
                            + outcome.trans[1];
                    if (vertex == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.closePath();
                final var area = new Area(path);
                for (final Area previous : placed) {
                    final var intersection = new Area(area);
                    intersection.intersect(previous);
                    // Java Area can retain microscopic floating-point slivers at touching edges.
                    assertTrue(area(intersection) < 1e-6, "Intersection area=" + area(intersection) + "; abey=" + abey);
                }
                placed.add(area);
            }
        }
    }

    private static double area(final Area area) {
        final var iterator = area.getPathIterator(null);
        final double[] point = new double[6];
        double total = 0, x = 0, y = 0, startX = 0, startY = 0;
        while (!iterator.isDone()) {
            final int kind = iterator.currentSegment(point);
            if (kind == 0) {
                startX = x = point[0];
                startY = y = point[1];
            } else if (kind == 1) {
                total += x * point[1] - point[0] * y;
                x = point[0];
                y = point[1];
            } else if (kind == 4) total += x * startY - startX * y;
            iterator.next();
        }
        return Math.abs(total) * .5;
    }

    @Test
    void automaticScalePublishesProgressBeforeAnAttemptFinishes() {
        final var sources = PolygonPackCompatibilityTest.sources("lshape", 5);
        final List<AutoScalePack.Prepared> items = new ArrayList<>();
        for (int id = 0; id < 5; id++) {
            items.add(new AutoScalePack.Prepared(
                    id,
                    "item-" + id,
                    sources.get(id).inpts,
                    sources.get(id).outpts,
                    false,
                    false,
                    false,
                    new double[] {1, 0},
                    new double[] {0, 0},
                    false,
                    1));
        }
        final List<Double> progress = new ArrayList<>();
        final var outcome = new AutoScalePack(500, 500, TextureAtlasRotationMode.NONE, false, .7, null, 24)
                .autoScalePack(items, .005, 12, () -> false, progress::add);
        assertTrue(outcome.attempts >= 1);
        assertTrue(progress.stream().anyMatch(value -> value > 0 && value < 1.0 / 12));
        for (int index = 1; index < progress.size(); index++)
            assertTrue(progress.get(index) >= progress.get(index - 1));
    }
}
