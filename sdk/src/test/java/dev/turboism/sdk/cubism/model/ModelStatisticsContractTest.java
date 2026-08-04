package dev.turboism.sdk.cubism.model;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelStatisticsContractTest {

    @Test
    void exposesImmutableValidatedCrossVersionStatistics() {
        final ModelStatistics statistics = new ModelStatistics(
            3, 4, 5, 5, 2, 120, 60, 2, 3, 2,
            OptionalInt.of(2), OptionalInt.of(1)
        );

        assertEquals(3, statistics.parameterCount());
        assertEquals(5, statistics.artMeshCount());
        assertEquals(120, statistics.vertexCount());
        assertEquals(OptionalInt.of(2), statistics.offscreenRenderingCount());
        assertEquals(OptionalInt.of(1), statistics.maxOffscreenDepth());
        assertThrows(IllegalArgumentException.class, () -> new ModelStatistics(
            -1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            OptionalInt.empty(), OptionalInt.empty()
        ));
    }
}
