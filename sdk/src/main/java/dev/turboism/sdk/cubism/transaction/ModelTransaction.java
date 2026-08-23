package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.cubism.write.CubismWriteCommand;

/**
 * A plugin-scoped, document-scoped write transaction.
 * All write operations must be performed within an open transaction.
 * Transactions are single-use: once committed or rolled back, the instance is invalid.
 */
public interface ModelTransaction {

    /** Returns the current status of this transaction. */
    TransactionStatus status();

    /**
     * Enqueues a narrow Cubism write command into this transaction.
     * The write is not visible to read services or host state until commit succeeds.
     * @throws TransactionException if the transaction is closed, stale, or validation fails.
     */
    void enqueue(CubismWriteCommand command) throws TransactionException;

    /**
     * Commits all enqueued write operations.
     * After commit, the transaction status becomes COMMITTED and no further
     * operations are allowed.
     * @throws TransactionException if commit fails or transaction is already closed.
     */
    void commit() throws TransactionException;

    /**
     * Rolls back all enqueued write operations, restoring the fake host
     * to its pre-transaction state.
     * After rollback, the transaction status becomes ROLLED_BACK.
     * @throws TransactionException if rollback fails or transaction is already closed.
     */
    void rollback() throws TransactionException;

    /**
     * Returns a human-readable identifier for this transaction, useful for diagnostics.
     */
    String transactionId();
}
