package dev.turboism.sdk.cubism.export;

import dev.turboism.sdk.CubismEditor;

import java.util.Objects;

/**
 * Immutable contribution of one default-off boolean option to the native
 * embedded-model Export Settings flow.
 *
 * <p>The option is default-off by construction: this record exposes no default
 * value and no selection state. The host export flow reports selection at decision
 * time through the {@link ExportSettingsDecisionCallback}.</p>
 */
@CubismEditor({"5.3.02"})
public record ExportSettingsContribution(
    String optionId,
    String labelKey,
    ExportSettingsDecisionCallback callback
) {
    private static final int MAX_KEY_LENGTH = 128;

    public ExportSettingsContribution {
        optionId = requireKey(optionId, "optionId");
        labelKey = requireKey(labelKey, "labelKey");
        callback = Objects.requireNonNull(callback, "callback");
    }

    private static String requireKey(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                name + " must contain 1-" + MAX_KEY_LENGTH + " characters"
            );
        }
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a lowercase config key");
        }
        return value;
    }
}
