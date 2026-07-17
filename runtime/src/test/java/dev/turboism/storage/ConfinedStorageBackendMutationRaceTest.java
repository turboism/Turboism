package dev.turboism.storage;

import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfinedStorageBackendMutationRaceTest {

    @TempDir
    Path temporary;

    @Test
    void stableMovePreservesNoReplaceSemantics() throws Exception {
        final Roots roots = roots();
        final Path parent = Files.createDirectory(roots.data().resolve("files"));
        Files.writeString(parent.resolve("source.txt"), "source");
        Files.writeString(parent.resolve("target.txt"), "target");
        final ConfinedStorageBackend backend = backend(
            roots,
            new ConfinedStorageBackend.MutationInterlock() { }
        );

        final var noReplace = backend.moveAtomic(
            path("files/source.txt"),
            path("files/target.txt"),
            false
        );

        assertEquals(StorageErrorCode.ALREADY_EXISTS, noReplace.error().orElseThrow().code());
        assertEquals("source", Files.readString(parent.resolve("source.txt")));
        assertEquals("target", Files.readString(parent.resolve("target.txt")));

        final var replaced = backend.moveAtomic(
            path("files/source.txt"),
            path("files/target.txt"),
            true
        );

        assertTrue(replaced.changed());
        assertFalse(Files.exists(parent.resolve("source.txt")));
        assertEquals("source", Files.readString(parent.resolve("target.txt")));
    }

    @Test
    void moveRejectsTargetParentReplacementWithoutTouchingReplacement() throws Exception {
        final Roots roots = roots();
        final Path sourceParent = Files.createDirectory(roots.data().resolve("source"));
        final Path targetParent = Files.createDirectory(roots.data().resolve("target"));
        Files.writeString(sourceParent.resolve("message.txt"), "source");
        final Path displacedTargetParent = roots.data().resolve("target-original");
        final ConfinedStorageBackend backend = backend(
            roots,
            new ConfinedStorageBackend.MutationInterlock() {
                @Override
                public void beforeMove() throws java.io.IOException {
                    Files.move(targetParent, displacedTargetParent);
                    Files.createDirectory(targetParent);
                    Files.writeString(targetParent.resolve("message.txt"), "decoy");
                }
            }
        );

        final var result = backend.moveAtomic(
            path("source/message.txt"),
            path("target/message.txt"),
            true
        );

        assertEquals(StorageErrorCode.CONFLICT, result.error().orElseThrow().code());
        assertEquals("source", Files.readString(sourceParent.resolve("message.txt")));
        assertEquals("decoy", Files.readString(targetParent.resolve("message.txt")));
        assertFalse(Files.exists(displacedTargetParent.resolve("message.txt")));
    }

    @Test
    void moveRejectsTargetReplacementWithoutOverwritingReplacement() throws Exception {
        final Roots roots = roots();
        final Path sourceParent = Files.createDirectory(roots.data().resolve("source"));
        final Path targetParent = Files.createDirectory(roots.data().resolve("target"));
        Files.writeString(sourceParent.resolve("message.txt"), "source");
        final ConfinedStorageBackend backend = backend(
            roots,
            new ConfinedStorageBackend.MutationInterlock() {
                @Override
                public void beforeMove() throws java.io.IOException {
                    Files.writeString(targetParent.resolve("message.txt"), "decoy");
                }
            }
        );

        final var result = backend.moveAtomic(
            path("source/message.txt"),
            path("target/message.txt"),
            true
        );

        assertEquals(StorageErrorCode.CONFLICT, result.error().orElseThrow().code());
        assertEquals("source", Files.readString(sourceParent.resolve("message.txt")));
        assertEquals("decoy", Files.readString(targetParent.resolve("message.txt")));
    }

    @Test
    void moveRejectsSourceReplacementWithoutMovingReplacement() throws Exception {
        final Roots roots = roots();
        final Path sourceParent = Files.createDirectory(roots.data().resolve("source"));
        final Path targetParent = Files.createDirectory(roots.data().resolve("target"));
        Files.writeString(sourceParent.resolve("message.txt"), "source");
        final Path displacedSourceParent = roots.data().resolve("source-original");
        final ConfinedStorageBackend backend = backend(
            roots,
            new ConfinedStorageBackend.MutationInterlock() {
                @Override
                public void beforeMove() throws java.io.IOException {
                    Files.move(sourceParent, displacedSourceParent);
                    Files.createDirectory(sourceParent);
                    Files.writeString(sourceParent.resolve("message.txt"), "decoy");
                }
            }
        );

        final var result = backend.moveAtomic(
            path("source/message.txt"),
            path("target/message.txt"),
            true
        );

        assertEquals(StorageErrorCode.CONFLICT, result.error().orElseThrow().code());
        assertEquals("source", Files.readString(displacedSourceParent.resolve("message.txt")));
        assertEquals("decoy", Files.readString(sourceParent.resolve("message.txt")));
        assertFalse(Files.exists(targetParent.resolve("message.txt")));
    }

    @Test
    void stableRecursiveDeleteRemovesTreeThroughBoundDirectoryStreams() throws Exception {
        final Roots roots = roots();
        final Path directory = Files.createDirectories(roots.data().resolve("documents/nested"));
        Files.writeString(directory.resolve("message.txt"), "value");
        final ConfinedStorageBackend backend = backend(
            roots,
            new ConfinedStorageBackend.MutationInterlock() { }
        );

        final var result = backend.delete(path("documents"), true);

        assertTrue(result.changed());
        assertFalse(Files.exists(roots.data().resolve("documents")));
    }

    @Test
    void recursiveDeleteRejectsNestedReplacementBeforeAnyDeletion() throws Exception {
        final Roots roots = roots();
        final Path nested = Files.createDirectories(roots.data().resolve("documents/nested"));
        Files.writeString(nested.resolve("message.txt"), "original");
        final Path displacedNested = roots.data().resolve("documents/nested-original");
        final ConfinedStorageBackend backend = backend(
            roots,
            new ConfinedStorageBackend.MutationInterlock() {
                @Override
                public void beforeDelete() throws java.io.IOException {
                    Files.move(nested, displacedNested);
                    Files.createDirectory(nested);
                    Files.writeString(nested.resolve("message.txt"), "decoy");
                }
            }
        );

        final var result = backend.delete(path("documents"), true);

        assertFalse(result.changed());
        assertEquals(StorageErrorCode.CONFLICT, result.error().orElseThrow().code());
        assertEquals("original", Files.readString(displacedNested.resolve("message.txt")));
        assertEquals("decoy", Files.readString(nested.resolve("message.txt")));
        assertTrue(Files.isDirectory(roots.data().resolve("documents")));
    }

    @Test
    void deleteRejectsParentReplacementWithoutDeletingReplacementEntry() throws Exception {
        final Roots roots = roots();
        final Path parent = Files.createDirectory(roots.data().resolve("documents"));
        Files.writeString(parent.resolve("message.txt"), "original");
        final Path displacedParent = roots.data().resolve("documents-original");
        final ConfinedStorageBackend backend = backend(
            roots,
            new ConfinedStorageBackend.MutationInterlock() {
                @Override
                public void beforeDelete() throws java.io.IOException {
                    Files.move(parent, displacedParent);
                    Files.createDirectory(parent);
                    Files.writeString(parent.resolve("message.txt"), "decoy");
                }
            }
        );

        final var result = backend.delete(path("documents/message.txt"), false);

        assertEquals(StorageErrorCode.CONFLICT, result.error().orElseThrow().code());
        assertEquals("original", Files.readString(displacedParent.resolve("message.txt")));
        assertEquals("decoy", Files.readString(parent.resolve("message.txt")));
    }

    @Test
    void secureDirectoryStreamUnavailableFailsBeforeMoveOrDeleteSideEffects()
        throws Exception {
        final Path archive = temporary.resolve("roots.zip");
        final URI uri = URI.create("jar:" + archive.toUri());
        try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            final Roots roots = new Roots(
                Files.createDirectory(fileSystem.getPath("/data")),
                Files.createDirectory(fileSystem.getPath("/state")),
                Files.createDirectory(fileSystem.getPath("/cache"))
            );
            Files.writeString(roots.data().resolve("source.txt"), "source");
            Files.writeString(roots.data().resolve("delete.txt"), "delete");
            final ConfinedStorageBackend backend = backend(
                roots,
                new ConfinedStorageBackend.MutationInterlock() { }
            );

            final var move = backend.moveAtomic(
                path("source.txt"),
                path("target.txt"),
                false
            );
            final var delete = backend.delete(path("delete.txt"), false);

            assertEquals(
                StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE,
                move.error().orElseThrow().code()
            );
            assertEquals(
                StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE,
                delete.error().orElseThrow().code()
            );
            assertEquals("source", Files.readString(roots.data().resolve("source.txt")));
            assertFalse(Files.exists(roots.data().resolve("target.txt")));
            assertEquals("delete", Files.readString(roots.data().resolve("delete.txt")));
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
        final ConfinedStorageBackend.MutationInterlock interlock
    ) throws java.io.IOException {
        return new ConfinedStorageBackend(
            Map.of(
                StorageRoot.DATA, roots.data(),
                StorageRoot.STATE, roots.state(),
                StorageRoot.CACHE, roots.cache()
            ),
            new CleanupEvidenceCollector(),
            new ConfinedStorageBackend.AtomicWriteInterlock() { },
            interlock
        );
    }

    private StoragePath path(final String relativePath) {
        return new StoragePath(StorageRoot.DATA, relativePath);
    }

    private record Roots(Path data, Path state, Path cache) {
    }
}
