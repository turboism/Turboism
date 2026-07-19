package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.PreviewApi;

/** Lifecycle status of a write transaction. */
@PreviewApi
public enum TransactionStatus {
    /** Transaction is open and accepts write operations. */
    OPEN,
    /** Transaction has been successfully committed. */
    COMMITTED,
    /** Transaction has been rolled back. */
    ROLLED_BACK,
    /** Transaction has failed due to an error. */
    FAILED
}
