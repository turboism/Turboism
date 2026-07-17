package dev.turboism.storage;

import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfinedStorageBackendAtomicWriteRaceTest {

    @TempDir
    Path temporary;

    @Test
    void rejectsParentReplacementBeforeTemporaryCreationWithoutReplacementSideEffects()
        throws Exception {
        final Roots roots = roots();
        final Path parent = Files.createDirectory(roots.data().resolve("documents"));
        final Path displacedParent = roots.data().resolve("documents-original");
        final Path target = parent.resolve("message.txt");
        final ConfinedStorageBackend backend = backend(
            roots,
            new CleanupEvidenceCollector(),
            new ConfinedStorageBackend.AtomicWriteInterlock() {
                @Override
                public void beforeTemporaryCreation() throws java.io.IOException {
                    Files.move(parent, displacedParent);
                    Files.createDirectory(parent);
                }
            }
        );

        final var result = backend.writeBytesAtomic(
            path("documents/message.txt"),
            bytes("new"),
            true
        );

        assertFalse(Files.exists(target), "replacement parent must not receive the target");
        assertFalse(Files.exists(displacedParent.resolve("message.txt")));
        assertTrue(temporaryFiles(parent).isEmpty());
        assertTrue(temporaryFiles(displacedParent).isEmpty());
        assertEquals(StorageErrorCode.CONFLICT, result.error().orElseThrow().code());
    }

    @Test
    void rejectsParentReplacementBeforeInstallWithoutTouchingDecoyOrOldTarget()
        throws Exception {
        final Roots roots = roots();
        final Path parent = Files.createDirectory(roots.data().resolve("documents"));
        final Path oldTarget = parent.resolve("message.txt");
        Files.writeString(oldTarget, "old");
        final Path displacedParent = roots.data().resolve("documents-original");
        final CleanupEvidenceCollector cleanupEvidence = new CleanupEvidenceCollector();
        final ConfinedStorageBackend backend = backend(
            roots,
            cleanupEvidence,
            new ConfinedStorageBackend.AtomicWriteInterlock() {
                @Override
                public void beforeInstall() throws java.io.IOException {
                    Files.move(parent, displacedParent);
                    Files.createDirectory(parent);
                    Files.writeString(parent.resolve("message.txt"), "decoy");
                }
            }
        );

        final var result = backend.writeBytesAtomic(
            path("documents/message.txt"),
            bytes("new"),
            true
        );

        assertEquals(StorageErrorCode.CONFLICT, result.error().orElseThrow().code());
        assertEquals("decoy", Files.readString(parent.resolve("message.txt")));
        assertEquals("old", Files.readString(displacedParent.resolve("message.txt")));
        assertTrue(temporaryFiles(parent).isEmpty());
        assertTrue(temporaryFiles(displacedParent).isEmpty());
        assertEquals(1, cleanupEvidence.snapshot().temporaryFilesDeleted());
        assertEquals(0, cleanupEvidence.snapshot().failures());
    }

    @Test
    void stableParentSupportsReplaceAndDoesNotCountInstalledTemporaryAsCleanup()
        throws Exception {
        final Roots roots = roots();
        final Path parent = Files.createDirectory(roots.data().resolve("documents"));
        Files.writeString(parent.resolve("message.txt"), "old");
        final CleanupEvidenceCollector cleanupEvidence = new CleanupEvidenceCollector();
        final ConfinedStorageBackend backend = backend(
            roots,
            cleanupEvidence,
            new ConfinedStorageBackend.AtomicWriteInterlock() { }
        );

        final var result = backend.writeBytesAtomic(
            path("documents/message.txt"),
            bytes("new"),
            true
        );

        assertTrue(result.written());
        assertEquals("new", Files.readString(parent.resolve("message.txt")));
        assertTrue(temporaryFiles(parent).isEmpty());
        assertEquals(0, cleanupEvidence.snapshot().temporaryFilesDeleted());
        assertEquals(0, cleanupEvidence.snapshot().failures());
    }

    @Test
    void noReplacePreservesTargetAndDeletesOnlyOwnedTemporary() throws Exception {
        final Roots roots = roots();
        final Path parent = Files.createDirectory(roots.data().resolve("documents"));
        Files.writeString(parent.resolve("message.txt"), "old");
        final CleanupEvidenceCollector cleanupEvidence = new CleanupEvidenceCollector();
        final ConfinedStorageBackend backend = backend(
            roots,
            cleanupEvidence,
            new ConfinedStorageBackend.AtomicWriteInterlock() { }
        );

        final var result = backend.writeBytesAtomic(
            path("documents/message.txt"),
            bytes("new"),
            false
        );

        assertEquals(StorageErrorCode.ALREADY_EXISTS, result.error().orElseThrow().code());
        assertEquals("old", Files.readString(parent.resolve("message.txt")));
        assertTrue(temporaryFiles(parent).isEmpty());
        assertEquals(1, cleanupEvidence.snapshot().temporaryFilesDeleted());
        assertEquals(0, cleanupEvidence.snapshot().failures());
    }

    @Test
    void copyWithoutReplacementPreservesTargetAndCleansOwnedTemporary() throws Exception {
        final Roots roots = roots();
        final Path parent = Files.createDirectory(roots.data().resolve("documents"));
        Files.writeString(parent.resolve("source.txt"), "source");
        Files.writeString(parent.resolve("target.txt"), "target");
        final CleanupEvidenceCollector cleanupEvidence = new CleanupEvidenceCollector();
        final ConfinedStorageBackend backend = backend(
            roots,
            cleanupEvidence,
            new ConfinedStorageBackend.AtomicWriteInterlock() { }
        );

        final var result = backend.copy(
            path("documents/source.txt"),
            path("documents/target.txt"),
            false
        );

        assertEquals(StorageErrorCode.ALREADY_EXISTS, result.error().orElseThrow().code());
        assertEquals("target", Files.readString(parent.resolve("target.txt")));
        assertTrue(temporaryFiles(parent).isEmpty());
        assertEquals(1, cleanupEvidence.snapshot().temporaryFilesDeleted());
        assertEquals(0, cleanupEvidence.snapshot().failures());
    }

    @Test
    void cleanupDoesNotDeleteAReplacementThatTakesOverOwnedTemporaryName()
        throws Exception {
        final Roots roots = roots();
        final Path parent = Files.createDirectory(roots.data().resolve("documents"));
        Files.writeString(parent.resolve("message.txt"), "old");
        final CleanupEvidenceCollector cleanupEvidence = new CleanupEvidenceCollector();
        final Path[] replacement = new Path[1];
        final ConfinedStorageBackend backend = backend(
            roots,
            cleanupEvidence,
            new ConfinedStorageBackend.AtomicWriteInterlock() {
                @Override
                public void beforeInstall() throws java.io.IOException {
                    final Path temporary = temporaryFiles(parent).get(0);
                    Files.delete(temporary);
                    Files.writeString(temporary, "replacement");
                    replacement[0] = temporary;
                }
            }
        );

        final var result = backend.writeBytesAtomic(
            path("documents/message.txt"),
            bytes("new"),
            false
        );

        assertEquals(StorageErrorCode.ALREADY_EXISTS, result.error().orElseThrow().code());
        assertEquals("old", Files.readString(parent.resolve("message.txt")));
        assertEquals("replacement", Files.readString(replacement[0]));
        assertEquals(0, cleanupEvidence.snapshot().temporaryFilesDeleted());
        assertEquals(1, cleanupEvidence.snapshot().failures());
    }

    @Test
    void failedInstallDeletesOnlyOwnedTemporaryAndRecordsEvidence() throws Exception {
        final Roots roots = roots();
        final Path parent = Files.createDirectory(roots.data().resolve("documents"));
        final CleanupEvidenceCollector cleanupEvidence = new CleanupEvidenceCollector();
        final ConfinedStorageBackend backend = backend(
            roots,
            cleanupEvidence,
            new ConfinedStorageBackend.AtomicWriteInterlock() {
                @Override
                public void beforeInstall() throws java.io.IOException {
                    throw new NoSuchFileException("simulated atomic install failure");
                }
            }
        );

        final var result = backend.writeBytesAtomic(
            path("documents/message.txt"),
            bytes("new"),
            true
        );

        assertEquals(StorageErrorCode.IO_FAILURE, result.error().orElseThrow().code());
        assertFalse(Files.exists(parent.resolve("message.txt")));
        assertTrue(temporaryFiles(parent).isEmpty());
        assertEquals(1, cleanupEvidence.snapshot().temporaryFilesDeleted());
        assertEquals(0, cleanupEvidence.snapshot().failures());
    }

    @Test
    void rejectsTargetReplacementWithLinkBeforeInstallWithoutTouchingLinkTarget()
        throws Exception {
        final Roots roots = roots();
        final Path parent = Files.createDirectory(roots.data().resolve("documents"));
        final Path target = parent.resolve("message.txt");
        Files.writeString(target, "old");
        final Path outside = temporary.resolve("outside.txt");
        Files.writeString(outside, "outside");
        final CleanupEvidenceCollector cleanupEvidence = new CleanupEvidenceCollector();
        final ConfinedStorageBackend backend = backend(
            roots,
            cleanupEvidence,
            new ConfinedStorageBackend.AtomicWriteInterlock() {
                @Override
                public void beforeInstall() throws java.io.IOException {
                    Files.delete(target);
                    Files.createSymbolicLink(target, outside);
                }
            }
        );

        final var result = backend.writeBytesAtomic(
            path("documents/message.txt"),
            bytes("new"),
            true
        );

        assertEquals(StorageErrorCode.LINK_ESCAPE, result.error().orElseThrow().code());
        assertTrue(Files.isSymbolicLink(target));
        assertEquals("outside", Files.readString(outside));
        assertTrue(temporaryFiles(parent).isEmpty());
        assertEquals(1, cleanupEvidence.snapshot().temporaryFilesDeleted());
        assertEquals(0, cleanupEvidence.snapshot().failures());
    }

    @Test
    void secureDirectoryStreamUnavailableFailsBeforeCreatingTemporaryFile() throws Exception {
        final Path archive = temporary.resolve("roots.zip");
        final URI uri = URI.create("jar:" + archive.toUri());
        try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            final Roots roots = new Roots(
                Files.createDirectory(fileSystem.getPath("/data")),
                Files.createDirectory(fileSystem.getPath("/state")),
                Files.createDirectory(fileSystem.getPath("/cache"))
            );
            final CleanupEvidenceCollector cleanupEvidence = new CleanupEvidenceCollector();
            final ConfinedStorageBackend backend = backend(
                roots,
                cleanupEvidence,
                new ConfinedStorageBackend.AtomicWriteInterlock() { }
            );

            final var result = backend.writeBytesAtomic(
                path("message.txt"),
                bytes("new"),
                true
            );

            assertEquals(
                StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE,
                result.error().orElseThrow().code()
            );
            assertTrue(temporaryFiles(roots.data()).isEmpty());
            assertFalse(Files.exists(roots.data().resolve("message.txt")));
            assertEquals(0, cleanupEvidence.snapshot().temporaryFilesDeleted());
            assertEquals(0, cleanupEvidence.snapshot().failures());
        }
    }

    private Roots roots() throws java.io.IOException {
        return new Roots(root("data"), root("state"), root("cache"));
    }

    private Path root(final String name) throws java.io.IOException {
        return Files.createDirectory(temporary.resolve(name));
    }

    private ConfinedStorageBackend backend(
        final Roots roots,
        final CleanupEvidenceCollector cleanupEvidence,
        final ConfinedStorageBackend.AtomicWriteInterlock interlock
    ) throws java.io.IOException {
        return new ConfinedStorageBackend(
            Map.of(
                StorageRoot.DATA, roots.data(),
                StorageRoot.STATE, roots.state(),
                StorageRoot.CACHE, roots.cache()
            ),
            cleanupEvidence,
            interlock
        );
    }

    private StoragePath path(final String relativePath) {
        return new StoragePath(StorageRoot.DATA, relativePath);
    }

    private byte[] bytes(final String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private List<Path> temporaryFiles(final Path directory) throws java.io.IOException {
        try (var children = Files.list(directory)) {
            return children
                .filter(path -> path.getFileName().toString().contains(".turboism-"))
                .toList();
        }
    }

    private record Roots(Path data, Path state, Path cache) {
    }
}
