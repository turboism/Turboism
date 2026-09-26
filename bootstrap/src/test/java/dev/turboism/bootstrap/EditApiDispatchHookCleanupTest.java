package dev.turboism.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditApiDispatchHookCleanupTest {

    @Test
    void failureToCloseDispatcherStillRemovesEveryToggleResource() {
        final List<String> closed = new ArrayList<>();
        final IllegalStateException dispatchFailure = new IllegalStateException("dispatcher");
        final IllegalArgumentException toggleFailure = new IllegalArgumentException("toggle");

        final Exception failure = assertThrows(Exception.class, () ->
            EditApiDispatchHookContributor.closeResources(List.of(
                () -> closed.add("first"),
                () -> { closed.add("second"); throw toggleFailure; }
            ), () -> { closed.add("dispatcher"); throw dispatchFailure; }));

        assertEquals(List.of("dispatcher", "second", "first"), closed);
        assertSame(dispatchFailure, failure);
        assertEquals(List.of(toggleFailure), List.of(failure.getSuppressed()));
    }

    @Test
    void failedCreationStillClosesResourcesWhenNoDispatcherExists() throws Exception {
        final List<String> closed = new ArrayList<>();

        EditApiDispatchHookContributor.closeResources(List.of(() -> closed.add("toggle")), null);

        assertEquals(List.of("toggle"), closed);
    }
}
