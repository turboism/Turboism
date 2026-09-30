package dev.turboism.test.plugin;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.plugin.DisposableScope;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests that DisposableScope closes registered resources in reverse order and allows removal.
 */
class DisposableScopeLeakTest {

    @Test
    void closesRegisteredResources() throws Exception {
        DisposableScope scope = new DisposableScope();
        AtomicInteger closed = new AtomicInteger(0);

        scope.register(() -> closed.addAndGet(10));
        scope.register(() -> closed.addAndGet(1));

        scope.close();

        assertEquals(11, closed.get(), "Both resources should be closed");
    }

    @Test
    void registrationCanBeRemoved() throws Exception {
        DisposableScope scope = new DisposableScope();
        AtomicInteger closed = new AtomicInteger(0);

        var registration = scope.register(() -> closed.incrementAndGet());
        registration.close();

        assertEquals(1, closed.get(), "Removed registration should be closed exactly once");

        scope.close();

        assertEquals(1, closed.get(), "Removed registration should not be closed again");
    }

    @Test
    void closeIsIdempotent() {
        DisposableScope scope = new DisposableScope();
        assertDoesNotThrow(scope::close);
        assertDoesNotThrow(scope::close);
    }
}
