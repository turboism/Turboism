package dev.turboism.sdk.cubism.backup;


/**
 * Immutable per-document auto-backup snapshot (host {@code IFileContent} view).
 *
 * <p>Times are epoch milliseconds. {@code filePath} is the document's host file
 * path; it is {@code null} when the host document exposes no file (fail closed).</p>
 */
public record EditorAutoBackupStatus(
    String documentName,
    String filePath,
    long lastAutoBackupTimeMillis,
    long lastSavedTimeMillis,
    boolean modifiedAfterSaving
) {

    public EditorAutoBackupStatus {
        if (documentName == null || documentName.isBlank()) {
            throw new IllegalArgumentException("documentName must not be blank");
        }
    }
}
