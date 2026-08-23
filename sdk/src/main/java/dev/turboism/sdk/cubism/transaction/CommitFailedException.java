package dev.turboism.sdk.cubism.transaction;


/**
 * Signals that a transaction's staged writes could not be applied to the Cubism host,
 * leaving the model in whatever state the failed commit produced. Carries error code 1201
 * at {@code ERROR} severity.
 */
public class CommitFailedException extends TransactionException {

    public CommitFailedException(String transactionId, String message) {
        super(transactionId, 1201, "ERROR", message);
    }

    public CommitFailedException(String transactionId, String message, Throwable cause) {
        super(transactionId, 1201, "ERROR", message, cause);
    }
}
