package dev.turboism.mapping.draft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The single filesystem publish step of the mapping review pipeline, isolated behind an interface
 * so tests can observe or fail the move without touching a real disk.
 *
 * <p>The contract is all-or-nothing: a mapping pack is either fully replaced or left exactly as it
 * was. Implementations must not leave a partially written target behind.
 */
@FunctionalInterface
public interface AtomicMover {
    /**
     * Publishes {@code source} over {@code target} in one indivisible step.
     *
     * @param source the fully written temporary file
     * @param target the destination, replaced if it already exists
     * @throws IOException if the move could not be performed atomically, in which case the target
     *     is expected to be untouched
     */
    void move(Path source, Path target) throws IOException;

    /**
     * @return a mover backed by the real filesystem, requesting an atomic replace; it fails rather
     *     than silently degrading to a copy when the filesystem cannot move atomically
     */
    static AtomicMover system() {
        return (source, target) -> Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        );
    }
}
