package dev.turboism.sdk.cubism.backup;

import dev.turboism.sdk.event.EventBus;

import java.util.List;
import java.util.Objects;

/**
 * Runtime-owned observation published after an explicit auto-backup command succeeds.
 *
 * <p>The payload is detached and privacy-safe: artifacts expose base names and sizes,
 * document statuses omit host paths, and no mutable {@code File} handles cross the
 * global event bus. Code that initiated the command receives a {@link BackupRunResult}
 * with the exact artifacts it owns.</p>
 */
public record BackupCompletedEvent(
    long completedAtMillis,
    List<BackupArtifact> artifacts,
    List<BackupDocumentStatus> statuses
) implements EventBus.TurboismEvent {

    public BackupCompletedEvent {
        if (completedAtMillis < 0L) {
            throw new IllegalArgumentException("completedAtMillis must not be negative");
        }
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        statuses = List.copyOf(Objects.requireNonNull(statuses, "statuses"));
    }
}
