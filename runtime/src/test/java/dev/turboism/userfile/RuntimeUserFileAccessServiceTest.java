package dev.turboism.userfile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.UserFileErrorCode;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileHandleState;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestStatus;
import dev.turboism.task.RuntimePluginTaskScheduler;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeUserFileAccessServiceTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.user-file-test";

    @TempDir
    Path temporary;

    private DisposableScope scope;
    private RuntimeScheduler runtimeScheduler;
    private RuntimePluginTaskScheduler tasks;

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
    void oneOperationReadIsBoundedConsumedAndLateContinuationUsesPluginExecutor() throws Exception {
        final Path selected = temporary.resolve("input.csv");
        Files.writeString(selected, "hello");
        final RuntimeUserFileAccessService service =
                service(permissions(UserFileMode.READ), UserFileGrantSource.fixedSelection(selected));
        final UserFileHandle handle = service.request(request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();

        final var stage = service.readUtf8(handle, 3);
        final var result = stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals("hel", result.value().orElseThrow());
        assertTrue(result.truncated());
        assertEquals(UserFileHandleState.CLOSED, handle.state());

        final AtomicReference<String> continuationThread = new AtomicReference<>();
        final CountDownLatch continuationRan = new CountDownLatch(1);
        stage.thenRun(() -> {
            continuationThread.set(Thread.currentThread().getName());
            continuationRan.countDown();
        });
        assertTrue(continuationRan.await(1, TimeUnit.SECONDS));
        assertTrue(continuationThread.get().contains("plugin.user-file-test"));

        final var expired = service.readUtf8(handle, 16).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.GRANT_EXPIRED, expired.error().orElseThrow().code());
    }

    @Test
    void untilDisableWriteUsesAtomicSameTargetAndDefensiveByteSnapshot() throws Exception {
        final Path selected = temporary.resolve("output.csv");
        Files.writeString(selected, "old");
        final RuntimeUserFileAccessService service =
                service(allFilePermissions(), UserFileGrantSource.fixedSelection(selected));
        final UserFileHandle handle = service.request(request(UserFileMode.WRITE, UserFileLifetime.UNTIL_DISABLE))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();

        final byte[] bytes = {1, 2, 3};
        final var write = service.writeBytesAtomic(handle, bytes);
        bytes[0] = 9;
        assertTrue(write.toCompletableFuture().get(2, TimeUnit.SECONDS).written());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(selected));
        assertEquals(UserFileHandleState.ACTIVE, handle.state());

        assertTrue(service.writeUtf8Atomic(handle, "second")
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .written());
        assertEquals("second", Files.readString(selected));

        final var mismatch = service.readUtf8(handle, 16).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.MODE_MISMATCH, mismatch.error().orElseThrow().code());
        assertEquals(UserFileHandleState.ACTIVE, handle.state());
    }

    @Test
    void permissionCanceledAndUnavailableRequestsUseExactResultAlgebra() throws Exception {
        RuntimeUserFileAccessService denied = service(
                Set.of(PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST),
                UserFileGrantSource.fixedSelection(temporary.resolve("denied.csv")));
        var deniedResult = denied.request(request(UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        assertEquals(UserFileRequestStatus.DENIED, deniedResult.status());
        assertEquals(
                UserFileErrorCode.PERMISSION_DENIED,
                deniedResult.error().orElseThrow().code());

        final RuntimeUserFileAccessService canceled =
                service(permissions(UserFileMode.READ), UserFileGrantSource.canceled());
        assertEquals(
                UserFileRequestStatus.CANCELED,
                canceled.request(request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION))
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS)
                        .status());

        final RuntimeUserFileAccessService unavailable =
                service(permissions(UserFileMode.READ), UserFileGrantSource.unavailable());
        final var unavailableResult = unavailable
                .request(request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        assertEquals(UserFileRequestStatus.UNAVAILABLE, unavailableResult.status());
        assertEquals(
                UserFileErrorCode.RUNTIME_UNAVAILABLE,
                unavailableResult.error().orElseThrow().code());
    }

    @Test
    void userFileFailuresAreCollectedOnceWithoutExposingGrantPaths() throws Exception {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        final RuntimeUserFileAccessService denied = service(
                Set.of(PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST),
                UserFileGrantSource.fixedSelection(temporary.resolve("C:/Users/private/denied.csv")),
                failures);

        final var result = denied.request(request(UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);

        assertEquals(UserFileRequestStatus.DENIED, result.status());
        final var collected = failures.snapshot().storageFailures();
        assertEquals(1, collected.size());
        assertEquals("PERMISSION_DENIED", collected.get(0).code());
        assertEquals("user-file.request", collected.get(0).operationId());
        assertEquals(PermissionIds.TURBOISM_FILE_WRITE, collected.get(0).permissionId());
        assertEquals(null, collected.get(0).relativePath());
        assertFalse(collected.get(0).message().contains("Users"));
    }

    @Test
    void forgedForeignRevokedAndModeMismatchGrantsRemainDistinct() throws Exception {
        final Path selected = temporary.resolve("input.csv");
        Files.writeString(selected, "value");
        final RuntimeUserFileAccessService first =
                service(allFilePermissions(), UserFileGrantSource.fixedSelection(selected));
        final RuntimeUserFileAccessService second = new RuntimeUserFileAccessService(
                "dev.turboism.plugin.other",
                permissions(UserFileMode.READ),
                UserFileGrantSource.fixedSelection(selected),
                tasks,
                scope);
        final UserFileHandle firstHandle = first.request(request(UserFileMode.READ, UserFileLifetime.UNTIL_DISABLE))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();

        assertEquals(
                UserFileErrorCode.FOREIGN_GRANT,
                second.readUtf8(firstHandle, 16)
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS)
                        .error()
                        .orElseThrow()
                        .code());
        assertEquals(
                UserFileErrorCode.INVALID_GRANT,
                first.readUtf8(new ForgedHandle(), 16)
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS)
                        .error()
                        .orElseThrow()
                        .code());

        firstHandle.revoke();
        assertEquals(UserFileHandleState.REVOKED, firstHandle.state());
        assertEquals(
                UserFileErrorCode.GRANT_REVOKED,
                first.readUtf8(firstHandle, 16)
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS)
                        .error()
                        .orElseThrow()
                        .code());

        final UserFileHandle oneOperationRead = first.request(
                        request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();
        final var mismatch = first.writeUtf8Atomic(oneOperationRead, "bad")
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.MODE_MISMATCH, mismatch.error().orElseThrow().code());
        assertEquals(
                UserFileHandleState.ACTIVE,
                oneOperationRead.state(),
                "a rejected call must not consume a one-operation grant");
        assertEquals(
                "value",
                first.readUtf8(oneOperationRead, 16)
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS)
                        .value()
                        .orElseThrow(),
                "the grant must still serve a matching call after a mode-mismatched one");
        assertEquals(UserFileHandleState.CLOSED, oneOperationRead.state());
    }

    @Test
    void closeRevokesGrantsAndSettlesPendingRequestBeforeSchedulerShutdown() throws Exception {
        final Path selected = temporary.resolve("input.csv");
        Files.writeString(selected, "value");
        final RuntimeUserFileAccessService grantService =
                service(permissions(UserFileMode.READ), UserFileGrantSource.fixedSelection(selected));
        final UserFileHandle handle = grantService
                .request(request(UserFileMode.READ, UserFileLifetime.UNTIL_DISABLE))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();

        final CompletableFuture<UserFileGrantSource.Decision> pending = new CompletableFuture<>();
        final RuntimeUserFileAccessService pendingService = service(permissions(UserFileMode.READ), request -> pending);
        final var pendingResult = pendingService.request(request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION));

        grantService.close();
        assertEquals(UserFileHandleState.REVOKED, handle.state());
        assertEquals(
                UserFileErrorCode.GRANT_REVOKED,
                grantService
                        .readUtf8(handle, 16)
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS)
                        .error()
                        .orElseThrow()
                        .code());

        pendingService.close();
        assertEquals(
                UserFileRequestStatus.UNAVAILABLE,
                pendingResult.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        pending.complete(UserFileGrantSource.Decision.selected(selected));
        assertFalse(runtimeScheduler.isClosed());
    }

    @Test
    void oneOperationGrantSurvivesPermissionDeniedCallsAndStillServesAMatchingOne() throws Exception {
        final Path selected = temporary.resolve("input.csv");
        Files.writeString(selected, "value");
        final RuntimeUserFileAccessService service =
                service(permissions(UserFileMode.READ), UserFileGrantSource.fixedSelection(selected));
        final UserFileHandle handle = service.request(request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();

        final var denied = service.writeUtf8Atomic(handle, "not-written")
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.PERMISSION_DENIED,
                denied.error().orElseThrow().code());
        assertEquals(
                UserFileHandleState.ACTIVE,
                handle.state(),
                "a permission-denied call must not consume a one-operation grant");
        assertEquals("value", Files.readString(selected));

        assertEquals(
                "value",
                service.readUtf8(handle, 16)
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS)
                        .value()
                        .orElseThrow(),
                "the grant must still serve an authorized call after a denied one");
        assertEquals(UserFileHandleState.CLOSED, handle.state());
    }

    @Test
    void binaryImportBudgetReadsSmallFilesAndPreservesExactLimit() throws Exception {
        final int limit = 16 * 1024 * 1024;
        final Path selected = temporary.resolve("input.csv");
        final byte[] header = {0x67, 0x6c, 0x54, 0x46};
        Files.write(selected, header);
        final RuntimeUserFileAccessService service =
                service(permissions(UserFileMode.READ), UserFileGrantSource.fixedSelection(selected));
        final UserFileHandle handle = service.request(request(UserFileMode.READ, UserFileLifetime.UNTIL_DISABLE))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();
        final var small = service.readBytes(handle, limit).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(small.error().isEmpty(), "16 MiB request must accept a small authorized GLB");
        assertArrayEquals(header, small.value().orElseThrow());
        assertFalse(small.truncated());
        final byte[] exact = new byte[limit];
        exact[0] = 7;
        exact[limit - 1] = 11;
        Files.write(selected, exact);
        final var full = service.readBytes(handle, limit).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertArrayEquals(exact, full.value().orElseThrow());
        assertFalse(full.truncated());
        Files.write(selected, new byte[] {13}, java.nio.file.StandardOpenOption.APPEND);
        final var overflow =
                service.readBytes(handle, limit).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(overflow.truncated());
        assertArrayEquals(exact, overflow.value().orElseThrow());
        final var text =
                service.readUtf8(handle, 9 * 1024 * 1024).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.SIZE_LIMIT_EXCEEDED,
                text.error().orElseThrow().code());
    }

    @Test
    void oversizedAndZeroByteReadsAreStructured() throws Exception {
        final Path selected = temporary.resolve("input.csv");
        Files.writeString(selected, "x");
        final RuntimeUserFileAccessService service =
                service(permissions(UserFileMode.READ), UserFileGrantSource.fixedSelection(selected));
        final UserFileHandle zero = service.request(request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();
        final var zeroResult = service.readBytes(zero, 0).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(0, zeroResult.value().orElseThrow().length);
        assertTrue(zeroResult.truncated());

        final UserFileHandle oversized = service.request(request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();
        final var oversizedResult = service.readBytes(oversized, 17 * 1024 * 1024)
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.SIZE_LIMIT_EXCEEDED,
                oversizedResult.error().orElseThrow().code());
        assertEquals(UserFileHandleState.CLOSED, oversized.state());
    }

    @Test
    void boundedAtomicExportWritesAboveLegacyLimitAndPreservesSnapshotAndBudget() throws Exception {
        var selected = temporary.resolve("export.csv");
        Files.writeString(selected, "original");
        var service = service(allFilePermissions(), UserFileGrantSource.fixedSelection(selected));
        var handle = service.request(request(UserFileMode.WRITE, UserFileLifetime.UNTIL_DISABLE))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();
        byte[] content = new byte[9 * 1024 * 1024];
        content[0] = 42;
        content[content.length - 1] = 99;
        assertEquals(
                UserFileErrorCode.SIZE_LIMIT_EXCEEDED,
                service.writeBytesAtomic(handle, content)
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS)
                        .error()
                        .orElseThrow()
                        .code());
        assertEquals("original", Files.readString(selected));
        var stage = service.writeBytesAtomicBounded(handle, content, content.length);
        content[0] = 1;
        assertTrue(stage.toCompletableFuture().get(5, TimeUnit.SECONDS).written());
        var actual = Files.readAllBytes(selected);
        assertEquals(42, actual[0]);
        assertEquals(99, actual[actual.length - 1]);
        assertEquals(
                UserFileErrorCode.SIZE_LIMIT_EXCEEDED,
                service.writeBytesAtomicBounded(handle, new byte[2], 1)
                        .toCompletableFuture()
                        .get()
                        .error()
                        .orElseThrow()
                        .code());
        assertEquals(
                UserFileErrorCode.SIZE_LIMIT_EXCEEDED,
                service.writeBytesAtomicBounded(handle, new byte[0], 128 * 1024 * 1024 + 1)
                        .toCompletableFuture()
                        .get()
                        .error()
                        .orElseThrow()
                        .code());
        assertArrayEquals(actual, Files.readAllBytes(selected));
        assertThrows(IllegalArgumentException.class, () -> service.writeBytesAtomicBounded(handle, new byte[0], 0));
        handle.revoke();
        assertEquals(
                UserFileErrorCode.GRANT_REVOKED,
                service.writeBytesAtomicBounded(handle, new byte[] {0}, 1)
                        .toCompletableFuture()
                        .get()
                        .error()
                        .orElseThrow()
                        .code());
        assertArrayEquals(actual, Files.readAllBytes(selected));
    }

    @Test
    void boundedAtomicExportMaximumBudgetAndFailureAuditStayPathFree() throws Exception {
        var selected = temporary.resolve("private-export.csv");
        Files.writeString(selected, "old");
        var failures = new RuntimeFailureCollector();
        var service = service(allFilePermissions(), UserFileGrantSource.fixedSelection(selected), failures);
        var handle = service.request(request(UserFileMode.WRITE, UserFileLifetime.UNTIL_DISABLE))
                .toCompletableFuture()
                .get()
                .handle()
                .orElseThrow();
        assertTrue(service.writeBytesAtomicBounded(handle, new byte[] {7}, 128 * 1024 * 1024)
                .toCompletableFuture()
                .get()
                .written());
        var rejected = service.writeBytesAtomicBounded(handle, new byte[] {8, 9}, 1)
                .toCompletableFuture()
                .get();
        assertFalse(rejected.written());
        assertArrayEquals(new byte[] {7}, Files.readAllBytes(selected));
        var records = failures.snapshot().storageFailures();
        assertEquals(1, records.size());
        assertEquals("user-file.writeBytesAtomicBounded", records.get(0).operationId());
        assertEquals("SIZE_LIMIT_EXCEEDED", records.get(0).code());
        assertEquals(null, records.get(0).relativePath());
        assertFalse(records.get(0).message().contains("private-export"));
    }

    @Test
    void boundedAtomicExportRejectsForeignReadOnlyAndSpentHandles() throws Exception {
        var selected = temporary.resolve("bounded.csv");
        Files.writeString(selected, "unchanged");
        var service = service(allFilePermissions(), UserFileGrantSource.fixedSelection(selected));
        var read = service.request(request(UserFileMode.READ, UserFileLifetime.UNTIL_DISABLE))
                .toCompletableFuture()
                .get()
                .handle()
                .orElseThrow();
        assertEquals(
                UserFileErrorCode.MODE_MISMATCH,
                service.writeBytesAtomicBounded(read, new byte[] {1}, 10)
                        .toCompletableFuture()
                        .get()
                        .error()
                        .orElseThrow()
                        .code());
        var foreign = service(allFilePermissions(), UserFileGrantSource.fixedSelection(selected));
        var write = service.request(request(UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get()
                .handle()
                .orElseThrow();
        assertEquals(
                UserFileErrorCode.FOREIGN_GRANT,
                foreign.writeBytesAtomicBounded(write, new byte[] {1}, 10)
                        .toCompletableFuture()
                        .get()
                        .error()
                        .orElseThrow()
                        .code());
        assertTrue(service.writeBytesAtomicBounded(write, new byte[] {2}, 10)
                .toCompletableFuture()
                .get()
                .written());
        assertFalse(service.writeBytesAtomicBounded(write, new byte[] {3}, 10)
                .toCompletableFuture()
                .get()
                .written());
        assertArrayEquals(new byte[] {2}, Files.readAllBytes(selected));
    }

    @Test
    void boundedBinaryImportSupportsUpTo128MibAndPreservesOld16MibLimit() throws Exception {
        final int size18Mib = 18 * 1024 * 1024;
        final byte[] largeContent = new byte[size18Mib];
        largeContent[0] = 42;
        largeContent[size18Mib - 1] = 99;
        final Path selected = temporary.resolve("large.csv");
        Files.write(selected, largeContent);

        final RuntimeUserFileAccessService service =
                service(permissions(UserFileMode.READ), UserFileGrantSource.fixedSelection(selected));
        final UserFileHandle handle = service.request(request(UserFileMode.READ, UserFileLifetime.UNTIL_DISABLE))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();

        // Old readBytes rejects 18 MiB request (> 16 MiB limit)
        final var oldRejection =
                service.readBytes(handle, size18Mib).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.SIZE_LIMIT_EXCEEDED,
                oldRejection.error().orElseThrow().code());

        // New readBytesBounded successfully reads 18 MiB
        final var boundedSuccess = service.readBytesBounded(handle, size18Mib)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertTrue(boundedSuccess.error().isEmpty());
        final byte[] readBack = boundedSuccess.value().orElseThrow();
        assertEquals(size18Mib, readBack.length);
        assertEquals((byte) 42, readBack[0]);
        assertEquals((byte) 99, readBack[size18Mib - 1]);
        assertFalse(boundedSuccess.truncated());

        // readBytesBounded rejects requests > 128 MiB
        final var overLimit = service.readBytesBounded(handle, 128 * 1024 * 1024 + 1)
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.SIZE_LIMIT_EXCEEDED,
                overLimit.error().orElseThrow().code());

        // readBytesBounded rejects negative maxBytes with IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> service.readBytesBounded(handle, -1));
    }

    @Test
    void readBytesBoundedTruncationAndBoundaryChecks() throws Exception {
        final Path selected = temporary.resolve("sample.csv");
        final byte[] content = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        Files.write(selected, content);

        final RuntimeUserFileAccessService service =
                service(permissions(UserFileMode.READ), UserFileGrantSource.fixedSelection(selected));
        final UserFileHandle handle = service.request(request(UserFileMode.READ, UserFileLifetime.UNTIL_DISABLE))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();

        // Truncated when maxBytes < file length
        final var truncatedResult =
                service.readBytesBounded(handle, 4).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(truncatedResult.truncated());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, truncatedResult.value().orElseThrow());

        // Exact file length: not truncated
        final var exactResult =
                service.readBytesBounded(handle, 10).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertFalse(exactResult.truncated());
        assertArrayEquals(content, exactResult.value().orElseThrow());

        // Oversized maxBytes: returns full content without truncation
        final var largerResult =
                service.readBytesBounded(handle, 50).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertFalse(largerResult.truncated());
        assertArrayEquals(content, largerResult.value().orElseThrow());

        // 0 maxBytes: returns empty array with truncated=true
        final var zeroResult =
                service.readBytesBounded(handle, 0).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(zeroResult.truncated());
        assertEquals(0, zeroResult.value().orElseThrow().length);
    }

    @Test
    void readBytesBoundedOneOperationHandleLifecycleAndExpiration() throws Exception {
        final Path selected = temporary.resolve("once.csv");
        Files.write(selected, new byte[] {1, 2, 3});

        final RuntimeUserFileAccessService service =
                service(permissions(UserFileMode.READ), UserFileGrantSource.fixedSelection(selected));
        final UserFileHandle handle = service.request(request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();

        final var firstRead =
                service.readBytesBounded(handle, 10).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertArrayEquals(new byte[] {1, 2, 3}, firstRead.value().orElseThrow());
        assertEquals(UserFileHandleState.CLOSED, handle.state());

        // Expired grant rejected by readBytesBounded
        final var expiredBounded =
                service.readBytesBounded(handle, 10).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.GRANT_EXPIRED,
                expiredBounded.error().orElseThrow().code());

        // Expired grant rejected by readBytes and readUtf8
        final var expiredLegacy =
                service.readBytes(handle, 10).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.GRANT_EXPIRED,
                expiredLegacy.error().orElseThrow().code());

        final var expiredUtf8 =
                service.readUtf8(handle, 10).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.GRANT_EXPIRED,
                expiredUtf8.error().orElseThrow().code());

        // Oversized read (> 128 MiB) on ONE_OPERATION handle consumes the grant
        final UserFileHandle oversizedHandle = service.request(
                        request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();
        final var oversizedResult = service.readBytesBounded(oversizedHandle, 129 * 1024 * 1024)
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.SIZE_LIMIT_EXCEEDED,
                oversizedResult.error().orElseThrow().code());
        assertEquals(UserFileHandleState.CLOSED, oversizedHandle.state());
    }

    @Test
    void readBytesBoundedSecurityChecksAndNegativeGrants() throws Exception {
        final Path selected = temporary.resolve("security.csv");
        Files.write(selected, new byte[] {10, 20});

        final RuntimeUserFileAccessService service =
                service(allFilePermissions(), UserFileGrantSource.fixedSelection(selected));
        final RuntimeUserFileAccessService secondService = new RuntimeUserFileAccessService(
                "dev.turboism.plugin.other",
                permissions(UserFileMode.READ),
                UserFileGrantSource.fixedSelection(selected),
                tasks,
                scope);

        // Forged handle rejected
        final var forged = service.readBytesBounded(new ForgedHandle(), 16)
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.INVALID_GRANT, forged.error().orElseThrow().code());

        // Cross-plugin authorization (foreign grant) rejected
        final UserFileHandle handle = service.request(request(UserFileMode.READ, UserFileLifetime.UNTIL_DISABLE))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();
        final var foreign =
                secondService.readBytesBounded(handle, 16).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.FOREIGN_GRANT, foreign.error().orElseThrow().code());

        // Mode mismatch: write handle rejected by readBytesBounded and remains active
        final UserFileHandle writeHandle = service.request(request(UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();
        final var mismatch =
                service.readBytesBounded(writeHandle, 16).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.MODE_MISMATCH, mismatch.error().orElseThrow().code());
        assertEquals(
                UserFileHandleState.ACTIVE, writeHandle.state(), "mode mismatch must not consume ONE_OPERATION grant");

        // Revoked handle rejected
        handle.revoke();
        assertEquals(UserFileHandleState.REVOKED, handle.state());
        final var revoked =
                service.readBytesBounded(handle, 16).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.GRANT_REVOKED, revoked.error().orElseThrow().code());

        // Service close revokes grants
        final UserFileHandle closeHandle = service.request(request(UserFileMode.READ, UserFileLifetime.UNTIL_DISABLE))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();
        service.close();
        assertEquals(UserFileHandleState.REVOKED, closeHandle.state());
        final var closedResult =
                service.readBytesBounded(closeHandle, 16).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.GRANT_REVOKED,
                closedResult.error().orElseThrow().code());
    }

    @Test
    void readBytesBoundedRecordsFailureSinkWithoutExposingPaths() throws Exception {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        final Path subDir = temporary.resolve("SecretDir");
        Files.createDirectories(subDir);
        final Path selected = subDir.resolve("private.csv");
        Files.write(selected, new byte[] {1, 2});
        final RuntimeUserFileAccessService service =
                service(permissions(UserFileMode.READ), UserFileGrantSource.fixedSelection(selected), failures);

        final UserFileHandle handle = service.request(request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS)
                .handle()
                .orElseThrow();

        final var overLimit = service.readBytesBounded(handle, 130 * 1024 * 1024)
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        assertEquals(
                UserFileErrorCode.SIZE_LIMIT_EXCEEDED,
                overLimit.error().orElseThrow().code());

        final var collected = failures.snapshot().storageFailures();
        assertEquals(1, collected.size());
        assertEquals("SIZE_LIMIT_EXCEEDED", collected.get(0).code());
        assertEquals("user-file.readBytesBounded", collected.get(0).operationId());
        assertEquals(null, collected.get(0).relativePath());
        assertFalse(collected.get(0).message().contains("SecretDir"));
    }

    @Test
    void closeClosesOwnedGrantSourceAndFencesLateSelection() throws Exception {
        final Path selected = temporary.resolve("late.csv");
        Files.writeString(selected, "value");
        final class CloseTrackingSource implements UserFileGrantSource, AutoCloseable {
            private final CompletableFuture<UserFileGrantSource.Decision> pending = new CompletableFuture<>();
            private final AtomicInteger closeCalls = new AtomicInteger();

            @Override
            public CompletionStage<UserFileGrantSource.Decision> request(final UserFileRequest request) {
                return pending;
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        }

        final CloseTrackingSource source = new CloseTrackingSource();
        final RuntimeUserFileAccessService service = service(permissions(UserFileMode.READ), source);
        final var result = service.request(request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION));

        service.close();

        assertEquals(1, source.closeCalls.get());
        assertEquals(
                UserFileRequestStatus.UNAVAILABLE,
                result.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        source.pending.complete(UserFileGrantSource.Decision.selected(selected));
        assertEquals(
                UserFileRequestStatus.UNAVAILABLE,
                result.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
    }

    @Test
    void sourceCloseFailureIsReportedAndIoExecutorStillCloses() throws Exception {
        final class FailingSource implements UserFileGrantSource, AutoCloseable {
            private final AtomicInteger closeCalls = new AtomicInteger();

            @Override
            public CompletionStage<UserFileGrantSource.Decision> request(final UserFileRequest request) {
                return CompletableFuture.completedFuture(UserFileGrantSource.Decision.unavailable());
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
                throw new IllegalStateException("C:/Users/private/source-close");
            }
        }

        final FailingSource source = new FailingSource();
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final RuntimeUserFileAccessService service =
                service(permissions(UserFileMode.READ), source, failures, evidence);

        service.close();

        assertEquals(1, source.closeCalls.get());
        assertTrue(ioExecutor(service).isShutdown());
        assertEquals(1, evidence.snapshot().failures());
        final var collected = failures.snapshot().storageFailures();
        assertEquals(1, collected.size());
        assertEquals("USER_FILE_SOURCE_CLOSE_FAILED", collected.get(0).code());
        assertEquals(null, collected.get(0).relativePath());
        assertFalse(collected.get(0).message().contains("C:/Users"));
    }

    @Test
    void registrationFailureTearsDownOwnedSourceWithoutMaskingOriginalException() throws Exception {
        scope = new DisposableScope();
        runtimeScheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 32, ignored -> {}, Clock.systemUTC()),
                SidecarDispatcher.noop(),
                ignored -> {});
        tasks = new RuntimePluginTaskScheduler(PLUGIN_ID, runtimeScheduler, scope);
        scope.close();

        final class FailingSource implements UserFileGrantSource, AutoCloseable {
            private final AtomicInteger closeCalls = new AtomicInteger();

            @Override
            public CompletionStage<UserFileGrantSource.Decision> request(final UserFileRequest request) {
                return CompletableFuture.completedFuture(UserFileGrantSource.Decision.unavailable());
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
                throw new IllegalStateException("source close failed");
            }
        }

        final FailingSource source = new FailingSource();
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final IllegalStateException original = assertThrows(
                IllegalStateException.class,
                () -> new RuntimeUserFileAccessService(
                        PLUGIN_ID, permissions(UserFileMode.READ), source, tasks, scope, evidence, failures));

        assertEquals("DisposableScope is already closed", original.getMessage());
        assertEquals(1, source.closeCalls.get());
        assertEquals(1, original.getSuppressed().length);
        assertEquals("source close failed", original.getSuppressed()[0].getMessage());
        assertEquals(1, evidence.snapshot().failures());
        assertEquals(
                "USER_FILE_SOURCE_CLOSE_FAILED",
                failures.snapshot().storageFailures().get(0).code());
    }

    private RuntimeUserFileAccessService service(final Set<String> permissions, final UserFileGrantSource source) {
        return service(permissions, source, new RuntimeFailureCollector(), new CleanupEvidenceCollector());
    }

    private RuntimeUserFileAccessService service(
            final Set<String> permissions, final UserFileGrantSource source, final RuntimeFailureCollector failures) {
        return service(permissions, source, failures, new CleanupEvidenceCollector());
    }

    private RuntimeUserFileAccessService service(
            final Set<String> permissions,
            final UserFileGrantSource source,
            final RuntimeFailureCollector failures,
            final CleanupEvidenceCollector evidence) {
        if (scope == null) {
            scope = new DisposableScope();
            runtimeScheduler = new RuntimeScheduler(
                    new DefaultWorkBudgetPolicy(),
                    new PluginWorkExecutorRegistry(1, 32, ignored -> {}, Clock.systemUTC()),
                    SidecarDispatcher.noop(),
                    ignored -> {});
            tasks = new RuntimePluginTaskScheduler(PLUGIN_ID, runtimeScheduler, scope);
        }
        return new RuntimeUserFileAccessService(PLUGIN_ID, permissions, source, tasks, scope, evidence, failures);
    }

    private static ThreadPoolExecutor ioExecutor(final RuntimeUserFileAccessService service)
            throws ReflectiveOperationException {
        final Field ioField = RuntimeUserFileAccessService.class.getDeclaredField("io");
        ioField.setAccessible(true);
        final Object io = ioField.get(service);
        final Field executorField = io.getClass().getDeclaredField("executor");
        executorField.setAccessible(true);
        return (ThreadPoolExecutor) executorField.get(io);
    }

    private static Set<String> permissions(final UserFileMode mode) {
        return mode == UserFileMode.READ
                ? Set.of(PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST, PermissionIds.TURBOISM_FILE_READ)
                : Set.of(PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST, PermissionIds.TURBOISM_FILE_WRITE);
    }

    private static Set<String> allFilePermissions() {
        return Set.of(
                PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST,
                PermissionIds.TURBOISM_FILE_READ,
                PermissionIds.TURBOISM_FILE_WRITE);
    }

    private static UserFileRequest request(final UserFileMode mode, final UserFileLifetime lifetime) {
        return new UserFileRequest(
                "request-" + mode.name().toLowerCase(), "Choose CSV", List.of("csv"), mode, lifetime);
    }

    private static final class ForgedHandle implements UserFileHandle {
        @Override
        public String id() {
            return "forged";
        }

        @Override
        public String displayName() {
            return "forged.csv";
        }

        @Override
        public UserFileMode mode() {
            return UserFileMode.READ;
        }

        @Override
        public UserFileLifetime lifetime() {
            return UserFileLifetime.UNTIL_DISABLE;
        }

        @Override
        public UserFileHandleState state() {
            return UserFileHandleState.ACTIVE;
        }

        @Override
        public void revoke() {}

        @Override
        public void close() {}
    }
}
