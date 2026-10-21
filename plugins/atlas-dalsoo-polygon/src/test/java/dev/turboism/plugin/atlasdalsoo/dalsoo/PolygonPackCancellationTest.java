package dev.turboism.plugin.atlasdalsoo.dalsoo;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PolygonPackCancellationTest {
    @Test
    void cancellationIsPolledInsideTheSearchForASingleRemainingItem() {
        for (final boolean abey : new boolean[] {false, true}) {
            final double[][] obstacle = {{0, 0}, {20, 0}, {20, 20}, {0, 20}};
            final double[][] outline = {{0, 0}, {60, 0}, {60, 30}, {30, 30}, {30, 60}, {0, 60}};
            final var sources = List.of(
                    SourcePoly.builder(0, "fixed", obstacle, copy(obstacle))
                            .fixPosition(true)
                            .issuedPlaced(true)
                            .issuedPosition(new double[] {0, 0})
                            .build(),
                    SourcePoly.builder(1, "moving", outline, copy(outline)).build());
            final AtomicInteger polls = new AtomicInteger();
            assertThrows(
                    PackingCancelledException.class,
                    () -> new PolygonPack(200, 200, TextureAtlasRotationMode.FREE, abey, .7, null)
                            .pack(sources, () -> polls.incrementAndGet() >= 3, ignored -> {}));
        }
    }

    @Test
    void interruptedWorkerStopsEvenWithoutAnExternalCancellationFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    PackingCancelledException.class,
                    () -> new PolygonPack(200, 200, TextureAtlasRotationMode.NONE, false, .7, null)
                            .pack(List.of(), () -> false, ignored -> {}));
        } finally {
            Thread.interrupted();
        }
    }

    private static double[][] copy(final double[][] ring) {
        return java.util.Arrays.stream(ring).map(double[]::clone).toArray(double[][]::new);
    }
}
