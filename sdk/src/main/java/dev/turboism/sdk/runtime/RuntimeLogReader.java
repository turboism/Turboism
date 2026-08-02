package dev.turboism.sdk.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only framework log view supplied to Turboism's built-in core plugin. */
public interface RuntimeLogReader {

    Snapshot snapshot();

    static RuntimeLogReader unavailable() {
        return () -> new Snapshot(Optional.empty(), Optional.empty(), List.of());
    }

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
