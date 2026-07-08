package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;

import java.util.Objects;

public final class TransactionValidator {

    private static final int CLOSED = 1101;
    private static final int PLUGIN_MISMATCH = 1102;
    private static final int DOCUMENT_MISMATCH = 1103;
    private static final int EXPIRED = 1104;

    public void validate(
        final RuntimeModelTransaction transaction,
        final String pluginId,
        final DocumentId documentId,
        final long currentVersion
    ) throws TransactionException {
        Objects.requireNonNull(transaction, "transaction");
        if (transaction.status() != TransactionStatus.OPEN) {
            throw error(transaction.transactionId(), CLOSED, "Transaction is not open");
        }
        if (!transaction.pluginId().equals(pluginId)) {
            throw error(transaction.transactionId(), PLUGIN_MISMATCH, "Transaction plugin mismatch");
        }
        if (!transaction.documentId().equals(documentId)) {
            throw error(transaction.transactionId(), DOCUMENT_MISMATCH, "Transaction document mismatch");
        }
        if (transaction.openedAtVersion() != currentVersion) {
            throw error(transaction.transactionId(), EXPIRED, "Transaction expired because host state changed");
        }
    }

    private static TransactionException error(final String transactionId, final int code, final String message) {
        return new TransactionException(transactionId, code, "ERROR", message);
    }
}
