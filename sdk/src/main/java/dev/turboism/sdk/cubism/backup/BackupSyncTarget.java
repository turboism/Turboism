package dev.turboism.sdk.cubism.backup;

import java.util.List;
import java.util.Objects;

/**
 * Framework-side sync capability hook: invoked with the new backup artifacts
 * after a {@link EditorAutoBackupService#backupNow()} completion.
 *
 * <p>Implementations receive opaque {@link BackupArtifactHandle}s — never host
 * paths — and upload or copy the artifact bytes through
 * {@link BackupArtifactHandle#openStream()}. A throwing implementation is
 * isolated: the backup result is never corrupted by a target failure (the
 * runtime records the failure and continues).</p>
 */
public interface BackupSyncTarget {

    /**
     * Uploads or otherwise syncs the newly produced backup artifacts.
     *
     * @param newArtifacts non-empty, size-greater-than-zero artifact handles
     *                     produced by the completed backup run
     */
    void sync(List<BackupArtifactHandle> newArtifacts);

    /** Default no-op target; useful for tests and opt-out configurations. */
    static BackupSyncTarget noop() {
        return Noop.INSTANCE;
    }

    /** Singleton sync target that accepts the artifact list and does nothing with it. */
    enum Noop implements BackupSyncTarget {
        INSTANCE;

        @Override
        public void sync(final List<BackupArtifactHandle> newArtifacts) {
            Objects.requireNonNull(newArtifacts, "newArtifacts");
        }
    }
}
