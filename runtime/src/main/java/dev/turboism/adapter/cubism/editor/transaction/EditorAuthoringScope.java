package dev.turboism.adapter.cubism.editor.transaction;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Mutable state owned by one coordinator while a root callback is running. */
final class EditorAuthoringScope {

    private final EditorAuthoringTransactionCoordinator.Binding binding;
    private final AuthoringTransactionOptions options;
    private final String transactionId;
    private final HistorySnapshot historyBefore;
    private final List<EditorUndoContribution> contributions = new ArrayList<>();
    private final EnumSet<EditorRefreshRequirement> refreshRequirements =
        EnumSet.noneOf(EditorRefreshRequirement.class);
    private Object edit;
    private boolean editEndAttempted;
    private boolean editClosed;

    EditorAuthoringScope(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final AuthoringTransactionOptions options,
        final String transactionId,
        final HistorySnapshot historyBefore
    ) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.options = Objects.requireNonNull(options, "options");
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.historyBefore = Objects.requireNonNull(historyBefore, "historyBefore");
    }

    EditorAuthoringTransactionCoordinator.Binding binding() {
        return binding;
    }

    AuthoringTransactionOptions options() {
        return options;
    }

    String transactionId() {
        return transactionId;
    }

    HistorySnapshot historyBefore() {
        return historyBefore;
    }

    Object edit() {
        return edit;
    }

    void edit(final Object value) {
        if (edit != null) throw new IllegalStateException("native edit is already open");
        edit = Objects.requireNonNull(value, "edit");
    }

    boolean changed() {
        return !contributions.isEmpty();
    }

    void add(final EditorUndoContribution contribution) {
        contributions.add(Objects.requireNonNull(contribution, "contribution"));
        refreshRequirements.addAll(contribution.refreshRequirements());
    }

    List<EditorUndoContribution> contributions() {
        return List.copyOf(contributions);
    }

    Set<EditorRefreshRequirement> refreshRequirements() {
        return refreshRequirements.isEmpty()
            ? Set.of()
            : Set.copyOf(EnumSet.copyOf(refreshRequirements));
    }

    boolean editEndAttempted() {
        return editEndAttempted;
    }

    void markEditEndAttempted() {
        if (edit == null) throw new IllegalStateException("no native edit is open");
        if (editEndAttempted) {
            throw new IllegalStateException("native edit end was already attempted");
        }
        editEndAttempted = true;
    }

    boolean editClosed() {
        return editClosed;
    }

    void markEditClosed() {
        if (!editEndAttempted) {
            throw new IllegalStateException("native edit end was not attempted");
        }
        if (editClosed) throw new IllegalStateException("native edit is already closed");
        editClosed = true;
    }
}
