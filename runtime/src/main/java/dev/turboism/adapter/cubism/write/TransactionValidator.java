package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;
import dev.turboism.sdk.cubism.transaction.WriteValidationException;

import java.util.Objects;

public final class TransactionValidator {

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
