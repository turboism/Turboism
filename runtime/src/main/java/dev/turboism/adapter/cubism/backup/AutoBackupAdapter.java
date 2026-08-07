package dev.turboism.adapter.cubism.backup;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Adapter seam for the host auto-backup manager (CEAutobackupManager).
 *
 * <p>The connected adapter dispatches every host operation onto the host UI
 * thread (EDT) and delegates to the exact-version verified
 * {@link HostOperations}. The safe-mode adapter fails closed: every operation
 * throws {@link UnsupportedOperationException} and never touches the host.</p>
 */
public interface AutoBackupAdapter {

    /** Reads the current host settings snapshot (enabled / interval / maxMB / backupDir). */
    Snapshot settings();

    /**
     * Applies the target settings through the manager setters and reads the
     * result back. Throws on any mutation or readback failure; the caller is
     * responsible for rollback using the observed original snapshot.
     */
    Snapshot applySettings(Snapshot target);

    /** Per-document backup snapshot for every file content of the current pack. */
    List<Document> documents();

    /** Idempotent pack attach followed by the host's updateAutoBackup trigger. */
    void triggerBackupNow();

    /**
     * Produces the save-triggered backup artifact as a silent pure file copy of
     * the matched pack file content: {@code <name>_backup<yyyy_MMdd_HHmmss>.<ext>}
     * under a temporary {@code turboism-backup-} directory (the host already
     * wrote the document to its original file by the time the save hook fires;
     * no host saveDocument/UI is involved, and no file remains in the host
     * backup directory — the plugin uploads and deletes the temp artifact).
     * Matching prefers the saved document's UID list (modeling
     * {@code getDocumentUID}, animation {@code getSceneDocs} scene UIDs; game-data
     * has no UID mapping) and falls back to name/path matching. Returns the
     * produced artifact, or {@code null} when no pack file content matches the
     * given saved file (the caller fails closed); a missing source or failed
     * copy throws.
     */
    File saveDocumentFor(File matchFile, List<String> documentUids, long timestampMillis);

    static AutoBackupAdapter safeMode() {
        return SafeMode.INSTANCE;
    }

    static AutoBackupAdapter connected(final HostOperations host) {
        Objects.requireNonNull(host, "host");
        return new VerifiedDispatchAutoBackupAdapter(host);
    }

    /** Immutable host settings snapshot; {@code backupDir} may be null (fail closed). */
    record Snapshot(boolean enabled, int intervalMinutes, int maxMB, File backupDir) {

        public Snapshot {
            if (intervalMinutes < 0) {
                throw new IllegalArgumentException("intervalMinutes must not be negative");
            }
            if (maxMB < 0) {
                throw new IllegalArgumentException("maxMB must not be negative");
            }
        }
    }

    /** Immutable per-document host snapshot; {@code file} may be null (fail closed). */
    record Document(String name, File file, long lastAutoBackupTimeMillis, long lastSavedTimeMillis,
                    boolean modifiedAfterSaving) {

        public Document {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("document name must not be blank");
            }
        }
    }

    /** Exact-version verified primitive host operations (no dispatch, no orchestration). */
    interface HostOperations {

        Snapshot settings();

        Snapshot applySettings(Snapshot target);

        List<Document> documents();

        void triggerBackupNow();


        /** Saves one matched file content to the host backup directory; see {@link AutoBackupAdapter#saveDocumentFor}. */
        File saveDocumentFor(File matchFile, List<String> documentUids, long timestampMillis);
    }

    enum SafeMode implements AutoBackupAdapter {
        INSTANCE;

        @Override
        public Snapshot settings() {
            throw new UnsupportedOperationException("auto-backup is not available");
        }

        @Override
        public Snapshot applySettings(final Snapshot target) {
            Objects.requireNonNull(target, "target");
            throw new UnsupportedOperationException("auto-backup is not available");
        }

        @Override
        public List<Document> documents() {
            throw new UnsupportedOperationException("auto-backup is not available");
        }

        @Override
        public void triggerBackupNow() {
            throw new UnsupportedOperationException("auto-backup is not available");
        }

        @Override
        public File saveDocumentFor(
            final File matchFile, final List<String> documentUids, final long timestampMillis
        ) {
            Objects.requireNonNull(matchFile, "matchFile");
            Objects.requireNonNull(documentUids, "documentUids");
            throw new UnsupportedOperationException("auto-backup is not available");
        }
    }
}
