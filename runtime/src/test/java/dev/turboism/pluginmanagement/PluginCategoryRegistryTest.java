package dev.turboism.pluginmanagement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime-owned category registry: admission values and presentation fallback. */
class PluginCategoryRegistryTest {

    @Test
    void registryContainsTheEightReviewedCategories() {
        assertEquals(8, PluginCategoryRegistry.registered().size());
        assertTrue(PluginCategoryRegistry.registered().containsAll(java.util.Set.of(
            "modeling", "workflow", "appearance", "analysis",
            "performance", "integration", "system", "development"
        )));
    }

    @Test
    void registryIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
            () -> PluginCategoryRegistry.registered().add("new-category"));
    }

    @Test
    void registeredCategoriesPassThroughPresentation() {
        assertEquals("modeling", PluginCategoryRegistry.presentation("modeling"));
        assertEquals("system", PluginCategoryRegistry.presentation("system"));
    }

    @Test
    void absentUnknownAndBlankCategoriesFallBackToOther() {
        assertEquals("other", PluginCategoryRegistry.presentation(null));
        assertEquals("other", PluginCategoryRegistry.presentation(""));
        assertEquals("other", PluginCategoryRegistry.presentation("custom-tooling"));
        assertEquals("other", PluginCategoryRegistry.presentation("other"));
    }

    @Test
    void fallbackIsNotAnOfficialCategory() {
        assertFalse(PluginCategoryRegistry.isRegistered(PluginCategoryRegistry.FALLBACK));
    }
}
