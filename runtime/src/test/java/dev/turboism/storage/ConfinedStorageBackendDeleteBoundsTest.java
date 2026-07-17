package dev.turboism.storage;

import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfinedStorageBackendDeleteBoundsTest {

    @TempDir
    Path temporary;

    @Test
    void depthZeroIncludesAndDeletesTheTargetNode() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(0, 1, 2));
        final Path target = temporary.resolve("data/target.txt");
        Files.writeString(target, "target");

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "target.txt"),
            true
        );

        assertTrue(result.changed());
        assertTrue(result.error().isEmpty());
        assertFalse(Files.exists(target));
    }

    @Test
    void depthLimitIncludesAChildAtTheExactMaximumDepth() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(1, 2, 6));
        final Path tree = temporary.resolve("data/tree");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("child.txt"), "child");

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "tree"),
            true
        );

        assertTrue(result.changed());
        assertTrue(result.error().isEmpty());
        assertFalse(Files.exists(tree));
    }

    @Test
    void depthLimitBeforeAnyDeletionKeepsTheTreeAndReportsTheChild() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(0, 2, 6));
        final Path tree = temporary.resolve("data/tree");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("child.txt"), "child");

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "tree"),
            true
        );

        assertFalse(result.changed());
        assertEquals(
            StorageErrorCode.SIZE_LIMIT_EXCEEDED,
            result.error().orElseThrow().code()
        );
        assertEquals(
            new StoragePath(StorageRoot.DATA, "tree/child.txt"),
            result.error().orElseThrow().path()
        );
        assertTrue(Files.exists(tree.resolve("child.txt")));
        assertTrue(Files.isDirectory(tree));
    }

    @Test
    void depthLimitReportsPartialAtDeepChildAfterSortedEarlierSiblingDeletion()
        throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(1, 10_000, 100_000));
        final Path tree = temporary.resolve("data/tree");
        Files.createDirectories(tree.resolve("z-deep"));
        Files.writeString(tree.resolve("a-first.txt"), "first");
        Files.writeString(tree.resolve("z-deep/leaf.txt"), "leaf");

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "tree"),
            true
        );

        assertTrue(result.changed());
        assertEquals(StorageErrorCode.PARTIAL_DELETE, result.error().orElseThrow().code());
        assertEquals(
            new StoragePath(StorageRoot.DATA, "tree/z-deep/leaf.txt"),
            result.error().orElseThrow().path()
        );
        assertFalse(Files.exists(tree.resolve("a-first.txt")));
        assertTrue(Files.exists(tree.resolve("z-deep/leaf.txt")));
        assertTrue(Files.isDirectory(tree));
    }

    @Test
    void entryLimitCountsTheRootAsOneExactEntry() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(0, 1, 2));
        final Path target = temporary.resolve("data/target.txt");
        Files.writeString(target, "target");

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "target.txt"),
            true
        );

        assertTrue(result.changed());
        assertTrue(result.error().isEmpty());
        assertFalse(Files.exists(target));
    }

    @Test
    void entryLimitIncludesOneDiscoveredChildAtTheExactLimit() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(1, 2, 6));
        final Path tree = temporary.resolve("data/tree");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("child.txt"), "child");

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "tree"),
            true
        );

        assertTrue(result.changed());
        assertTrue(result.error().isEmpty());
        assertFalse(Files.exists(tree));
    }

    @Test
    void entryLimitDuringEnumerationFailsAtTheDirectoryWithoutDeleting() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(1, 1, 6));
        final Path tree = temporary.resolve("data/tree");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("child.txt"), "child");
        final StoragePath logicalTree = new StoragePath(StorageRoot.DATA, "tree");

        final var result = backend.delete(logicalTree, true);

        assertFalse(result.changed());
        assertEquals(
            StorageErrorCode.SIZE_LIMIT_EXCEEDED,
            result.error().orElseThrow().code()
        );
        assertEquals(logicalTree, result.error().orElseThrow().path());
        assertTrue(Files.exists(tree.resolve("child.txt")));
        assertTrue(Files.isDirectory(tree));
    }

    @Test
    void entryLimitAfterDeletionReportsPartialAtTheEnumeratedDirectory() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(2, 3, 100));
        final Path tree = temporary.resolve("data/tree");
        Files.createDirectories(tree.resolve("z-nested"));
        Files.writeString(tree.resolve("a-first.txt"), "first");
        Files.writeString(tree.resolve("z-nested/leaf.txt"), "leaf");

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "tree"),
            true
        );

        assertTrue(result.changed());
        assertEquals(StorageErrorCode.PARTIAL_DELETE, result.error().orElseThrow().code());
        assertEquals(
            new StoragePath(StorageRoot.DATA, "tree/z-nested"),
            result.error().orElseThrow().path()
        );
        assertFalse(Files.exists(tree.resolve("a-first.txt")));
        assertTrue(Files.exists(tree.resolve("z-nested/leaf.txt")));
        assertTrue(Files.isDirectory(tree));
    }

    @Test
    void entryLimitBelowTheRootFailsWithoutDeleting() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(0, 0, 2));
        final Path target = temporary.resolve("data/target.txt");
        Files.writeString(target, "target");
        final StoragePath logicalTarget = new StoragePath(StorageRoot.DATA, "target.txt");

        final var result = backend.delete(logicalTarget, true);

        assertFalse(result.changed());
        assertEquals(
            StorageErrorCode.SIZE_LIMIT_EXCEEDED,
            result.error().orElseThrow().code()
        );
        assertEquals(logicalTarget, result.error().orElseThrow().path());
        assertTrue(Files.exists(target));
    }

    @Test
    void workLimitTwoExactlyDeletesOneFile() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(0, 1, 2));
        final Path target = temporary.resolve("data/target.txt");
        Files.writeString(target, "target");

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "target.txt"),
            true
        );

        assertTrue(result.changed());
        assertTrue(result.error().isEmpty());
        assertFalse(Files.exists(target));
    }

    @Test
    void workLimitSixExactlyDeletesADirectoryAndOneChild() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(1, 2, 6));
        final Path tree = temporary.resolve("data/tree");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("child.txt"), "child");

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "tree"),
            true
        );

        assertTrue(result.changed());
        assertTrue(result.error().isEmpty());
        assertFalse(Files.exists(tree));
    }

    @Test
    void workLimitFourStopsBeforeDeletingTheChildWithoutChangingTheTree()
        throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(1, 2, 4));
        final Path tree = temporary.resolve("data/tree");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("child.txt"), "child");
        final StoragePath logicalChild = new StoragePath(
            StorageRoot.DATA,
            "tree/child.txt"
        );

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "tree"),
            true
        );

        assertFalse(result.changed());
        assertEquals(
            StorageErrorCode.SIZE_LIMIT_EXCEEDED,
            result.error().orElseThrow().code()
        );
        assertEquals(logicalChild, result.error().orElseThrow().path());
        assertTrue(Files.exists(tree.resolve("child.txt")));
        assertTrue(Files.isDirectory(tree));
    }

    @Test
    void workLimitFiveStopsBeforeDeletingTheRootAfterDeletingItsChild() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(1, 2, 5));
        final Path tree = temporary.resolve("data/tree");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("child.txt"), "child");
        final StoragePath logicalTree = new StoragePath(StorageRoot.DATA, "tree");

        final var result = backend.delete(logicalTree, true);

        assertTrue(result.changed());
        assertEquals(StorageErrorCode.PARTIAL_DELETE, result.error().orElseThrow().code());
        assertEquals(logicalTree, result.error().orElseThrow().path());
        assertTrue(Files.isDirectory(tree));
        try (var children = Files.list(tree)) {
            assertEquals(0L, children.count());
        }
    }

    @Test
    void workLimitOneFailsAtTheDirectoryDuringChildDiscoveryWithoutDeleting()
        throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(1, 2, 1));
        final Path tree = temporary.resolve("data/tree");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("child.txt"), "child");
        final StoragePath logicalTree = new StoragePath(StorageRoot.DATA, "tree");

        final var result = backend.delete(logicalTree, true);

        assertFalse(result.changed());
        assertEquals(
            StorageErrorCode.SIZE_LIMIT_EXCEEDED,
            result.error().orElseThrow().code()
        );
        assertEquals(logicalTree, result.error().orElseThrow().path());
        assertTrue(Files.exists(tree.resolve("child.txt")));
        assertTrue(Files.isDirectory(tree));
    }

    @Test
    void workLimitThreeFailsAtTheChildEnterWithoutDeleting() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(1, 2, 3));
        final Path tree = temporary.resolve("data/tree");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("child.txt"), "child");
        final StoragePath logicalChild = new StoragePath(
            StorageRoot.DATA,
            "tree/child.txt"
        );

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "tree"),
            true
        );

        assertFalse(result.changed());
        assertEquals(
            StorageErrorCode.SIZE_LIMIT_EXCEEDED,
            result.error().orElseThrow().code()
        );
        assertEquals(logicalChild, result.error().orElseThrow().path());
        assertTrue(Files.exists(tree.resolve("child.txt")));
        assertTrue(Files.isDirectory(tree));
    }

    @Test
    void workLimitOneFailsBeforeAFileDeleteAttempt() throws Exception {
        final ConfinedStorageBackend backend = backend(new DeleteLimits(0, 1, 1));
        final Path target = temporary.resolve("data/target.txt");
        Files.writeString(target, "target");
        final StoragePath logicalTarget = new StoragePath(StorageRoot.DATA, "target.txt");

        final var result = backend.delete(logicalTarget, true);

        assertFalse(result.changed());
        assertEquals(
            StorageErrorCode.SIZE_LIMIT_EXCEEDED,
            result.error().orElseThrow().code()
        );
        assertEquals(logicalTarget, result.error().orElseThrow().path());
        assertTrue(Files.exists(target));
    }

    @Test
    void deleteLimitDefaultsAreFrozenAndOverridesCanOnlyTighten() {
        assertEquals(64, DeleteLimits.DEFAULT_MAX_DEPTH);
        assertEquals(10_000L, DeleteLimits.DEFAULT_MAX_ENTRIES);
        assertEquals(100_000L, DeleteLimits.DEFAULT_MAX_WORK);
        assertEquals(
            new DeleteLimits(64, 10_000L, 100_000L),
            DeleteLimits.defaults()
        );
        assertEquals(
            "maxDepth must be between 0 and 64",
            assertThrows(
                IllegalArgumentException.class,
                () -> new DeleteLimits(65, 10_000L, 100_000L)
            ).getMessage()
        );
        assertEquals(
            "maxEntries must be between 0 and 10000",
            assertThrows(
                IllegalArgumentException.class,
                () -> new DeleteLimits(64, 10_001L, 100_000L)
            ).getMessage()
        );
        assertEquals(
            "maxWork must be between 0 and 100000",
            assertThrows(
                IllegalArgumentException.class,
                () -> new DeleteLimits(64, 10_000L, 100_001L)
            ).getMessage()
        );
        assertEquals(
            "maxDepth must be between 0 and 64",
            assertThrows(
                IllegalArgumentException.class,
                () -> new DeleteLimits(-1, 10_000L, 100_000L)
            ).getMessage()
        );
        assertEquals(
            "maxEntries must be between 0 and 10000",
            assertThrows(
                IllegalArgumentException.class,
                () -> new DeleteLimits(64, -1L, 100_000L)
            ).getMessage()
        );
        assertEquals(
            "maxWork must be between 0 and 100000",
            assertThrows(
                IllegalArgumentException.class,
                () -> new DeleteLimits(64, 10_000L, -1L)
            ).getMessage()
        );
    }

    @Test
    void symlinkDiscoveredBeforeAnyDeletionFailsAtTheSymlink() throws Exception {
        final ConfinedStorageBackend backend = backend(DeleteLimits.defaults());
        final Path tree = temporary.resolve("data/tree");
        final Path outside = temporary.resolve("outside.txt");
        Files.createDirectories(tree);
        Files.writeString(outside, "outside");
        Files.createSymbolicLink(tree.resolve("link.txt"), outside);

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "tree"),
            true
        );

        assertFalse(result.changed());
        assertEquals(StorageErrorCode.LINK_ESCAPE, result.error().orElseThrow().code());
        assertEquals(
            new StoragePath(StorageRoot.DATA, "tree/link.txt"),
            result.error().orElseThrow().path()
        );
        assertTrue(Files.isSymbolicLink(tree.resolve("link.txt")));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void symlinkDiscoveredAfterEarlierSiblingDeletionReportsPartialAtTheSymlink()
        throws Exception {
        final ConfinedStorageBackend backend = backend(DeleteLimits.defaults());
        final Path tree = temporary.resolve("data/tree");
        final Path nested = tree.resolve("z-nested");
        final Path outside = temporary.resolve("outside.txt");
        Files.createDirectories(nested);
        Files.writeString(tree.resolve("a-first.txt"), "first");
        Files.writeString(outside, "outside");
        Files.createSymbolicLink(nested.resolve("link.txt"), outside);

        final var result = backend.delete(
            new StoragePath(StorageRoot.DATA, "tree"),
            true
        );

        assertTrue(result.changed());
        assertEquals(StorageErrorCode.PARTIAL_DELETE, result.error().orElseThrow().code());
        assertEquals(
            new StoragePath(StorageRoot.DATA, "tree/z-nested/link.txt"),
            result.error().orElseThrow().path()
        );
        assertFalse(Files.exists(tree.resolve("a-first.txt")));
        assertTrue(Files.isSymbolicLink(nested.resolve("link.txt")));
        assertEquals("outside", Files.readString(outside));
    }

    private ConfinedStorageBackend backend(final DeleteLimits limits) throws Exception {
        return new ConfinedStorageBackend(
            Map.of(
                StorageRoot.DATA, temporary.resolve("data"),
                StorageRoot.STATE, temporary.resolve("state"),
                StorageRoot.CACHE, temporary.resolve("cache")
            ),
            limits
        );
    }
}
