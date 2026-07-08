package dev.turboism.core.menu;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeMenuRegistryTest {

    @Test
    void contributionIsRegisteredAndCanBeUnregistered() {
        // Given a registry with a capturing dispatcher
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        RuntimeMenuRegistry registry = new RuntimeMenuRegistry(dispatcher, "dev.turboism.plugin.demo");

        // When a contribution is registered
        Registration registration = registry.contribute(new StubContribution("view/selection", "select-all", 10));

        // Then it is present and a visibility update was dispatched
        assertTrue(registry.isRegistered("select-all"));
        assertEquals(1, dispatcher.dispatched.size());

        // When the registration is closed
        registration.close();

        // Then the contribution is removed and another visibility update was dispatched
        assertFalse(registry.isRegistered("select-all"));
        assertEquals(2, dispatcher.dispatched.size());
    }

    @Test
    void closingRegistrationRemovesContribution() {
        // Given a registry with one contribution
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        RuntimeMenuRegistry registry = new RuntimeMenuRegistry(dispatcher, "dev.turboism.plugin.demo");
        Registration registration = registry.contribute(new StubContribution("file", "open-project", 5));

        // When the registration is closed
        registration.close();

        // Then the contribution is gone
        assertFalse(registry.isRegistered("open-project"));
        assertEquals(0, registry.registrationCount());
    }

    @Test
    void duplicateIdsAreHandledDeterministically() {
        // Given a registry with a registered contribution
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        RuntimeMenuRegistry registry = new RuntimeMenuRegistry(dispatcher, "dev.turboism.plugin.demo");
        Registration first = registry.contribute(new StubContribution("edit", "undo", 1));

        // When a contribution with the same action id is registered again
        Registration second = registry.contribute(new StubContribution("edit", "undo", 2));

        // Then the latest contribution wins and a visibility update was dispatched
        assertEquals(1, registry.registrationCount());
        assertEquals(2, dispatcher.dispatched.size());

        // When the first registration is closed, the duplicate remains
        first.close();
        assertTrue(registry.isRegistered("undo"));
        assertEquals(1, registry.registrationCount());
        assertEquals(3, dispatcher.dispatched.size());

        // When the second registration is closed, the contribution is removed
        second.close();
        assertFalse(registry.isRegistered("undo"));
        assertEquals(0, registry.registrationCount());
        assertEquals(4, dispatcher.dispatched.size());
    }

    private record StubContribution(String menuPath, String actionId, int order) implements MenuRegistry.MenuContribution {
    }

    private static final class CapturingDispatcher implements BiConsumer<PluginTask, Runnable> {
        private final List<Dispatched> dispatched = new ArrayList<>();

        @Override
        public void accept(PluginTask task, Runnable callback) {
            dispatched.add(new Dispatched(task, callback));
            callback.run();
        }

        record Dispatched(PluginTask task, Runnable callback) {
        }
    }
}
