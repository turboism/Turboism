package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.ModelTransaction;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;
import dev.turboism.sdk.cubism.write.WriteParameterCommand;

import java.util.Objects;

public final class RuntimeModelTransaction implements ModelTransaction {

    private static final int COMMIT_FAILED = 1201;
    private static final int ROLLBACK_FAILED = 1202;

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

    public synchronized void enqueue(final WriteParameterCommand command) throws TransactionException {
        permissionOwner.requireWritePermission("transaction.enqueue");
        validateOpen();
        queue.add(command);
    }

    @Override
    public synchronized void commit() throws TransactionException {
        permissionOwner.requireWritePermission("transaction.commit");
        validateOpen();
        try {
            adapter.apply(documentId, queue.commands());
            status = TransactionStatus.COMMITTED;
            registry.close(this);
        } catch (TransactionException error) {
            status = TransactionStatus.FAILED;
            throw error;
        } catch (RuntimeException error) {
            status = TransactionStatus.FAILED;
            throw new TransactionException(transactionId, COMMIT_FAILED, "ERROR", "Commit failed", error);
        }
    }

    @Override
    public synchronized void rollback() throws TransactionException {
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
            throw new TransactionException(transactionId, ROLLBACK_FAILED, "ERROR", "Rollback failed", error);
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
        registry.requireOwner(this, pluginId, documentId);
        validator.validate(this, pluginId, documentId, adapter.version());
    }
}
