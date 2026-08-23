package dev.turboism.sdk.cubism.backup;


import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Framework-side sync capability hook: invoked with the new backup artifacts
 * after a {@link EditorAutoBackupService#backupNow()} completion.
 *
 * <p>Implementations must upload/copy the files to their target. A throwing
 * implementation is isolated: the backup result is never corrupted by a
 * target failure (the runtime records the failure and continues).</p>
 */
public interface BackupSyncTarget {

    /**
     * Uploads or otherwise syncs the newly produced backup files.
     *
     * @param newBackupFiles non-empty, size-greater-than-zero backup artifacts
     *                       produced by the completed backup run
     */
    void sync(List<File> newBackupFiles);

    /** Default no-op target; useful for tests and opt-out configurations. */
    static BackupSyncTarget noop() {
        return Noop.INSTANCE;
    }

    enum Noop implements BackupSyncTarget {
        INSTANCE;

        @Override
        public void sync(final List<File> newBackupFiles) {
            Objects.requireNonNull(newBackupFiles, "newBackupFiles");
        }
    }
}
