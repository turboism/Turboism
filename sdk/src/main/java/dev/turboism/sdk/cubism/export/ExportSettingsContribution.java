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

    public ExportSettingsContribution {
        optionId = requireText(optionId, "optionId");
        labelKey = requireText(labelKey, "labelKey");
        callback = Objects.requireNonNull(callback, "callback");
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
