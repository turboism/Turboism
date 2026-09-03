package dev.turboism.adapter.cubism.editor.transaction;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime-owned ambient scope for synchronous Editor authoring transactions.
 *
 * <p>The coordinator is deliberately independent of reflective Cubism members. A verified host
 * adapter supplies native edit, refresh, history, and diagnostic operations through {@link Host};
 * individual writer providers contribute Undo admission, mutation, readback, and compensation.
 * The ambient scope is instance-owned and thread-confined.</p>
 */
public final class EditorAuthoringTransactionCoordinator {

    private final Host host;
    private final ThreadLocal<EditorAuthoringScope> ambient = new ThreadLocal<>();
    private final AtomicLong transactionSequence = new AtomicLong();

    /**
     * Creates a coordinator over one verified host adapter.
     *
     * @param host native edit, history, refresh, and diagnostic boundary
     */
    public EditorAuthoringTransactionCoordinator(final Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Executes one root authoring callback synchronously.
     *
     * @param binding plugin, document, model, generation, and thread binding
     * @param options immutable authoring options
     * @param work synchronous callback
     * @param <T> callback value type
     * @return typed transaction result
     */
    public <T> AuthoringTransactionResult<T> execute(
        final Binding binding,
        final AuthoringTransactionOptions options,
        final AuthoringTransactionWork<T> work
    ) {
        final Binding checkedBinding = Objects.requireNonNull(binding, "binding");
        final AuthoringTransactionOptions checkedOptions = Objects.requireNonNull(
            options,
            "options"
        );
        final AuthoringTransactionWork<T> checkedWork = Objects.requireNonNull(work, "work");

        if (ambient.get() != null) {
            return AuthoringTransactionResult.rejectedScope(
                Optional.empty(),
                Optional.of(diagnostic("authoring.scope-rejected", null))
            );
        }
        if (!checkedBinding.isCurrentThread() || !current(checkedBinding)) {
            return AuthoringTransactionResult.rejectedScope(
                Optional.empty(),
                Optional.of(diagnostic("authoring.scope-rejected", null))
            );
        }

        final HistorySnapshot before;
        try {
            before = Objects.requireNonNull(host.history(checkedBinding), "history");
        } catch (RuntimeException failure) {
            return AuthoringTransactionResult.unavailable(
                diagnostic("authoring.history-unavailable", failure)
            );
        }
        if (before.availability() != HistorySnapshot.Availability.AVAILABLE) {
            return AuthoringTransactionResult.unavailable(
                diagnostic("authoring.history-unavailable", null)
            );
        }

        final EditorAuthoringScope scope = new EditorAuthoringScope(
            checkedBinding,
            checkedOptions,
            nextTransactionId(),
            before
        );
        ambient.set(scope);
        try {
            final T value;
            try {
                value = checkedWork.run();
            } catch (ScopeRejectedException failure) {
                return recover(scope, failure, true);
            } catch (Exception failure) {
                return recover(scope, failure, false);
            }

            if (!current(checkedBinding)) {
                return recover(
                    scope,
                    new ScopeRejectedException("authoring binding changed before commit"),
                    true
                );
            }
            if (!scope.changed()) {
                return noChange(scope, value);
            }
            return commit(scope, value);
        } finally {
            ambient.remove();
        }
    }

    /**
     * Applies one changed writer contribution. With no ambient root this method opens a standalone
     * transaction using the contribution's label; with a matching ambient root it joins that root.
     *
     * @param binding current provider binding
     * @param contribution changed authoring primitive
     * @throws IllegalStateException when a standalone transaction cannot commit safely
     */
    public void mutate(
        final Binding binding,
        final EditorUndoContribution contribution
    ) {
        final Binding checkedBinding = Objects.requireNonNull(binding, "binding");
        final EditorUndoContribution checkedContribution = Objects.requireNonNull(
            contribution,
            "contribution"
        );
        final EditorAuthoringScope scope = ambient.get();
        if (scope != null) {
            apply(scope, checkedBinding, checkedContribution);
            return;
        }

        final AuthoringTransactionResult<Void> result = execute(
            checkedBinding,
            AuthoringTransactionOptions.of(checkedContribution.standaloneLabel()),
            () -> {
                apply(Objects.requireNonNull(ambient.get(), "ambient scope"),
                    checkedBinding, checkedContribution);
                return null;
            }
        );
        if (!result.successful()) {
            throw new IllegalStateException(
                "Standalone Editor authoring transaction failed: " + result.outcome()
                    + result.diagnosticId().map(id -> " [" + id + "]").orElse("")
            );
        }
    }

    private <T> AuthoringTransactionResult<T> noChange(
        final EditorAuthoringScope scope,
        final T value
    ) {
        final HistorySnapshot after;
        try {
            after = Objects.requireNonNull(host.history(scope.binding()), "history");
        } catch (RuntimeException failure) {
            final AuthoringTransactionReceipt receipt = receipt(
                scope,
                HistorySnapshot.unavailable(),
                Optional.empty()
            );
            return AuthoringTransactionResult.recoveryFailed(
                Optional.of(receipt),
                diagnostic("authoring.no-change-history-unavailable", failure)
            );
        }
        final AuthoringTransactionReceipt receipt = receipt(scope, after, Optional.empty());
        if (!scope.historyBefore().equals(after)) {
            return AuthoringTransactionResult.recoveryFailed(
                Optional.of(receipt),
                diagnostic("authoring.no-change-history-mutated", null)
            );
        }
        return AuthoringTransactionResult.noChange(value, receipt);
    }

    private <T> AuthoringTransactionResult<T> commit(
        final EditorAuthoringScope scope,
        final T value
    ) {
        try {
            if (!current(scope.binding())) {
                throw new ScopeRejectedException("authoring binding changed before refresh");
            }
            final Set<EditorRefreshRequirement> requirements = scope.refreshRequirements();
            if (!requirements.isEmpty()) {
                host.refresh(scope.binding(), requirements);
            }
            if (!current(scope.binding())) {
                throw new ScopeRejectedException("authoring binding changed before native commit");
            }
            scope.markEditEndAttempted();
            host.endEdit(scope.binding(), scope.edit(), false);
            scope.markEditClosed();
        } catch (ScopeRejectedException failure) {
            return recover(scope, failure, true);
        } catch (RuntimeException failure) {
            return recover(scope, failure, false);
        }

        final HistorySnapshot after;
        try {
            after = Objects.requireNonNull(host.history(scope.binding()), "history");
        } catch (RuntimeException failure) {
            return AuthoringTransactionResult.recoveryFailed(
                Optional.of(receipt(scope, HistorySnapshot.unavailable(), Optional.empty())),
                diagnostic("authoring.history-unverified", failure)
            );
        }
        final Optional<String> entryId;
        try {
            entryId = Objects.requireNonNull(
                host.committedHistoryEntryId(
                    scope.binding(),
                    scope.historyBefore(),
                    after,
                    scope.transactionId(),
                    scope.options().label()
                ),
                "committedHistoryEntryId"
            );
        } catch (RuntimeException failure) {
            return AuthoringTransactionResult.recoveryFailed(
                Optional.of(receipt(scope, after, Optional.empty())),
                diagnostic("authoring.history-unverified", failure)
            );
        }
        final AuthoringTransactionReceipt receipt = receipt(scope, after, entryId);
        if (entryId.isEmpty()) {
            return AuthoringTransactionResult.recoveryFailed(
                Optional.of(receipt),
                diagnostic("authoring.history-unverified", null)
            );
        }
        return AuthoringTransactionResult.committed(value, receipt);
    }

    private void apply(
        final EditorAuthoringScope scope,
        final Binding binding,
        final EditorUndoContribution contribution
    ) {
        if (!scope.binding().sameScope(binding) || !binding.isCurrentThread()) {
            throw new ScopeRejectedException(
                "authoring contribution does not match the ambient scope"
            );
        }
        if (!current(binding)) {
            throw new ScopeRejectedException("authoring contribution binding is stale");
        }
        if (scope.edit() == null) {
            scope.edit(Objects.requireNonNull(
                host.beginEdit(binding, scope.options().label()),
                "native edit"
            ));
        }
        contribution.undoAdmission().admit(scope.edit(), scope.options().label());
        scope.add(contribution);
        contribution.mutation().run();
        if (!contribution.applied().getAsBoolean()) {
            throw new IllegalStateException(
                "Authoring postcondition failed: " + contribution.operationId()
            );
        }
    }

    private <T> AuthoringTransactionResult<T> recover(
        final EditorAuthoringScope scope,
        final Exception failure,
        final boolean scopeRejected
    ) {
        RuntimeException recoveryFailure = null;
        if (scope.edit() != null && !scope.editEndAttempted()) {
            try {
                scope.markEditEndAttempted();
                host.endEdit(scope.binding(), scope.edit(), true);
                scope.markEditClosed();
            } catch (RuntimeException abortFailure) {
                recoveryFailure = append(recoveryFailure, abortFailure);
            }
        } else if (scope.edit() != null && !scope.editClosed()) {
            recoveryFailure = append(
                recoveryFailure,
                new IllegalStateException(
                    "native edit end outcome is uncertain; a second close was not attempted"
                )
            );
        }

        final List<EditorUndoContribution> contributions = scope.contributions();
        for (int index = contributions.size() - 1; index >= 0; index--) {
            final EditorUndoContribution contribution = contributions.get(index);
            try {
                if (!contribution.restored().getAsBoolean()) {
                    contribution.compensation().run();
                }
                if (!contribution.restored().getAsBoolean()) {
                    throw new IllegalStateException(
                        "Authoring rollback verification failed: "
                            + contribution.operationId()
                    );
                }
            } catch (RuntimeException compensationFailure) {
                recoveryFailure = append(recoveryFailure, compensationFailure);
            }
        }

        final HistorySnapshot after;
        try {
            after = Objects.requireNonNull(host.history(scope.binding()), "history");
            if (!scope.historyBefore().equals(after)) {
                recoveryFailure = append(
                    recoveryFailure,
                    new IllegalStateException("authoring history was not restored")
                );
            }
        } catch (RuntimeException historyFailure) {
            recoveryFailure = append(recoveryFailure, historyFailure);
            return AuthoringTransactionResult.recoveryFailed(
                Optional.of(receipt(scope, HistorySnapshot.unavailable(), Optional.empty())),
                diagnostic("authoring.recovery-failed", recoveryFailure)
            );
        }

        final AuthoringTransactionReceipt receipt = receipt(scope, after, Optional.empty());
        if (recoveryFailure != null) {
            return AuthoringTransactionResult.recoveryFailed(
                Optional.of(receipt),
                diagnostic("authoring.recovery-failed", recoveryFailure)
            );
        }
        final String diagnostic = diagnostic(
            scopeRejected ? "authoring.scope-rejected" : "authoring.callback-failed",
            failure
        );
        if (scopeRejected && !scope.changed()) {
            return AuthoringTransactionResult.rejectedScope(
                Optional.of(receipt),
                Optional.of(diagnostic)
            );
        }
        return AuthoringTransactionResult.rolledBack(
            receipt,
            Optional.of(diagnostic)
        );
    }

    private AuthoringTransactionReceipt receipt(
        final EditorAuthoringScope scope,
        final HistorySnapshot after,
        final Optional<String> historyEntryId
    ) {
        return new AuthoringTransactionReceipt(
            scope.transactionId(),
            scope.options().label(),
            scope.historyBefore(),
            after,
            historyEntryId
        );
    }

    private boolean current(final Binding binding) {
        try {
            return host.isCurrent(binding);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private String nextTransactionId() {
        return "authoring-" + Long.toUnsignedString(
            transactionSequence.incrementAndGet(),
            36
        );
    }

    private String diagnostic(final String code, final Throwable failure) {
        try {
            final String value = host.diagnosticId(code, failure);
            if (value != null && !value.isBlank()) return value.strip();
        } catch (RuntimeException ignored) {
            // A diagnostic sink must never hide the primary authoring outcome.
        }
        return code;
    }

    private static RuntimeException append(
        final RuntimeException current,
        final RuntimeException next
    ) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    private static final class ScopeRejectedException extends RuntimeException {
        ScopeRejectedException(final String message) {
            super(message);
        }
    }

    /**
     * Immutable identity required for root admission and ambient participation.
     *
     * @param pluginId owning plugin identity
     * @param documentIdentity opaque active document identity
     * @param documentGeneration active document generation
     * @param modelIdentity opaque active model identity
     * @param modelGeneration active model generation
     * @param hostThread exact host thread on which the callback must remain
     */
    public record Binding(
        String pluginId,
        String documentIdentity,
        long documentGeneration,
        String modelIdentity,
        long modelGeneration,
        Thread hostThread
    ) {

        /** Validates one generation-bound authoring identity. */
        public Binding {
            pluginId = requireText(pluginId, "pluginId");
            documentIdentity = requireText(documentIdentity, "documentIdentity");
            if (documentGeneration <= 0) {
                throw new IllegalArgumentException("documentGeneration must be positive");
            }
            modelIdentity = requireText(modelIdentity, "modelIdentity");
            if (modelGeneration <= 0) {
                throw new IllegalArgumentException("modelGeneration must be positive");
            }
            hostThread = Objects.requireNonNull(hostThread, "hostThread");
        }

        boolean sameScope(final Binding other) {
            final Binding checked = Objects.requireNonNull(other, "other");
            return documentGeneration == checked.documentGeneration
                && modelGeneration == checked.modelGeneration
                && documentIdentity.equals(checked.documentIdentity)
                && modelIdentity.equals(checked.modelIdentity)
                && hostThread == checked.hostThread;
        }

        boolean isCurrentThread() {
            return Thread.currentThread() == hostThread;
        }

        private static String requireText(final String value, final String name) {
            final String checked = Objects.requireNonNull(value, name).strip();
            if (checked.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return checked;
        }
    }

    /** Verified host operations used by the transaction coordinator. */
    public interface Host {

        /**
         * Returns whether the supplied document/model/generation/thread binding is still active.
         * The plugin id is a root authorization identity and is not part of native host identity.
         */
        boolean isCurrent(Binding binding);

        /** Captures the active native Undo history for the binding. */
        HistorySnapshot history(Binding binding);

        /** Lazily opens the root native edit object. */
        Object beginEdit(Binding binding, String label);

        /** Closes the native edit; {@code abort=true} requests the verified abort path. */
        void endEdit(Binding binding, Object edit, boolean abort);

        /** Performs coalesced model update, refresh, repaint, and dirty-state work. */
        void refresh(Binding binding, Set<EditorRefreshRequirement> requirements);

        /**
         * Returns the opaque identity of exactly one history entry attributable to this commit.
         * Empty means attribution could not be proven.
         */
        Optional<String> committedHistoryEntryId(
            Binding binding,
            HistorySnapshot before,
            HistorySnapshot after,
            String transactionId,
            String label
        );

        /** Records or derives an opaque diagnostic identity for a terminal outcome. */
        String diagnosticId(String code, Throwable failure);
    }
}
