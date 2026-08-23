package dev.turboism.sdk.runtime;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.event.EventBus;

import java.util.List;
import java.util.Objects;

/**
 * Runtime-owned, privacy-safe batch of Cubism log observations.
 *
 * <p>The Runtime bounds and redacts messages before publication. A positive
 * {@link #droppedEntries()} value reports entries rejected since the preceding
 * batch because the observation queue was full. The direct
 * {@link CubismLogService} stream remains the command-side source for host log
 * filtering and exact in-process inspection.</p>
 */
@PreviewApi
public record CubismLogBatchEvent(
    List<Entry> entries,
    long droppedEntries
) implements EventBus.TurboismEvent {

    public CubismLogBatchEvent {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (droppedEntries < 0L) {
            throw new IllegalArgumentException("droppedEntries must not be negative");
        }
        if (entries.isEmpty() && droppedEntries == 0L) {
            throw new IllegalArgumentException("a log batch must contain entries or drop evidence");
        }
    }

    /** One detached entry with no host logger object, throwable, or file handle. */
    public record Entry(
        CubismLogService.LogLevel level,
        String message,
        long timestampNanos
    ) {
        public Entry {
            level = Objects.requireNonNull(level, "level");
            message = Objects.requireNonNull(message, "message");
            if (timestampNanos < 0L) {
                throw new IllegalArgumentException("timestampNanos must not be negative");
            }
        }
    }
}
