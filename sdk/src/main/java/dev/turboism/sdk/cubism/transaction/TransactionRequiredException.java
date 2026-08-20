package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.PreviewApi;

/**
 * Signals that an operation which may only run inside a transaction was attempted without
 * one. Carries error code 1105 at {@code ERROR} severity.
 */
@PreviewApi
public class TransactionRequiredException extends TransactionException {

    public TransactionRequiredException(String transactionId, String message) {
        super(transactionId, 1105, "ERROR", message);
    }
}
