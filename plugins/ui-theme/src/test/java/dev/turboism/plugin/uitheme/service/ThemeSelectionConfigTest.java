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

    /**
     * The runtime's early theme reader (ARCHITECTURE.md section 5, closed exception) parses this
     * document before the plugin loads and hard-codes these values. Changing any of them requires
     * changing that reader and its pinned-contract test in the same commit.
     */
    @Test
    void persistedSelectionContractMatchesTheRuntimeEarlyThemeReader() {
        InMemoryConfigRegistry config = new InMemoryConfigRegistry();

        new ThemeSelectionConfig(config).initialize().toCompletableFuture().join();

        assertEquals("ui-theme/selection.cfg", config.schema.relativePath());
        assertEquals("selected-theme", config.schema.keys().get(0).name());
        assertEquals(1, config.schema.version());
        assertEquals("__native__", ThemeSelectionConfig.NATIVE_ID);
    }
}
