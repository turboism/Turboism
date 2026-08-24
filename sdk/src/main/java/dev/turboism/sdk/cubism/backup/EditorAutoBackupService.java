package dev.turboism.sdk.cubism.backup;

import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.plugin.Registration;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Auto-backup capability takeover over the host's native auto-backup manager
 * ({@code com.live2d.cubism.util.a}, CEAutobackupManager).
 *
 * <p>The service only mutates settings through the verified manager setters
 * (the host owns UUConfig persistence) and triggers backups through the
 * verified {@code updateAutoBackup} path. Every operation is exact-version
 * verified and fails closed to UNAVAILABLE when any required selector is
 * missing or the host slice is not connected.</p>
 */
public interface EditorAutoBackupService {

    /**
     * Reads the current host settings (enabled / interval / maxMB / backupDir).
     *
     * @throws UnsupportedOperationException when the service is unavailable
     */
    EditorAutoBackupSettings settings();

    /**
     * Mutates host settings through the manager setters, reads the values back,
     * and rolls them back to the observed originals (with verified readback)
     * when any mutation or readback step fails.
     *
     * @param settings target settings; {@code backupDir} is ignored (host-read-only)
     * @return the settings as read back from the host after the update
     * @throws IllegalArgumentException when the requested values are out of range
     * @throws UnsupportedOperationException when the service is unavailable
     * @throws IllegalStateException when the update failed and the rollback is unverified
     */
    EditorAutoBackupSettings updateSettings(EditorAutoBackupSettings settings);

    /**
     * Per-document auto-backup snapshot (lastAutoBackupTime / lastSavedTime /
     * modifiedAfterSaving / file) for every file content in the current pack.
     */
    List<EditorAutoBackupStatus> statuses();

    /**
     * Runs an immediate auto-backup: idempotent pack attach, then the host's
     * {@code updateAutoBackup} trigger, then polls the backup directory and the
     * document timestamps until the artifacts appear (or the timeout expires).
     *
     * <p>The returned stage completes with a {@link BackupRunResult} on success,
     * or exceptionally (sanitized) when the host call failed, the polling timed
     * out, or the service is unavailable. The runtime separately publishes a
     * detached {@link BackupCompletedEvent}. Sync targets registered
     * via {@link #registerSyncTarget} are invoked with the new files after
     * completion; target failures never fail the backup result.</p>
     */
    CompletionStage<BackupRunResult> backupNow();

    /**
     * Backs up a just-saved document through the verified host {@code saveDocument}
     * primitives, bypassing the host dirty condition
     * ({@code isModifiedAfterSaving && LastModified >= lastAutoBackupTime}):
     * right after a save the document is clean, so the host's
     * {@code updateAutoBackup} path would not back it up.
     *
     * <p>The saved snapshot is matched against the current pack file contents
     * ({@code getAllFileContents} + {@code IFileContent.getFile}); the matched
     * content is saved as {@code <name>_backup<yyyy_MMdd_HHmm>.<ext>} into the
     * host backup directory (only there, never touching the original document),
     * the artifact is polled for, and the returned stage completes with a
     * {@link BackupRunResult}. The runtime separately publishes a detached
     * {@link BackupCompletedEvent}. Sync targets registered via
     * {@link #registerSyncTarget} are invoked with the new file after
     * completion; target failures never fail the backup result.</p>
     *
     * <p>Idempotent debounce: per-document saves within the 2-second debounce
     * window are coalesced into the in-flight backup (the same stage is
     * returned). Backup runs are serialized globally, so host
     * {@code saveDocument} calls never run concurrently. Host calls run on the
     * host UI thread and never block the save callback or the EDT.</p>
     *
     * @param saved the snapshot of the saved document
     * @return the completed backup event, or a stage failed exceptionally when
     *         no pack content matches the snapshot, the host call failed, the
     *         artifact polling timed out, or the service is unavailable
     */
    CompletionStage<BackupRunResult> backupAfterSave(ProjectContentSnapshot saved);

    /**
     * Registers a sync target invoked with the new backup files after each
     * successful {@link #backupNow()} completion.
     */
    Registration registerSyncTarget(BackupSyncTarget target);

    /** Safe-mode instance: every operation fails closed to UNAVAILABLE. */
    static EditorAutoBackupService unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements EditorAutoBackupService {
        INSTANCE;

        @Override
        public EditorAutoBackupSettings settings() {
            throw new UnsupportedOperationException("auto-backup service is not available");
        }

        @Override
        public EditorAutoBackupSettings updateSettings(final EditorAutoBackupSettings settings) {
            Objects.requireNonNull(settings, "settings");
            throw new UnsupportedOperationException("auto-backup service is not available");
        }

        @Override
        public List<EditorAutoBackupStatus> statuses() {
            throw new UnsupportedOperationException("auto-backup service is not available");
        }

        @Override
        public CompletionStage<BackupRunResult> backupNow() {
            return CompletableFuture.failedStage(
                new UnsupportedOperationException("auto-backup service is not available")
            );
        }

        @Override
        public CompletionStage<BackupRunResult> backupAfterSave(final ProjectContentSnapshot saved) {
            Objects.requireNonNull(saved, "saved");
            return CompletableFuture.failedStage(
                new UnsupportedOperationException("auto-backup service is not available")
            );
        }

        @Override
        public Registration registerSyncTarget(final BackupSyncTarget target) {
            Objects.requireNonNull(target, "target");
            return () -> {
                // no-op registration on the unavailable service
            };
        }
    }
}
