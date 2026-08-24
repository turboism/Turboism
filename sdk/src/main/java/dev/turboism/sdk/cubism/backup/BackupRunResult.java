package dev.turboism.sdk.cubism.backup;


import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * One-shot result of an explicit auto-backup command.
 *
 * <p>The artifact handles are returned only to the command caller and registered
 * {@link BackupSyncTarget}s. The corresponding global {@link BackupCompletedEvent}
 * contains detached metadata instead, so event subscribers never receive host file handles.</p>
 *
 * @param completedAtMillis completion time in epoch milliseconds
 * @param newBackupFiles backup artifacts produced by the command
 * @param statuses per-document host status snapshot captured at completion
 */
public record BackupRunResult(
    long completedAtMillis,
    List<File> newBackupFiles,
    List<EditorAutoBackupStatus> statuses
) {
    public BackupRunResult {
        if (completedAtMillis < 0L) {
            throw new IllegalArgumentException("completedAtMillis must not be negative");
        }
        newBackupFiles = List.copyOf(Objects.requireNonNull(newBackupFiles, "newBackupFiles"));
        statuses = List.copyOf(Objects.requireNonNull(statuses, "statuses"));
    }
}
