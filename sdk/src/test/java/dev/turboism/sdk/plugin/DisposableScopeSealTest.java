package dev.turboism.sdk.plugin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisposableScopeSealTest {

    @Test
    void sealRejectsNewRegistrationsWithoutRunningClosers() throws Exception {
        final DisposableScope scope = new DisposableScope();
        final List<String> closed = new ArrayList<>();
        scope.register(() -> closed.add("first"));

        scope.seal();

        assertTrue(scope.isSealed());
        assertTrue(closed.isEmpty(), "seal must not dispose registered resources");
        assertThrows(IllegalStateException.class, () -> scope.register(() -> closed.add("late")));

        scope.close();
        assertEquals(List.of("first"), closed, "close after seal still disposes prior registrations");
        assertTrue(scope.isSealed());
    }

    @Test
    void closeSealsBeforeDisposing() throws Exception {
        final DisposableScope scope = new DisposableScope();
        final List<String> order = new ArrayList<>();
        scope.register(() -> {
            order.add("closer");
            assertTrue(scope.isSealed(), "close implies the registration fence is already up");
            assertThrows(
                IllegalStateException.class,
                () -> scope.register(() -> order.add("nested"))
            );
        });
        scope.close();
        assertEquals(List.of("closer"), order);
    }

    @Test
    void unsealedScopeAcceptsRegistrations() {
        final DisposableScope scope = new DisposableScope();
        assertFalse(scope.isSealed());
        scope.register(() -> { }).close();
    }
}
