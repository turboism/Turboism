package dev.turboism.sdk.cubism.backup;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/**
 * Privacy-safe document status included in a backup completion observation.
 *
 * <p>Unlike the command/query {@link EditorAutoBackupStatus}, this event projection
 * omits the host document path.</p>
 */
@PreviewApi
public record BackupDocumentStatus(
    String documentName,
    long lastAutoBackupTimeMillis,
    long lastSavedTimeMillis,
    boolean modifiedAfterSaving
) {
    public BackupDocumentStatus {
        Objects.requireNonNull(documentName, "documentName");
        if (documentName.isBlank()) {
            throw new IllegalArgumentException("documentName must not be blank");
        }
    }
}
