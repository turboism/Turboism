package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.PreviewApi;

@PreviewApi
public class CommitFailedException extends TransactionException {

    public CommitFailedException(String transactionId, String message) {
        super(transactionId, 1201, "ERROR", message);
    }

    public CommitFailedException(String transactionId, String message, Throwable cause) {
        super(transactionId, 1201, "ERROR", message, cause);
    }
}
