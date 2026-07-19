package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.PreviewApi;

@PreviewApi
public class TransactionClosedException extends TransactionException {

    public TransactionClosedException(String transactionId, String message) {
        super(transactionId, 1101, "ERROR", message);
    }
}
