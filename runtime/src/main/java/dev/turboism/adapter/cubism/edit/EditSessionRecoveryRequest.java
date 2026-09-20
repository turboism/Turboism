package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.sdk.cubism.history.HistorySnapshot;

import java.util.Objects;

/**
 * Everything a {@link EditSessionRecovery} needs to restore the pre-session state.
 *
 * @param binding the session's plugin/document/model/thread binding
 * @param editToken the native session undo token returned by {@link
 *     EditorEditSessionHost#beginEdit}
 * @param historyBefore the native Undo history captured before {@code beginEdit}
 */
public record EditSessionRecoveryRequest(
    EditorAuthoringTransactionCoordinator.Binding binding,
    Object editToken,
    HistorySnapshot historyBefore
) {

    public EditSessionRecoveryRequest {
        binding = Objects.requireNonNull(binding, "binding");
        editToken = Objects.requireNonNull(editToken, "editToken");
        historyBefore = Objects.requireNonNull(historyBefore, "historyBefore");
    }
}
