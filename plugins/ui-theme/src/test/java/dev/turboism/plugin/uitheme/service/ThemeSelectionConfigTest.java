package dev.turboism.plugin.uitheme.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.test.config.InMemoryConfigRegistry;
import org.junit.jupiter.api.Test;

final class ThemeSelectionConfigTest {

    @Test
    void selectedThemeRoundTripsThroughTypedPluginConfig() {
        InMemoryConfigRegistry config = new InMemoryConfigRegistry();
        ThemeSelectionConfig selections = new ThemeSelectionConfig(config);

        selections.initialize().toCompletableFuture().join();
        selections.saveSelectedThemeId("user.aurora");

        assertEquals("user.aurora", selections.selectedThemeId().orElseThrow());
        selections.clearSelectedThemeId();
        assertTrue(selections.selectedThemeId().isEmpty());
    }

    @Test
    void productionSchemaUsesRuntimeValidIdentifiers() {
        InMemoryConfigRegistry config = new InMemoryConfigRegistry();
        ThemeSelectionConfig selections = new ThemeSelectionConfig(config);

        selections.initialize().toCompletableFuture().join();

        assertEquals("ui-theme.selection", config.schema.configId());
        assertEquals("ui-theme/selection.cfg", config.schema.relativePath());
        assertEquals("selected-theme", config.schema.keys().get(0).name());
    }
}
