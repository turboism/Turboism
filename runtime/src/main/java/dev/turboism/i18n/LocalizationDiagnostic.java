package dev.turboism.i18n;

import java.util.Objects;

/** Sanitized runtime-owned localization diagnostic. */
public record LocalizationDiagnostic(
    String code,
    String pluginId,
    String key,
    String locale,
    String message
) {
    public LocalizationDiagnostic {
        code = requireText(code, "code");
        pluginId = requireText(pluginId, "pluginId");
        key = key == null ? "" : key;
        locale = locale == null ? "" : locale;
        message = requireText(message, "message");
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
