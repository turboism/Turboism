package dev.turboism.sdk.cubism.backup;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.event.EventBus;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Published by the framework after a completed {@link EditorAutoBackupService#backupNow()}.
 *
 * <p>{@code newBackupFiles} lists the backup artifacts produced by the run
 * ({@code <name>_backup<yyyy_MMdd_HHmm>.cmo3}, size &gt; 0). {@code statuses}
 * is the per-document snapshot taken at completion. The event is immutable and
 * safe to share across plugin boundaries.</p>
 */
@PreviewApi
public record BackupCompletedEvent(
    long completedAtMillis,
    List<File> newBackupFiles,
    List<EditorAutoBackupStatus> statuses
) implements EventBus.TurboismEvent {

    public BackupCompletedEvent {
        newBackupFiles = List.copyOf(Objects.requireNonNull(newBackupFiles, "newBackupFiles"));
        statuses = List.copyOf(Objects.requireNonNull(statuses, "statuses"));
    }
}
