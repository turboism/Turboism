package dev.turboism.mapping.draft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@FunctionalInterface
public interface AtomicMover {
    void move(Path source, Path target) throws IOException;

    static AtomicMover system() {
        return (source, target) -> Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        );
    }
}
