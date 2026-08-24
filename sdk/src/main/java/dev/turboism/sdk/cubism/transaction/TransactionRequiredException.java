package dev.turboism.sdk.cubism.transaction;


/**
 * Signals that an operation which may only run inside a transaction was attempted without
 * one. Carries error code 1105 at {@code ERROR} severity.
 */
public class TransactionRequiredException extends TransactionException {

    public TransactionRequiredException(String transactionId, String message) {
        super(transactionId, 1105, "ERROR", message);
    }
}
