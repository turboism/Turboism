package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.PreviewApi;

@PreviewApi
public class RollbackFailedException extends TransactionException {

    public RollbackFailedException(String transactionId, String message) {
        super(transactionId, 1202, "ERROR", message);
    }

    public RollbackFailedException(String transactionId, String message, Throwable cause) {
        super(transactionId, 1202, "ERROR", message, cause);
    }
}
