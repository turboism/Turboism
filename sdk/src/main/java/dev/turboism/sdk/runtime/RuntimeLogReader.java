package dev.turboism.sdk.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only framework log view supplied to Turboism's runtime-owned shell. */
public interface RuntimeLogReader {

    /** Returns a point-in-time view of the runtime log location and buffered lines. */
    Snapshot snapshot();

    /** Returns a fail-closed reader that always reports an empty snapshot. */
    static RuntimeLogReader unavailable() {
        return () -> new Snapshot(Optional.empty(), Optional.empty(), List.of());
    }

    /** Point-in-time view of the runtime log directory, active file, and buffered lines. */
    record Snapshot(
        Optional<Path> directory,
        Optional<Path> currentFile,
        List<String> lines
    ) {
        public Snapshot {
            directory = Objects.requireNonNull(directory, "directory");
            currentFile = Objects.requireNonNull(currentFile, "currentFile");
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        }
    }
}
