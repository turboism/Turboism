package dev.turboism.pluginmanagement;

import java.util.Set;

/**
 * Runtime-owned official plugin category registry for first-party admission and
 * installed-plugin presentation.
 *
 * <p>The registry grants no permission or capability: well-formed local
 * categories unknown to the registry remain loadable and readable through
 * {@code PluginDescriptor.category()}. Presentation maps absent or
 * unregistered categories to {@link #FALLBACK}.</p>
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

    /** Presentation category: registered IDs pass through; absent/unregistered fall back to {@code other}. */
    public static String presentation(final String category) {
        return isRegistered(category) ? category : FALLBACK;
    }
}
