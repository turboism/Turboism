package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;
import dev.turboism.sdk.cubism.transaction.WriteValidationException;

import java.util.Objects;

/**
 * Checks that a transaction is still entitled to touch the host before any
 * staged work is applied. Kept separate from the registry so ownership
 * (who holds the slot) and validity (is the slot still current) fail with
 * distinct error codes.
 */
public final class TransactionValidator {

    private static final int PLUGIN_MISMATCH = 1102;
    private static final int DOCUMENT_MISMATCH = 1103;
    private static final int EXPIRED = 1104;

    /**
     * Rejects a transaction that has been closed, that belongs to a different
     * plugin or document than the caller claims, or that was opened against a
     * host version the Editor has since moved past.
     *
     * @param transaction transaction being checked
     * @param pluginId plugin the caller claims to be acting as
     * @param documentId document the caller claims to be writing
     * @param currentVersion the host's version right now
     * @throws dev.turboism.sdk.cubism.transaction.WriteValidationException with
     *     code 1101 when not open, 1102 on plugin mismatch, 1103 on document
     *     mismatch, and 1104 when host state changed under the transaction
     * @throws TransactionException if validation cannot be completed
     */
    public void validate(
        final RuntimeModelTransaction transaction,
        final String pluginId,
        final DocumentId documentId,
        final long currentVersion
    ) throws TransactionException {
        Objects.requireNonNull(transaction, "transaction");
        if (transaction.status() != TransactionStatus.OPEN) {
            throw new WriteValidationException(transaction.transactionId(), 1101, "Transaction is not open");
        }
        if (!transaction.pluginId().equals(pluginId)) {
            throw new WriteValidationException(transaction.transactionId(), PLUGIN_MISMATCH, "Transaction plugin mismatch");
        }
        if (!transaction.documentId().equals(documentId)) {
            throw new WriteValidationException(transaction.transactionId(), DOCUMENT_MISMATCH, "Transaction document mismatch");
        }
        if (transaction.openedAtVersion() != currentVersion) {
            throw new WriteValidationException(transaction.transactionId(), EXPIRED, "Transaction expired because host state changed");
        }
    }
}
