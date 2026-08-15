package dev.turboism.pluginmanagement;

import dev.turboism.i18n.LocalizationDiagnostic;
import dev.turboism.i18n.LocalizationDiagnosticSink;

import java.util.Optional;
import java.util.Set;

/**
 * Runtime-owned official plugin category registry for first-party admission and
 * installed-plugin presentation.
 *
 * <p>The registry grants no permission or capability: well-formed local
 * categories unknown to the registry remain loadable and readable unchanged
 * through {@code PluginDescriptor.category()}. Presentation maps absent or
 * unregistered categories to {@link #FALLBACK} and emits a structured
 * {@code PLUGIN_CATEGORY_UNKNOWN} diagnostic when a well-formed category is
 * declared but not registered.</p>
 */
public final class PluginCategoryRegistry {

    /** Presentation fallback for unclassified and unregistered categories; not an official category. */
    public static final String FALLBACK = "other";

    private static final Set<String> REGISTERED = Set.of(
        "modeling",
        "workflow",
        "appearance",
        "analysis",
        "performance",
        "integration",
        "system",
        "development"
    );

    private PluginCategoryRegistry() {
    }

    /** Immutable set of the reviewed official category IDs. */
    public static Set<String> registered() {
        return REGISTERED;
    }

    public static boolean isRegistered(final String category) {
        return category != null && REGISTERED.contains(category);
    }

    /**
     * Single presentation-normalization path: registered categories pass
     * through unchanged; absent categories fall back to {@code other} without
     * a diagnostic; well-formed unregistered categories fall back to
     * {@code other} and emit a structured {@code PLUGIN_CATEGORY_UNKNOWN}
     * diagnostic carrying the plugin id and the declared category.
     */
    public static String presentation(
        final String pluginId,
        final Optional<String> declaredCategory,
        final LocalizationDiagnosticSink diagnostics
    ) {
        final String category = declaredCategory.orElse(null);
        if (isRegistered(category)) {
            return category;
        }
        if (category != null && diagnostics != null) {
            diagnostics.record(new LocalizationDiagnostic(
                "PLUGIN_CATEGORY_UNKNOWN",
                pluginId,
                "category",
                "",
                "plugin " + pluginId + " declares well-formed unknown category " + category
            ));
        }
        return FALLBACK;
    }

    /** Presentation-only overload for values without a diagnostic sink. */
    public static String presentation(final String category) {
        return presentation(null, Optional.ofNullable(category), null);
    }
}
