package dev.turboism.sdk.cubism.transaction;

import java.util.Objects;

/**
 * Executes one synchronous callback inside a Runtime-owned Editor authoring transaction.
 *
 * <p>The implementation owns host-thread admission, active document/model binding, native Undo
 * grouping, abort and recovery, and transaction diagnostics. The callback receives no transaction
 * handle and may interact only through normal SDK services available to the plugin.</p>
 */
public interface AuthoringTransactionService {

    /**
     * Executes authoring work synchronously.
     *
     * @param options validated transaction options
     * @param work synchronous callback; never invoked when the service is unavailable or preflight
     *     rejects the request
     * @param <T> callback result type
     * @return typed terminal result and immutable history evidence
     */
    <T> AuthoringTransactionResult<T> execute(
        AuthoringTransactionOptions options,
        AuthoringTransactionWork<T> work
    );

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
            final AuthoringTransactionOptions options,
            final AuthoringTransactionWork<T> work
        ) {
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(work, "work");
            return AuthoringTransactionResult.unavailable(
                "cubism.authoring.transactions.unavailable"
            );
        }
    }
}
