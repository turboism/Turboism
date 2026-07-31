package dev.turboism.storage;

import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.storage.StorageWriteResult;
import dev.turboism.task.RuntimePluginTaskScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePluginStorageTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.storage";

    @TempDir
    Path temporary;

    private DisposableScope scope;
    private RuntimeScheduler runtimeScheduler;
    private RuntimePluginStorage storage;

    @AfterEach
    void cleanup() throws Exception {
        if (scope != null) {
            scope.close();
        }
        if (runtimeScheduler != null && !runtimeScheduler.isClosed()) {
            runtimeScheduler.shutdown();
        }
    }

    @Test
    void atomicWriteAndBoundedReadCompleteOnPluginExecutor() throws Exception {
        createStorage(Set.of(PermissionIds.TURBOISM_FILE_READ, PermissionIds.TURBOISM_FILE_WRITE));
        final StoragePath path = new StoragePath(StorageRoot.DATA, "nested/message.txt");

        final StorageWriteResult written = storage.writeUtf8Atomic(path, "hello")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(written.written());
        assertEquals("hello", Files.readString(temporary.resolve("data/nested/message.txt")));

        final var full = storage.readUtf8(path, 16).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertEquals("hello", full.value().orElseThrow());
        assertFalse(full.truncated());

        final var boundedStage = storage.readUtf8(path, 3);
        final var bounded = boundedStage.toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals("hel", bounded.value().orElseThrow());
        assertTrue(bounded.truncated());

        final AtomicReference<String> continuationThread = new AtomicReference<>();
        final CountDownLatch continuationRan = new CountDownLatch(1);
        boundedStage.thenRun(() -> {
            continuationThread.set(Thread.currentThread().getName());
            continuationRan.countDown();
        });
        assertTrue(continuationRan.await(1, TimeUnit.SECONDS));
        assertTrue(continuationThread.get().contains("plugin.storage"));
    }

    @Test
    void canonicalParentAliasDoesNotEscapeStorageRoot() throws Exception {
        final Path realHome = Files.createDirectories(temporary.resolve("real-home"));
        final Path alias = temporary.resolve("home-alias");
        Files.createSymbolicLink(alias, realHome);
        final Path state = Files.createDirectories(alias.resolve("state"));
        final ConfinedStorageBackend backend = new ConfinedStorageBackend(Map.of(
            StorageRoot.DATA, Files.createDirectories(realHome.resolve("data")),
            StorageRoot.STATE, state,
            StorageRoot.CACHE, Files.createDirectories(realHome.resolve("cache"))
        ));

        final StoragePath path = new StoragePath(StorageRoot.STATE, "manual-order.txt");
        assertTrue(backend.writeBytesAtomic(path, "saved".getBytes(java.nio.charset.StandardCharsets.UTF_8), true).written());
        assertEquals("saved", Files.readString(realHome.resolve("state/manual-order.txt")));
    }

    @Test
    void missingPermissionsFailAsStructuredResults() throws Exception {
        createStorage(Set.of());
        final StoragePath path = new StoragePath(StorageRoot.STATE, "state.json");

        final var read = storage.readUtf8(path, 16).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertEquals(StorageErrorCode.PERMISSION_DENIED, read.error().orElseThrow().code());

        final var write = storage.writeUtf8Atomic(path, "value").toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertEquals(StorageErrorCode.PERMISSION_DENIED, write.error().orElseThrow().code());
    }

    @Test
    void storageFailuresAreCollectedOnceWithoutExposingStoragePaths() throws Exception {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        createStorage(Set.of(), failures);
        final StoragePath privatePath = new StoragePath(
            StorageRoot.STATE,
            "private/C:/Users/secret/state.json"
        );

        final var result = storage.readUtf8(privatePath, 16).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);

        assertEquals(StorageErrorCode.PERMISSION_DENIED, result.error().orElseThrow().code());
        final var collected = failures.snapshot().storageFailures();
        assertEquals(1, collected.size());
        assertEquals("PERMISSION_DENIED", collected.get(0).code());
        assertEquals("storage.readUtf8", collected.get(0).operationId());
        assertEquals(PermissionIds.TURBOISM_FILE_READ, collected.get(0).permissionId());
        assertEquals(null, collected.get(0).relativePath());
        assertFalse(collected.get(0).message().contains("Users"));
    }

    @Test
    void malformedUtf8AndOperationLimitsAreStructured() throws Exception {
        createStorage(Set.of(PermissionIds.TURBOISM_FILE_READ, PermissionIds.TURBOISM_FILE_WRITE));
        final StoragePath malformed = new StoragePath(StorageRoot.DATA, "malformed.txt");
        Files.write(temporary.resolve("data/malformed.txt"), new byte[] {(byte) 0xC3, 0x28});
        assertEquals(
            StorageErrorCode.IO_FAILURE,
            storage.readUtf8(malformed, 16).toCompletableFuture()
                .get(2, TimeUnit.SECONDS).error().orElseThrow().code()
        );

        final byte[] oversized = new byte[8 * 1024 * 1024 + 1];
        assertEquals(
            StorageErrorCode.SIZE_LIMIT_EXCEEDED,
            storage.writeBytesAtomic(
                new StoragePath(StorageRoot.CACHE, "too-large.bin"),
                oversized
            ).toCompletableFuture().get(2, TimeUnit.SECONDS)
                .error().orElseThrow().code()
        );
        assertFalse(Files.exists(temporary.resolve("cache/too-large.bin")));
    }

    @Test
    void parentAndLeafLinkEscapeAreRejectedWithoutReadingOutsideRoot() throws Exception {
        createStorage(Set.of(PermissionIds.TURBOISM_FILE_READ));
        final Path outside = temporary.resolve("outside.txt");
        Files.writeString(outside, "secret");
        Files.createSymbolicLink(temporary.resolve("data/link.txt"), outside);

        final var leafResult = storage.readUtf8(
            new StoragePath(StorageRoot.DATA, "link.txt"),
            64
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(StorageErrorCode.LINK_ESCAPE, leafResult.error().orElseThrow().code());
        assertTrue(leafResult.value().isEmpty());

        final Path outsideDirectory = temporary.resolve("outside-dir");
        Files.createDirectories(outsideDirectory);
        Files.writeString(outsideDirectory.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(temporary.resolve("data/link-dir"), outsideDirectory);
        final var parentResult = storage.readUtf8(
            new StoragePath(StorageRoot.DATA, "link-dir/secret.txt"),
            64
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(StorageErrorCode.LINK_ESCAPE, parentResult.error().orElseThrow().code());
    }

    @Test
    void listCopyMoveAndDeletePreserveClosedMutationSemantics() throws Exception {
        createStorage(Set.of(PermissionIds.TURBOISM_FILE_READ, PermissionIds.TURBOISM_FILE_WRITE));
        final StoragePath source = new StoragePath(StorageRoot.DATA, "files/source.txt");
        final StoragePath target = new StoragePath(StorageRoot.DATA, "files/target.txt");
        storage.writeUtf8Atomic(source, "new").toCompletableFuture().get(2, TimeUnit.SECONDS);
        storage.writeUtf8Atomic(target, "old").toCompletableFuture().get(2, TimeUnit.SECONDS);
        storage.writeUtf8Atomic(
            new StoragePath(StorageRoot.DATA, "files/third.txt"),
            "third"
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);

        final var list = storage.list(
            new StoragePath(StorageRoot.DATA, "files"),
            2
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(2, list.entries().size());
        assertTrue(list.truncated());
        assertEquals("files/source.txt", list.entries().get(0).path().relativePath());

        final var noReplace = storage.copy(source, target, false)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(StorageErrorCode.ALREADY_EXISTS, noReplace.error().orElseThrow().code());
        assertEquals("old", Files.readString(temporary.resolve("data/files/target.txt")));

        assertTrue(storage.copy(source, target, true)
            .toCompletableFuture().get(2, TimeUnit.SECONDS).changed());
        assertEquals("new", Files.readString(temporary.resolve("data/files/target.txt")));
        try (var files = Files.list(temporary.resolve("data/files"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".turboism-")));
        }

        final var crossRoot = storage.moveAtomic(
            source,
            new StoragePath(StorageRoot.STATE, "moved.txt"),
            false
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
            StorageErrorCode.CROSS_ROOT_ATOMIC_MOVE_UNSUPPORTED,
            crossRoot.error().orElseThrow().code()
        );
        assertTrue(Files.exists(temporary.resolve("data/files/source.txt")));

        final StoragePath moved = new StoragePath(StorageRoot.DATA, "files/moved.txt");
        assertTrue(storage.moveAtomic(source, moved, false)
            .toCompletableFuture().get(2, TimeUnit.SECONDS).changed());
        assertFalse(Files.exists(temporary.resolve("data/files/source.txt")));

        final var nonRecursive = storage.delete(
            new StoragePath(StorageRoot.DATA, "files"),
            false
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(StorageErrorCode.IO_FAILURE, nonRecursive.error().orElseThrow().code());
        assertTrue(storage.delete(
            new StoragePath(StorageRoot.DATA, "files"),
            true
        ).toCompletableFuture().get(2, TimeUnit.SECONDS).changed());
        assertFalse(Files.exists(temporary.resolve("data/files")));
    }

    @Test
    void byteArraysAreDefensivelyCopiedAcrossAsyncBoundary() throws Exception {
        createStorage(Set.of(PermissionIds.TURBOISM_FILE_READ, PermissionIds.TURBOISM_FILE_WRITE));
        final StoragePath path = new StoragePath(StorageRoot.CACHE, "bytes.bin");
        final byte[] input = {1, 2, 3};
        final var write = storage.writeBytesAtomic(path, input);
        input[0] = 9;
        assertTrue(write.toCompletableFuture().get(2, TimeUnit.SECONDS).written());

        final var read = storage.readBytes(path, 16).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        final byte[] first = read.value().orElseThrow();
        assertEquals(1, first[0]);
        first[0] = 8;
        assertEquals(
            1,
            storage.readBytes(path, 16).toCompletableFuture()
                .get(2, TimeUnit.SECONDS).value().orElseThrow()[0]
        );
    }

    @Test
    void zeroBoundsRemainSuccessfulAndOversizedReadIsStructured() throws Exception {
        createStorage(Set.of(PermissionIds.TURBOISM_FILE_READ));
        Files.writeString(temporary.resolve("data/value.txt"), "value");
        Files.createDirectories(temporary.resolve("data/items"));
        Files.writeString(temporary.resolve("data/items/one.txt"), "one");

        final var zeroRead = storage.readBytes(
            new StoragePath(StorageRoot.DATA, "value.txt"),
            0
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(0, zeroRead.value().orElseThrow().length);
        assertTrue(zeroRead.truncated());

        final var zeroList = storage.list(
            new StoragePath(StorageRoot.DATA, "items"),
            0
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(zeroList.entries().isEmpty());
        assertTrue(zeroList.truncated());

        final var oversized = storage.readBytes(
            new StoragePath(StorageRoot.DATA, "value.txt"),
            8 * 1024 * 1024 + 1
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
            StorageErrorCode.SIZE_LIMIT_EXCEEDED,
            oversized.error().orElseThrow().code()
        );
    }

    @Test
    void sameRootAtomicMoveRequiresWriteButNotReadPermission() throws Exception {
        createStorage(Set.of(PermissionIds.TURBOISM_FILE_WRITE));
        Files.createDirectories(temporary.resolve("data/files"));
        Files.writeString(temporary.resolve("data/files/source.txt"), "value");

        final var result = storage.moveAtomic(
            new StoragePath(StorageRoot.DATA, "files/source.txt"),
            new StoragePath(StorageRoot.DATA, "files/target.txt"),
            false
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(result.changed());
        assertFalse(Files.exists(temporary.resolve("data/files/source.txt")));
        assertEquals("value", Files.readString(temporary.resolve("data/files/target.txt")));
    }

    @Test
    void closedStorageRejectsNewOperationsWithoutClosingSharedSchedulerEarly() throws Exception {
        createStorage(Set.of(PermissionIds.TURBOISM_FILE_READ));
        storage.close();

        final var result = storage.readUtf8(
            new StoragePath(StorageRoot.CACHE, "entry"),
            16
        ).toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(StorageErrorCode.RUNTIME_UNAVAILABLE, result.error().orElseThrow().code());
        assertFalse(runtimeScheduler.isClosed());
    }

    private void createStorage(final Set<String> permissions) throws Exception {
        createStorage(permissions, new RuntimeFailureCollector());
    }

    private void createStorage(
        final Set<String> permissions,
        final RuntimeFailureCollector failures
    ) throws Exception {
        Files.createDirectories(temporary.resolve("data"));
        Files.createDirectories(temporary.resolve("state"));
        Files.createDirectories(temporary.resolve("cache"));
        scope = new DisposableScope();
        runtimeScheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(
                1,
                16,
                event -> { },
                Clock.systemUTC()
            ),
            SidecarDispatcher.noop(),
            event -> { }
        );
        final RuntimePluginTaskScheduler taskScheduler = new RuntimePluginTaskScheduler(
            PLUGIN_ID,
            runtimeScheduler,
            scope
        );
        storage = new RuntimePluginStorage(
            PLUGIN_ID,
            Map.of(
                StorageRoot.DATA, temporary.resolve("data"),
                StorageRoot.STATE, temporary.resolve("state"),
                StorageRoot.CACHE, temporary.resolve("cache")
            ),
            permissions,
            taskScheduler,
            scope,
            new dev.turboism.cleanup.CleanupEvidenceCollector(),
            failures
        );
    }
}
