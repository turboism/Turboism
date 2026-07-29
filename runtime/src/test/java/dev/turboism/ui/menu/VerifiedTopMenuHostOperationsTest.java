package dev.turboism.ui.menu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedTopMenuHostOperationsTest {

    @Test
    void cleanupRemovesHostAndSwingEntriesThenRefreshesEvenAfterFailure() {
        List<String> operations = new ArrayList<>();

        VerifiedTopMenuHostOperations.cleanupMenu(
            () -> operations.add("host-list"),
            () -> operations.add("swing"),
            () -> operations.add("refresh")
        );
        assertEquals(List.of("host-list", "swing", "refresh"), operations);

        operations.clear();
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> VerifiedTopMenuHostOperations.cleanupMenu(
                () -> {
                    operations.add("host-list");
                    throw new IllegalStateException("host remove failed");
                },
                () -> operations.add("swing"),
                () -> operations.add("refresh")
            )
        );
        assertEquals("host remove failed", failure.getMessage());
        assertEquals(List.of("host-list", "swing", "refresh"), operations);
    }
}
