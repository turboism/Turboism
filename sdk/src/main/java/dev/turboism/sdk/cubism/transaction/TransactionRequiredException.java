package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.PreviewApi;

@PreviewApi
public class TransactionRequiredException extends TransactionException {

    public TransactionRequiredException(String transactionId, String message) {
        super(transactionId, 1105, "ERROR", message);
    }
}
