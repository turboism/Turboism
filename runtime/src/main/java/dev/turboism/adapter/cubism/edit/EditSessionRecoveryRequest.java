package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.sdk.cubism.history.HistorySnapshot;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Everything a {@link EditSessionRecovery} needs to restore the pre-session state.
 *
 * @param binding the session's plugin/document/model/thread binding
 * @param editToken the native session undo token returned by {@link
 *     EditorEditSessionHost#beginEdit}
 * @param historyBefore the native Undo history captured before {@code beginEdit}
 * @param diagnostics sink for recovery diagnostics (the session's status-dialog log
 *     channel); must not be {@code null}
 */
public record EditSessionRecoveryRequest(
    EditorAuthoringTransactionCoordinator.Binding binding,
    Object editToken,
    HistorySnapshot historyBefore,
    Consumer<String> diagnostics
) {

    public EditSessionRecoveryRequest {
        binding = Objects.requireNonNull(binding, "binding");
        editToken = Objects.requireNonNull(editToken, "editToken");
        historyBefore = Objects.requireNonNull(historyBefore, "historyBefore");
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Records one diagnostic line; sink failures are swallowed — diagnostics are advisory. */
    void diagnose(final String message) {
        try {
            diagnostics.accept(message);
        } catch (RuntimeException ignored) {
            // diagnostics must never mask the recovery outcome
        }
    }
}
