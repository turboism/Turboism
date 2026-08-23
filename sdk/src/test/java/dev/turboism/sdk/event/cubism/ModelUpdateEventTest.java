package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.event.CubismOperationEvent;
import dev.turboism.sdk.cubism.event.CubismOperationOrigin;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelUpdateEventTest {
    @Test
    void acceptsOnlyUpdateModelCorrelations() {
        final CubismOperationEvent update = operation(CubismOperation.UPDATE_MODEL);

        assertEquals(update, new ModelUpdateEvent.Before(update).operation());
        assertEquals(update, new ModelUpdateEvent.On(update).operation());
        assertEquals(update, new ModelUpdateEvent.After(update).operation());
        assertThrows(
            IllegalArgumentException.class,
            () -> new ModelUpdateEvent.Before(operation(CubismOperation.OPEN_DOCUMENT))
        );
    }

    private static CubismOperationEvent operation(final CubismOperation operation) {
        return new CubismOperationEvent(
            1L,
            operation,
            CubismOperationOrigin.TURBOISM_API,
            Optional.of("ModelA")
        );
    }
}
