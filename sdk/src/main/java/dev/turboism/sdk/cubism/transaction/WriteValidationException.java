package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.PreviewApi;

/**
 * Signals that a write command failed validation before being staged, so the model is
 * untouched. Unlike its sibling transaction exceptions the error code is supplied by the
 * validating caller rather than fixed, allowing per-rule diagnostics; severity is always
 * {@code ERROR}.
 */
@PreviewApi
public class WriteValidationException extends TransactionException {

    public WriteValidationException(String transactionId, int errorCode, String message) {
        super(transactionId, errorCode, "ERROR", message);
    }

    public WriteValidationException(String transactionId, int errorCode, String message, Throwable cause) {
        super(transactionId, errorCode, "ERROR", message, cause);
    }
}
