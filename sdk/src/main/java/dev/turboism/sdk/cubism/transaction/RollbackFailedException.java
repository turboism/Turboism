package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.PreviewApi;

/**
 * Signals that a transaction's rollback could not undo its staged writes, so the model may
 * retain partial changes. Carries error code 1202 at {@code ERROR} severity.
 */
@PreviewApi
public class RollbackFailedException extends TransactionException {

    public RollbackFailedException(String transactionId, String message) {
        super(transactionId, 1202, "ERROR", message);
    }

    public RollbackFailedException(String transactionId, String message, Throwable cause) {
        super(transactionId, 1202, "ERROR", message, cause);
    }
}
