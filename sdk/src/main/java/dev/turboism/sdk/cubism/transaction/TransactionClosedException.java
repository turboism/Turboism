package dev.turboism.sdk.cubism.transaction;

public class TransactionClosedException extends TransactionException {

    public TransactionClosedException(String transactionId, String message) {
        super(transactionId, 1101, "ERROR", message);
    }
}
