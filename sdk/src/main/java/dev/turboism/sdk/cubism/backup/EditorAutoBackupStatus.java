package dev.turboism.sdk.cubism.backup;

/**
 * Immutable per-document auto-backup snapshot (host {@code IFileContent} view).
 *
 * <p>Times are epoch milliseconds. The projection stays privacy-safe like its
 * event counterpart {@link BackupDocumentStatus}: it names the document but
 * never exposes the document's host file path.</p>
 */
public record EditorAutoBackupStatus(
        String documentName, long lastAutoBackupTimeMillis, long lastSavedTimeMillis, boolean modifiedAfterSaving) {

    public EditorAutoBackupStatus {
        if (documentName == null || documentName.isBlank()) {
            throw new IllegalArgumentException("documentName must not be blank");
        }
    }
}
