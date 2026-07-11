package dev.turboism.mapping.draft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Set;

/** Creates and owns a private, verified artifact snapshot for bounded scanning. */
@FunctionalInterface
interface ArtifactSnapshotter {
    ArtifactSnapshot create(Path source, long maxBytes) throws IOException;

    static ArtifactSnapshotter system() {
        return (source, maxBytes) -> {
            final Path directory = Files.createTempDirectory(
                "turboism-mapping-snapshot-", privateDirectoryAttributes());
            boolean completed = false;
            try {
                final Path snapshot = directory.resolve("artifact.jar");
                final FileSafety.Digest digest = FileSafety.snapshot(source, snapshot, maxBytes);
                completed = true;
                return new ArtifactSnapshot(directory, snapshot, digest);
            } finally {
                if (!completed) deleteDirectory(directory);
            }
        };
    }

    record ArtifactSnapshot(Path directory, Path path, FileSafety.Digest digest) implements AutoCloseable {
        @Override public void close() {
            deleteDirectory(directory);
        }
    }

    private static FileAttribute<?>[] privateDirectoryAttributes() {
        try {
            return new FileAttribute<?>[]{java.nio.file.attribute.PosixFilePermissions.asFileAttribute(Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            ))};
        } catch (UnsupportedOperationException exception) {
            return new FileAttribute<?>[0];
        }
    }

    private static void deleteDirectory(final Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Snapshot cleanup is best effort; the scan result or failure remains authoritative.
        }
    }
}
