package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class TransactionRegistry {

    private static final int DUPLICATE_OPEN = 1001;
    private static final int OWNERSHIP_MISMATCH = 1002;

    private final Map<TransactionKey, RuntimeModelTransaction> transactions = new ConcurrentHashMap<>();

    public void register(final RuntimeModelTransaction transaction) throws TransactionException {
        Objects.requireNonNull(transaction, "transaction");
        final TransactionKey key = new TransactionKey(transaction.pluginId(), transaction.documentId());
        final RuntimeModelTransaction previous = transactions.putIfAbsent(key, transaction);
        if (previous != null && previous.status() == TransactionStatus.OPEN) {
            throw error(transaction.transactionId(), DUPLICATE_OPEN, "Transaction already open for plugin "
                + transaction.pluginId() + " and document " + transaction.documentId().id());
        }
        if (previous != null) {
            transactions.put(key, transaction);
        }
    }

    public Optional<RuntimeModelTransaction> query(final String pluginId, final DocumentId documentId) {
        return Optional.ofNullable(transactions.get(new TransactionKey(pluginId, documentId)));
    }

    public void close(final RuntimeModelTransaction transaction) throws TransactionException {
        Objects.requireNonNull(transaction, "transaction");
        final TransactionKey key = new TransactionKey(transaction.pluginId(), transaction.documentId());
        if (!transactions.remove(key, transaction)) {
            throw error(transaction.transactionId(), OWNERSHIP_MISMATCH, "Transaction ownership mismatch");
        }
    }

    public void requireOwner(
        final RuntimeModelTransaction transaction,
        final String pluginId,
        final DocumentId documentId
    ) throws TransactionException {
        final RuntimeModelTransaction registered = transactions.get(new TransactionKey(pluginId, documentId));
        if (registered != transaction) {
            throw error(transaction.transactionId(), OWNERSHIP_MISMATCH, "Transaction ownership mismatch");
        }
    }

    private static TransactionException error(final String transactionId, final int code, final String message) {
        return new TransactionException(transactionId, code, "ERROR", message);
    }

    private record TransactionKey(String pluginId, DocumentId documentId) {
        private TransactionKey {
            pluginId = Objects.requireNonNull(pluginId, "pluginId");
            documentId = Objects.requireNonNull(documentId, "documentId");
        }
    }
}
