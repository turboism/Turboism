package dev.turboism.exportsettings;

import java.util.Objects;

/**
 * One user-facing diagnostic for a vetoed export-settings confirmation.
 *
 * <p>{@code key} is a bounded machine identity: a runtime {@code export-settings.*} or
 * {@code protected-export.*} key, or the plugin's own rejection message key.
 * {@code detail} carries bounded context for display — a resolved plugin message, a
 * refusal gate name, or a session failure detail — and may be {@code null}.</p>
 */
public record ExportSettingsVetoDiagnostic(String key, String detail) {

    public ExportSettingsVetoDiagnostic {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }

    public ExportSettingsVetoDiagnostic(final String key) {
        this(key, null);
    }
}
