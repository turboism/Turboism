package dev.turboism.ui.toolbar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorUiPluginResourceRegistryTest {

    @Test
    void resolvesOwnedResourcesUntilRegistrationCloses() {
        EditorUiPluginResourceRegistry registry = new EditorUiPluginResourceRegistry();
        var registration = registry.register(
            "plugin-a",
            EditorUiPluginResourceRegistryTest.class.getClassLoader()
        );

        assertTrue(registry.resource(
            "plugin-a",
            "dev/turboism/ui/toolbar/EditorUiPluginResourceRegistryTest.class"
        ).isPresent());

        registration.close();
        assertTrue(registry.resource("plugin-a", "missing").isEmpty());
    }

    @Test
    void rejectsTraversalAndConflictingLoaders() {
        EditorUiPluginResourceRegistry registry = new EditorUiPluginResourceRegistry();
        registry.register("plugin-a", getClass().getClassLoader());

        assertThrows(
            IllegalArgumentException.class,
            () -> registry.resource("plugin-a", "../secret")
        );
        assertThrows(
            IllegalStateException.class,
            () -> registry.register("plugin-a", new ClassLoader() { })
        );
    }
}
