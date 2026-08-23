package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.PreviewApi;

/**
 * Signals that the calling plugin lacks a permission required by an operation attempted
 * inside a transaction; the operation is refused and nothing is staged. Carries error code
 * 1003 at {@code ERROR} severity.
 */
@PreviewApi
public class PermissionDeniedException extends TransactionException {

    public PermissionDeniedException(String transactionId, String message) {
        super(transactionId, 1003, "ERROR", message);
    }
}
