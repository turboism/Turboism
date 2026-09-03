package dev.turboism.sdk.cubism.transaction;

import java.util.Objects;

/**
 * Synchronous callback boundary for one Editor-owned authoring transaction.
 *
 * <p>Runtime owns the native edit lifetime, host-thread confinement, document and model generation
 * checks, grouped Undo, commit verification, and recovery. Callers receive only immutable evidence;
 * no transaction handle escapes the callback.</p>
 */
public interface AuthoringTransactionService {

    /**
     * Executes work synchronously in one authoring transaction scope.
     *
     * @param options immutable transaction options
     * @param work synchronous callback using the natural Cubism object API
     * @param <T> callback value type
     * @return typed outcome and immutable transaction evidence
     */
    <T> AuthoringTransactionResult<T> execute(
        AuthoringTransactionOptions options,
        AuthoringTransactionWork<T> work
    );

    /**
     * Returns the fail-closed implementation used when no verified Runtime coordinator is installed.
     *
     * @return unavailable service that never invokes the callback
     */
    static AuthoringTransactionService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed singleton used by default facade implementations. */
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
