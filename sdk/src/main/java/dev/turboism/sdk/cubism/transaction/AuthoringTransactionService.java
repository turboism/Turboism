package dev.turboism.sdk.cubism.transaction;

import java.util.Objects;

/**
 * Executes one synchronous callback inside a Runtime-owned Editor authoring transaction.
 *
 * <p>The implementation owns host-thread admission, active document/model binding, native Undo
 * grouping, abort and recovery, and transaction diagnostics. The callback receives no transaction
 * handle and may interact only through normal SDK services available to the plugin.</p>
 *
 * <p>This is the one recommended transaction entry for plugin writes. Use it when several
 * writes must commit or roll back together or share a single Undo entry; a lone {@code
 * Parameter.setValue} call needs no transaction wrapper.
 *
 * <p>The callback runs on the host's UI thread (the Cubism Editor Swing event dispatch thread).
 * It must be brief and must not block or wait for other UI-thread tasks — doing so deadlocks the
 * host. SDK writes inside the callback that produce an admissible native Undo object — both
 * coordinator-backed writes such as {@code Parameter.setValue} and the migrated hand-written
 * Undo envelopes — join the ambient transaction's single Undo group and commit or roll back
 * with it. SDK writes whose native Undo cannot be admitted to the ambient edit (animation
 * timeline writes, parameter-definition updates, writes that explicitly bypass history) reject
 * with a typed failure instead of creating detached Undo state.</p>
 */
public interface AuthoringTransactionService {

    /**
     * Executes authoring work synchronously on the host UI thread.
     *
     * @param options validated transaction options
     * @param work synchronous callback run on the host UI thread; never invoked when the service
     *     is unavailable or preflight rejects the request, and must not block or wait for other
     *     UI-thread tasks
     * @param <T> callback result type
     * @return typed terminal result and immutable history evidence
     */
    <T> AuthoringTransactionResult<T> execute(AuthoringTransactionOptions options, AuthoringTransactionWork<T> work);

    /**
     * Returns the fail-closed implementation used when Runtime has no verified transaction backend.
     *
     * @return shared unavailable service
     */
    static AuthoringTransactionService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed implementation that never invokes authoring work. */
    enum Unavailable implements AuthoringTransactionService {
        INSTANCE;

        @Override
        public <T> AuthoringTransactionResult<T> execute(
                final AuthoringTransactionOptions options, final AuthoringTransactionWork<T> work) {
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(work, "work");
            return AuthoringTransactionResult.unavailable("cubism.authoring.transactions.unavailable");
        }
    }
}
