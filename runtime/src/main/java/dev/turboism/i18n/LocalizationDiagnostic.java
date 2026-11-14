package dev.turboism.i18n;

import dev.turboism.internal.Requirements;

/** Sanitized runtime-owned localization diagnostic. */
public record LocalizationDiagnostic(String code, String pluginId, String key, String locale, String message) {
    public LocalizationDiagnostic {
        code = Requirements.text(code, "code");
        pluginId = Requirements.text(pluginId, "pluginId");
        key = key == null ? "" : key;
        locale = locale == null ? "" : locale;
        message = Requirements.text(message, "message");
    }
}
