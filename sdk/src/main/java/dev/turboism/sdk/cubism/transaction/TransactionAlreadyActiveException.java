package dev.turboism.sdk.cubism.transaction;


/**
 * Signals an attempt to begin a transaction while one is already active on the same scope;
 * transactions do not nest. Carries error code 1001 at {@code ERROR} severity.
 */
public class TransactionAlreadyActiveException extends TransactionException {

    public TransactionAlreadyActiveException(String transactionId, String message) {
        super(transactionId, 1001, "ERROR", message);
    }
}
