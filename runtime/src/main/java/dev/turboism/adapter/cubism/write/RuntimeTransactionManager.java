package dev.turboism.adapter.cubism.write;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.ModelTransaction;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.PluginContext;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class RuntimeTransactionManager implements TransactionManager {

    public static final String TURBOISM_CUBISM_WRITE = "turboism.cubism.model.write";

    private final HostWriteAdapter adapter;
    private final PermissionChecker permissionChecker;
    private final TransactionRegistry registry;
    private final TransactionValidator validator;
    private final AtomicLong nextTransactionId = new AtomicLong(1L);

    public RuntimeTransactionManager(final HostWriteAdapter adapter, final PermissionChecker permissionChecker) {
        this(adapter, permissionChecker, new TransactionRegistry(), new TransactionValidator());
    }

    RuntimeTransactionManager(
        final HostWriteAdapter adapter,
        final PermissionChecker permissionChecker,
        final TransactionRegistry registry,
        final TransactionValidator validator
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public RuntimeModelTransaction openTransaction(final PluginContext ctx, final DocumentId docId)
        throws TransactionException, CubismPermissionException {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(docId, "docId");
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
        return registry.query(pluginId, documentId).map(ModelTransaction.class::cast);
    }

    public void close(final RuntimeModelTransaction transaction) throws TransactionException {
        registry.close(transaction);
    }

    void requireWritePermission(final String operation) {
        permissionChecker.check(TURBOISM_CUBISM_WRITE, operation);
    }
}
