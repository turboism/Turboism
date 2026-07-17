package dev.turboism.storage;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Atomic replacement helper; unsupported filesystems fail closed. */
final class StorageAtomicMover {

    private StorageAtomicMover() {
    }

    static void move(
        final Path source,
        final Path target,
        final boolean replaceExisting
    ) throws IOException {
        if (!replaceExisting && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        if (replaceExisting) {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
            return;
        }
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }
}
