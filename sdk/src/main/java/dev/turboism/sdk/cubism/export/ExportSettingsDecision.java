package dev.turboism.sdk.cubism.export;

import dev.turboism.sdk.CubismEditor;

import java.util.Objects;

/**
 * Immutable decision for one embedded-model Export Settings option.
 *
 * <p>The decision space is exactly {@link Outcome#PROCEED_UNCHANGED} and
 * {@link Outcome#REJECT}. A rejection carries one diagnostic/localization identity
 * (a message key); it never carries host data.</p>
 *
 * <p>An option is default-off by construction: this type exposes no default value
 * and no selection state. The host export flow reports selection at decision time.</p>
 */
@CubismEditor({"5.3.02"})
public record ExportSettingsDecision(Outcome outcome, String messageKey) {

    /** The two possible decisions for an embedded-model Export Settings option. */
    @CubismEditor({"5.3.02"})
    public enum Outcome {
        /** Leave the native export unchanged; no message key is carried. */
        PROCEED_UNCHANGED,
        /** Reject the export; carries one diagnostic/localization identity. */
        REJECT
    }

    /** Creates the unchanged decision. The result carries an empty message key. */
    public static ExportSettingsDecision proceedUnchanged() {
        return new ExportSettingsDecision(Outcome.PROCEED_UNCHANGED, "");
    }

    /**
     * Creates the rejection decision with one diagnostic/localization identity.
     *
     * @param messageKey non-blank localization or diagnostic message key
     */
    public static ExportSettingsDecision reject(final String messageKey) {
        return new ExportSettingsDecision(Outcome.REJECT, requireText(messageKey, "messageKey"));
    }

    public ExportSettingsDecision {
        outcome = Objects.requireNonNull(outcome, "outcome");
        messageKey = Objects.requireNonNull(messageKey, "messageKey");
        if (outcome == Outcome.PROCEED_UNCHANGED && !messageKey.isEmpty()) {
            throw new IllegalArgumentException(
                "PROCEED_UNCHANGED decision must not carry a message key"
            );
        }
        if (outcome == Outcome.REJECT && messageKey.isBlank()) {
            throw new IllegalArgumentException("REJECT decision requires a message key");
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
