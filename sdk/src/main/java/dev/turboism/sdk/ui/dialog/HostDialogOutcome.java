package dev.turboism.sdk.ui.dialog;

/**
 * Result of {@link HostDialogAutomationService#act}.
 *
 * <p>All flow failures are reported through this enum; {@code act} throws only
 * for illegal arguments. Ambiguity and unrecognizable dialogs always fail
 * closed ({@link #AMBIGUOUS} / {@link #UNSUPPORTED}) without clicking.</p>
 */
public enum HostDialogOutcome {
    /** Dialog found, unique button matched, action triggered, dialog confirmed closed. */
    ACTED,
    /** No matching dialog appeared within the timeout (caller may treat as "no dialog, continue"). */
    NOT_FOUND,
    /** Dialog appeared but did not close within the deadline after the action. */
    TIMEOUT,
    /** Multiple buttons matched the same semantic action → nothing was triggered. */
    AMBIGUOUS,
    /** No recognizable/operable button or not a target dialog type. */
    UNSUPPORTED
}
