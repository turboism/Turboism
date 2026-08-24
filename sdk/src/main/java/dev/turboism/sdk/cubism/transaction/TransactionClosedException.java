package dev.turboism.sdk.cubism.transaction;


/**
 * Signals use of a transaction that has already been committed or rolled back. The closed
 * transaction stays closed; the caller must begin a new one. Carries error code 1101 at
 * {@code ERROR} severity.
 */
public class TransactionClosedException extends TransactionException {

    public TransactionClosedException(String transactionId, String message) {
        super(transactionId, 1101, "ERROR", message);
    }
}
