package dev.turboism.sdk.cubism.transaction;

public class PermissionDeniedException extends TransactionException {

    public PermissionDeniedException(String transactionId, String message) {
        super(transactionId, 1003, "ERROR", message);
    }
}
