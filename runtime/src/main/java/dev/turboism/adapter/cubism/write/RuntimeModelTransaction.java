package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.transaction.CommitFailedException;
import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.ModelTransaction;
import dev.turboism.sdk.cubism.transaction.RollbackFailedException;
import dev.turboism.sdk.cubism.transaction.TransactionClosedException;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;

import java.util.Objects;

public final class RuntimeModelTransaction implements ModelTransaction {

    private final String transactionId;
    private final String pluginId;
    private final DocumentId documentId;
    private final HostWriteAdapter adapter;
    private final HostWriteAdapter.HostSnapshot snapshot;
    private final TransactionRegistry registry;
    private final TransactionValidator validator;
    private final WriteCommandQueue queue;
    private final RuntimeTransactionManager permissionOwner;
    private TransactionStatus status = TransactionStatus.OPEN;

    RuntimeModelTransaction(
        final String transactionId,
        final String pluginId,
        final DocumentId documentId,
        final HostWriteAdapter adapter,
        final HostWriteAdapter.HostSnapshot snapshot,
        final TransactionRegistry registry,
        final TransactionValidator validator,
        final RuntimeTransactionManager permissionOwner
    ) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.permissionOwner = Objects.requireNonNull(permissionOwner, "permissionOwner");
        this.queue = new WriteCommandQueue();
    }

    @Override
    public synchronized void enqueue(final CubismWriteCommand command) throws TransactionException {
        permissionOwner.requireWritePermission("transaction.enqueue");
        validateOpen();
        queue.add(command);
    }

    @Override
    public void commit() throws TransactionException {
        synchronized (this) {
            permissionOwner.requireWritePermission("transaction.commit");
            validateOpen();
        }
        permissionOwner.dispatchTransactionWork(this, "transaction.commit", this::commitOnScheduler);
    }

    private synchronized void commitOnScheduler() throws TransactionException {
        validateOpen();
        try {
            adapter.apply(documentId, queue.commands());
            status = TransactionStatus.COMMITTED;
            registry.close(this);
        } catch (TransactionException error) {
            rollbackAfterCommitFailure(error);
            throw new CommitFailedException(transactionId, "Commit failed and transaction was rolled back", error);
        } catch (RuntimeException error) {
            rollbackAfterCommitFailure(error);
            throw new CommitFailedException(transactionId, "Commit failed and transaction was rolled back", error);
        }
    }

    @Override
    public void rollback() throws TransactionException {
        synchronized (this) {
            validateOpen();
        }
        permissionOwner.dispatchTransactionWork(this, "transaction.rollback", this::rollbackOnScheduler);
    }

    private synchronized void rollbackOnScheduler() throws TransactionException {
        validateOpen();
        try {
            adapter.restore(snapshot);
            status = TransactionStatus.ROLLED_BACK;
            registry.close(this);
        } catch (TransactionException error) {
            status = TransactionStatus.FAILED;
            throw error;
        } catch (RuntimeException error) {
            status = TransactionStatus.FAILED;
            throw new RollbackFailedException(transactionId, "Rollback failed", error);
        }
    }

    private void rollbackAfterCommitFailure(final Throwable commitFailure) throws RollbackFailedException {
        try {
            adapter.restore(snapshot);
            status = TransactionStatus.ROLLED_BACK;
            registry.close(this);
        } catch (TransactionException | RuntimeException rollbackFailure) {
            status = TransactionStatus.FAILED;
            throw new RollbackFailedException(
                transactionId,
                "Rollback failed after commit failure",
                rollbackFailure
            );
        }
    }

    @Override
    public synchronized TransactionStatus status() {
        return status;
    }

    @Override
    public String transactionId() {
        return transactionId;
    }

    public String pluginId() {
        return pluginId;
    }

    public DocumentId documentId() {
        return documentId;
    }

    long openedAtVersion() {
        return snapshot.version();
    }

    private void validateOpen() throws TransactionException {
        if (status != TransactionStatus.OPEN) {
            throw new TransactionClosedException(transactionId, "Transaction is not open");
        }
        registry.requireOwner(this, pluginId, documentId);
        validator.validate(this, pluginId, documentId, adapter.version());
    }
}
