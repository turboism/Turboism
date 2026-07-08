package dev.turboism.sdk.cubism.transaction;

public class TransactionRequiredException extends TransactionException {

    public TransactionRequiredException(String transactionId, String message) {
        super(transactionId, 1105, "ERROR", message);
    }
}
