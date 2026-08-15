package dev.turboism.pluginmanagement;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void unknownWellFormedCategoryEmitsStructuredDiagnosticWithPluginIdAndCategory() {
        final List<dev.turboism.i18n.LocalizationDiagnostic> recorded = new java.util.ArrayList<>();
        final dev.turboism.i18n.LocalizationDiagnosticSink sink = recorded::add;

        final String presentation = PluginCategoryRegistry.presentation(
            "dev.turboism.plugin.local", java.util.Optional.of("custom-tooling"), sink
        );

        assertEquals("other", presentation);
        assertEquals(1, recorded.size());
        final dev.turboism.i18n.LocalizationDiagnostic diagnostic = recorded.get(0);
        assertEquals("PLUGIN_CATEGORY_UNKNOWN", diagnostic.code());
        assertEquals("dev.turboism.plugin.local", diagnostic.pluginId());
        assertTrue(diagnostic.message().contains("custom-tooling"), diagnostic.message());
    }

    @Test
    void registeredAndAbsentCategoriesEmitNoDiagnostic() {
        final List<dev.turboism.i18n.LocalizationDiagnostic> recorded = new java.util.ArrayList<>();
        final dev.turboism.i18n.LocalizationDiagnosticSink sink = recorded::add;

        PluginCategoryRegistry.presentation("dev.turboism.plugin.a", java.util.Optional.of("modeling"), sink);
        PluginCategoryRegistry.presentation("dev.turboism.plugin.b", java.util.Optional.empty(), sink);

        assertEquals(java.util.List.of(), recorded);
    }
}
