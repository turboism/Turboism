package dev.turboism.sdk.cubism.transaction;

public class TransactionAlreadyActiveException extends TransactionException {

    public TransactionAlreadyActiveException(String transactionId, String message) {
        super(transactionId, 1001, "ERROR", message);
    }
}
