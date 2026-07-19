package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.PreviewApi;

@PreviewApi
public class WriteValidationException extends TransactionException {

    public WriteValidationException(String transactionId, int errorCode, String message) {
        super(transactionId, errorCode, "ERROR", message);
    }

    public WriteValidationException(String transactionId, int errorCode, String message, Throwable cause) {
        super(transactionId, errorCode, "ERROR", message, cause);
    }
}
