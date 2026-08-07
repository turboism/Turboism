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
    }
}
