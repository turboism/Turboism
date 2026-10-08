package dev.turboism.sdk.cubism.backup;

import java.util.List;
import java.util.Objects;

/**
 * One-shot result of an explicit auto-backup command.
 *
 * <p>The artifact handles are returned only to the command caller and registered
 * {@link BackupSyncTarget}s. The corresponding global {@link BackupCompletedEvent}
 * contains detached {@link BackupArtifact} metadata instead, so event subscribers
 * never receive artifact handles.</p>
 *
 * @param completedAtMillis completion time in epoch milliseconds
 * @param artifacts backup artifact handles produced by the command
 * @param statuses per-document host status snapshot captured at completion
 */
public record BackupRunResult(
        long completedAtMillis, List<BackupArtifactHandle> artifacts, List<EditorAutoBackupStatus> statuses) {
    public BackupRunResult {
        if (completedAtMillis < 0L) {
            throw new IllegalArgumentException("completedAtMillis must not be negative");
        }
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        statuses = List.copyOf(Objects.requireNonNull(statuses, "statuses"));
    }
}
