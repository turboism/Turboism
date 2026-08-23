package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.transaction.TransactionAlreadyActiveException;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;
import dev.turboism.sdk.cubism.transaction.WriteValidationException;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds at most one live transaction per (plugin, document) pair, which is
 * what makes concurrent writes to the same document by the same plugin
 * impossible rather than merely discouraged.
 *
 * <p>Backed by a concurrent map and safe to call from several threads.</p>
 */
public final class TransactionRegistry {

    private static final int OWNERSHIP_MISMATCH = 1002;

    private final Map<TransactionKey, RuntimeModelTransaction> transactions = new ConcurrentHashMap<>();

    /**
     * Claims the (plugin, document) slot for a transaction. A slot left behind
     * by an already-finished transaction is taken over silently.
     *
     * @param transaction transaction to register
     * @throws dev.turboism.sdk.cubism.transaction.TransactionAlreadyActiveException
     *     if another transaction still holds the slot open
     * @throws TransactionException if registration is refused
     */
    public void register(final RuntimeModelTransaction transaction) throws TransactionException {
        Objects.requireNonNull(transaction, "transaction");
        final TransactionKey key = new TransactionKey(transaction.pluginId(), transaction.documentId());
        final RuntimeModelTransaction previous = transactions.putIfAbsent(key, transaction);
        if (previous != null && previous.status() == TransactionStatus.OPEN) {
            throw new TransactionAlreadyActiveException(
                transaction.transactionId(),
                "Transaction already open for plugin " + transaction.pluginId() + " and document " + transaction.documentId().value()
            );
        }
        if (previous != null) {
            transactions.put(key, transaction);
        }
    }

    /**
     * @param pluginId owning plugin
     * @param documentId document being written
     * @return the transaction currently holding that slot, empty if none does;
     *     the returned transaction is not guaranteed to still be open
     */
    public Optional<RuntimeModelTransaction> query(final String pluginId, final DocumentId documentId) {
        return Optional.ofNullable(transactions.get(new TransactionKey(pluginId, documentId)));
    }

    /**
     * Releases the slot, but only if it is still held by this exact
     * transaction instance.
     *
     * @param transaction transaction to unregister
     * @throws TransactionException with code 1002 if the slot is empty or held
     *     by a different transaction
     */
    public void close(final RuntimeModelTransaction transaction) throws TransactionException {
        Objects.requireNonNull(transaction, "transaction");
        final TransactionKey key = new TransactionKey(transaction.pluginId(), transaction.documentId());
        if (!transactions.remove(key, transaction)) {
            throw error(transaction.transactionId(), "Transaction ownership mismatch");
        }
    }

    /**
     * Asserts that the given transaction is the one registered for the
     * (plugin, document) slot, by identity rather than by equality.
     *
     * @param transaction transaction claiming ownership
     * @param pluginId plugin half of the slot key
     * @param documentId document half of the slot key
     * @throws TransactionException with code 1002 if some other transaction, or
     *     none, owns the slot
     */
    public void requireOwner(
        final RuntimeModelTransaction transaction,
        final String pluginId,
        final DocumentId documentId
    ) throws TransactionException {
        final RuntimeModelTransaction registered = transactions.get(new TransactionKey(pluginId, documentId));
        if (registered != transaction) {
            throw error(transaction.transactionId(), "Transaction ownership mismatch");
        }
    }

    private static TransactionException error(final String transactionId, final String message) {
        return new WriteValidationException(transactionId, OWNERSHIP_MISMATCH, message);
    }

    private record TransactionKey(String pluginId, DocumentId documentId) {
        private TransactionKey {
            pluginId = Objects.requireNonNull(pluginId, "pluginId");
            documentId = Objects.requireNonNull(documentId, "documentId");
        }
    }
}
