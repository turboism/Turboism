package dev.turboism.adapter.cubism.write;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.transaction.ModelTransaction;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.PluginContext;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class RuntimeTransactionManager implements TransactionManager {

    public static final String TURBOISM_CUBISM_WRITE = "turboism.cubism.model.write";
    static final String DEFAULT_CAPABILITY = "none";
    private static final long TRANSACTION_WAIT_TIMEOUT_MILLIS = 1_000L;

    private final HostWriteAdapter adapter;
    private final PermissionChecker permissionChecker;
    private final TransactionRegistry registry;
    private final TransactionValidator validator;
    private final RuntimeScheduler scheduler;
    private final AtomicLong nextTransactionId = new AtomicLong(1L);

    public RuntimeTransactionManager(
        final HostWriteAdapter adapter,
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler
    ) {
        this(adapter, permissionChecker, new TransactionRegistry(), new TransactionValidator(), scheduler);
    }

    RuntimeTransactionManager(
        final HostWriteAdapter adapter,
        final PermissionChecker permissionChecker,
        final TransactionRegistry registry,
        final TransactionValidator validator,
        final RuntimeScheduler scheduler
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public RuntimeModelTransaction openTransaction(final PluginContext ctx, final DocumentId docId)
        throws TransactionException, CubismPermissionException {
        Objects.requireNonNull(ctx, "ctx");
        requireDocumentId(docId, "docId");
        requireWritePermission("transaction.open");
        final RuntimeModelTransaction transaction = new RuntimeModelTransaction(
            "tx-" + nextTransactionId.getAndIncrement(),
            ctx.descriptor().id(),
            docId,
            adapter,
            adapter.capture(docId),
            registry,
            validator,
            this
        );
        registry.register(transaction);
        return transaction;
    }

    public Optional<ModelTransaction> query(final String pluginId, final DocumentId documentId) {
        requireDocumentId(documentId, "documentId");
        return registry.query(pluginId, documentId).map(ModelTransaction.class::cast);
    }

    public void close(final RuntimeModelTransaction transaction) throws TransactionException {
        registry.close(transaction);
    }

    void requireWritePermission(final String operation) {
        permissionChecker.check(TURBOISM_CUBISM_WRITE, operation);
    }

    void dispatchTransactionWork(
        final RuntimeModelTransaction transaction,
        final String taskType,
        final ThrowingRunnable work
    ) throws TransactionException {
        final CompletableFuture<TransactionException> completion = new CompletableFuture<>();
        final PluginTask task = new PluginTask(
            taskType,
            transaction.pluginId(),
            "transaction:" + transaction.transactionId() + ":" + transaction.documentId().value(),
            DEFAULT_CAPABILITY
        );
        final boolean accepted = scheduler.dispatch(task, () -> {
            try {
                work.run();
                completion.complete(null);
            } catch (TransactionException exception) {
                completion.complete(exception);
            } catch (RuntimeException exception) {
                completion.complete(new TransactionException(
                    transaction.transactionId(),
                    1203,
                    "ERROR",
                    "Transaction scheduler task failed",
                    exception
                ));
            }
        });
        if (!accepted) {
            throw new TransactionException(
                transaction.transactionId(),
                1206,
                "ERROR",
                "Transaction scheduler rejected " + taskType
            );
        }
        awaitTransactionWork(transaction, taskType, completion);
    }

    private static DocumentId requireDocumentId(final DocumentId documentId, final String name) {
        Objects.requireNonNull(documentId, name);
        if (documentId.value().isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return documentId;
    }

    private static void awaitTransactionWork(
        final RuntimeModelTransaction transaction,
        final String taskType,
        final CompletableFuture<TransactionException> completion
    ) throws TransactionException {
        try {
            TransactionException failure = completion.get(TRANSACTION_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (failure != null) {
                throw failure;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TransactionException(
                transaction.transactionId(),
                1204,
                "ERROR",
                "Interrupted while waiting for " + taskType,
                exception
            );
        } catch (ExecutionException exception) {
            throw new TransactionException(
                transaction.transactionId(),
                1205,
                "ERROR",
                "Failed while waiting for " + taskType,
                exception
            );
        } catch (TimeoutException exception) {
            throw new TransactionException(
                transaction.transactionId(),
                1206,
                "ERROR",
                "Timed out waiting for " + taskType,
                exception
            );
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws TransactionException;
    }
}
