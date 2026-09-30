package dev.turboism.sdk.action;

import java.util.Objects;
import java.util.Optional;

/**
 * One registered plugin action as exposed by {@link ActionCatalogService}.
 *
 * @param pluginId identity of the plugin that owns the action's registry
 * @param actionId framework-unique action id within the owning plugin's registry
 * @param label user-facing action label as supplied by the owning plugin; localization is
 *     the owning plugin's responsibility
 * @param defaultShortcut the action's declared default shortcut, or empty when unbound
 */
public record ActionDescriptor(String pluginId, String actionId, String label, Optional<String> defaultShortcut) {

    public ActionDescriptor {
        pluginId = requireText(pluginId, "pluginId");
        actionId = requireText(actionId, "actionId");
        label = requireText(label, "label");
        defaultShortcut = Objects.requireNonNull(defaultShortcut, "defaultShortcut");
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
