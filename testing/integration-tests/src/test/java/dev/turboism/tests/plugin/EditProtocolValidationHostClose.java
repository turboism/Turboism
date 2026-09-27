package dev.turboism.tests.plugin;

/** Test-only bridge so the edit-protocol probe reuses the bounded, task-owned native UI close path. */
public final class EditProtocolValidationHostClose {
    private EditProtocolValidationHostClose() {
    }

    /**
     * Requests normal close after the caller has persisted a terminal result for this run.
     * The shared helper validates the fixture, exact host version, focused window and any dialog.
     * No process-exit fallback or arbitrary-window selection is provided.
     *
     * @param automate whether the task explicitly enabled automatic close
     * @param running whether the calling probe still owns an active lifecycle
     * @param terminalWritten whether this run's terminal evidence has been published
     * @param runId the runner-provided task identity
     * @param hostVersion the exact reviewed host-version selector
     * @return a diagnostic close status; this is not a supervisor lifecycle verdict
     * @throws Exception if the normal close cannot be proven safe
     */
    public static String request(
        final boolean automate,
        final boolean running,
        final boolean terminalWritten,
        final String runId,
        final String hostVersion
    ) throws Exception {
        final var result = WindowsHistoryNativeUiHostClose.closeIfEligible(
            automate, running, terminalWritten, runId, hostVersion
        );
        return result.status().name() + ":" + result.reason();
    }
}
