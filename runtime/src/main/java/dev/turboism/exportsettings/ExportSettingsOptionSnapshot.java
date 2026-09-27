package dev.turboism.exportsettings;

import java.util.Objects;

/**
 * Immutable, plugin-localized option snapshot carried across the native dialog boundary.
 *
 * <p>The label is resolved by the owning plugin before the snapshot is created, so the
 * native bridge never performs localization and never exposes a host or Swing type.
 * Ownership is preserved as the plugin id plus the plugin generation captured when the
 * snapshot was taken.</p>
 */
public record ExportSettingsOptionSnapshot(
    String optionId,
    String label,
    String pluginId,
    long pluginGeneration
) {

    public ExportSettingsOptionSnapshot {
        optionId = requireText(optionId, "optionId");
        label = Objects.requireNonNull(label, "label");
        pluginId = requireText(pluginId, "pluginId");
        if (pluginGeneration < 0L) {
            throw new IllegalArgumentException("pluginGeneration must not be negative");
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
