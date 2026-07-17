package dev.turboism.storage;

import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageAtomicMoverTest {

    @TempDir
    Path temporary;

    @Test
    void unsupportedAtomicMoveFailsClosedAndPreservesSourceAndTarget() throws Exception {
        final Roots roots = roots();
        final Path source = roots.data().resolve("source.txt");
        final Path target = roots.data().resolve("target.txt");
        Files.writeString(source, "source");
        Files.writeString(target, "target");
        final ConfinedStorageBackend backend = backend(roots, unsupportedMover());

        final var result = backend.moveAtomic(path("source.txt"), path("target.txt"), true);

        assertEquals(
            StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE,
            result.error().orElseThrow().code()
        );
        assertEquals("source", Files.readString(source));
        assertEquals("target", Files.readString(target));
    }

    @Test
    void unsupportedAtomicCopyFailsClosedPreservesOldTargetAndCleansTemporary() throws Exception {
        final Roots roots = roots();
        final Path source = roots.data().resolve("source.txt");
        final Path target = roots.data().resolve("target.txt");
        Files.writeString(source, "source");
        Files.writeString(target, "target");
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final ConfinedStorageBackend backend = backend(roots, evidence, unsupportedMover());

        final var result = backend.copy(path("source.txt"), path("target.txt"), true);

        assertEquals(
            StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE,
            result.error().orElseThrow().code()
        );
        assertEquals("source", Files.readString(source));
        assertEquals("target", Files.readString(target));
        assertNoTemporaryFiles(roots.data());
        assertEquals(1L, evidence.snapshot().temporaryFilesDeleted());
        assertEquals(0L, evidence.snapshot().failures());
    }

    @Test
    void unsupportedAtomicWriteFailsClosedPreservesOldTargetAndCleansTemporary() throws Exception {
        final Roots roots = roots();
        final Path target = roots.data().resolve("target.txt");
        Files.writeString(target, "old");
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final ConfinedStorageBackend backend = backend(roots, evidence, unsupportedMover());

        final var result = backend.writeBytesAtomic(path("target.txt"), bytes("new"), true);

        assertEquals(
            StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE,
            result.error().orElseThrow().code()
        );
        assertEquals("old", Files.readString(target));
        assertNoTemporaryFiles(roots.data());
        assertEquals(1L, evidence.snapshot().temporaryFilesDeleted());
        assertEquals(0L, evidence.snapshot().failures());
    }

    @Test
    void noReplacePreservesExistingTarget() throws Exception {
        final Path source = temporary.resolve("source.txt");
        final Path target = temporary.resolve("target.txt");
        Files.writeString(source, "source");
        Files.writeString(target, "target");

        assertThrows(
            java.nio.file.FileAlreadyExistsException.class,
            () -> StorageAtomicMover.move(source, target, false)
        );

        assertEquals("source", Files.readString(source));
        assertEquals("target", Files.readString(target));
    }

    private Roots roots() throws Exception {
        return new Roots(
            Files.createDirectory(temporary.resolve("data")),
            Files.createDirectory(temporary.resolve("state")),
            Files.createDirectory(temporary.resolve("cache"))
        );
    }

    private ConfinedStorageBackend backend(
        final Roots roots,
        final ConfinedStorageBackend.AtomicMover mover
    ) throws Exception {
        return backend(roots, new CleanupEvidenceCollector(), mover);
    }

    private ConfinedStorageBackend backend(
        final Roots roots,
        final CleanupEvidenceCollector evidence,
        final ConfinedStorageBackend.AtomicMover mover
    ) throws Exception {
        return new ConfinedStorageBackend(
            Map.of(
                StorageRoot.DATA, roots.data(),
                StorageRoot.STATE, roots.state(),
                StorageRoot.CACHE, roots.cache()
            ),
            evidence,
            mover
        );
    }

    private ConfinedStorageBackend.AtomicMover unsupportedMover() {
        return (source, target, replaceExisting) -> {
            throw new AtomicMoveNotSupportedException(
                source.toString(),
                target.toString(),
                "simulated unsupported atomic move"
            );
        };
    }

    private static StoragePath path(final String relativePath) {
        return new StoragePath(StorageRoot.DATA, relativePath);
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void assertNoTemporaryFiles(final Path directory) throws Exception {
        try (var children = Files.list(directory)) {
            assertFalse(children.anyMatch(
                path -> path.getFileName().toString().contains(".turboism-")
            ));
        }
    }

    private record Roots(Path data, Path state, Path cache) {
    }
}
