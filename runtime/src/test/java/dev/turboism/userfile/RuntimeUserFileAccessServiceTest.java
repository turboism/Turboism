package dev.turboism.userfile;

import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void oneOperationReadIsBoundedConsumedAndLateContinuationUsesPluginExecutor()
        throws Exception {
        final Path selected = temporary.resolve("input.csv");
        Files.writeString(selected, "hello");
        final RuntimeUserFileAccessService service = service(
            permissions(UserFileMode.READ),
            UserFileGrantSource.fixedSelection(selected)
        );
        final UserFileHandle handle = service.request(request(
            UserFileMode.READ,
            UserFileLifetime.ONE_OPERATION
        )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();

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

        final var expired = service.readUtf8(handle, 16).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertEquals(UserFileErrorCode.GRANT_EXPIRED, expired.error().orElseThrow().code());
    }

    @Test
    void untilDisableWriteUsesAtomicSameTargetAndDefensiveByteSnapshot() throws Exception {
        final Path selected = temporary.resolve("output.csv");
        Files.writeString(selected, "old");
        final RuntimeUserFileAccessService service = service(
            allFilePermissions(),
            UserFileGrantSource.fixedSelection(selected)
        );
        final UserFileHandle handle = service.request(request(
            UserFileMode.WRITE,
            UserFileLifetime.UNTIL_DISABLE
        )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();

        final byte[] bytes = {1, 2, 3};
        final var write = service.writeBytesAtomic(handle, bytes);
        bytes[0] = 9;
        assertTrue(write.toCompletableFuture().get(2, TimeUnit.SECONDS).written());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(selected));
        assertEquals(UserFileHandleState.ACTIVE, handle.state());

        assertTrue(service.writeUtf8Atomic(handle, "second").toCompletableFuture()
            .get(2, TimeUnit.SECONDS).written());
        assertEquals("second", Files.readString(selected));

        final var mismatch = service.readUtf8(handle, 16).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertEquals(UserFileErrorCode.MODE_MISMATCH, mismatch.error().orElseThrow().code());
        assertEquals(UserFileHandleState.ACTIVE, handle.state());
    }

    @Test
    void permissionCanceledAndUnavailableRequestsUseExactResultAlgebra() throws Exception {
        RuntimeUserFileAccessService denied = service(
            Set.of(PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST),
            UserFileGrantSource.fixedSelection(temporary.resolve("denied.csv"))
        );
        var deniedResult = denied.request(request(
            UserFileMode.WRITE,
            UserFileLifetime.ONE_OPERATION
        )).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(UserFileRequestStatus.DENIED, deniedResult.status());
        assertEquals(
            UserFileErrorCode.PERMISSION_DENIED,
            deniedResult.error().orElseThrow().code()
        );

        final RuntimeUserFileAccessService canceled = service(
            permissions(UserFileMode.READ),
            UserFileGrantSource.canceled()
        );
        assertEquals(
            UserFileRequestStatus.CANCELED,
            canceled.request(request(UserFileMode.READ, UserFileLifetime.ONE_OPERATION))
                .toCompletableFuture().get(2, TimeUnit.SECONDS).status()
        );

        final RuntimeUserFileAccessService unavailable = service(
            permissions(UserFileMode.READ),
            UserFileGrantSource.unavailable()
        );
        final var unavailableResult = unavailable.request(request(
            UserFileMode.READ,
            UserFileLifetime.ONE_OPERATION
        )).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(UserFileRequestStatus.UNAVAILABLE, unavailableResult.status());
        assertEquals(
            UserFileErrorCode.RUNTIME_UNAVAILABLE,
            unavailableResult.error().orElseThrow().code()
        );
    }

    @Test
    void userFileFailuresAreCollectedOnceWithoutExposingGrantPaths() throws Exception {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        final RuntimeUserFileAccessService denied = service(
            Set.of(PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST),
            UserFileGrantSource.fixedSelection(temporary.resolve("C:/Users/private/denied.csv")),
            failures
        );

        final var result = denied.request(request(
            UserFileMode.WRITE,
            UserFileLifetime.ONE_OPERATION
        )).toCompletableFuture().get(2, TimeUnit.SECONDS);

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
        final RuntimeUserFileAccessService first = service(
            allFilePermissions(),
            UserFileGrantSource.fixedSelection(selected)
        );
        final RuntimeUserFileAccessService second = new RuntimeUserFileAccessService(
            "dev.turboism.plugin.other",
            permissions(UserFileMode.READ),
            UserFileGrantSource.fixedSelection(selected),
            tasks,
            scope
        );
        final UserFileHandle firstHandle = first.request(request(
            UserFileMode.READ,
            UserFileLifetime.UNTIL_DISABLE
        )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();

        assertEquals(
            UserFileErrorCode.FOREIGN_GRANT,
            second.readUtf8(firstHandle, 16).toCompletableFuture()
                .get(2, TimeUnit.SECONDS).error().orElseThrow().code()
        );
        assertEquals(
            UserFileErrorCode.INVALID_GRANT,
            first.readUtf8(new ForgedHandle(), 16).toCompletableFuture()
                .get(2, TimeUnit.SECONDS).error().orElseThrow().code()
        );

        firstHandle.revoke();
        assertEquals(UserFileHandleState.REVOKED, firstHandle.state());
        assertEquals(
            UserFileErrorCode.GRANT_REVOKED,
            first.readUtf8(firstHandle, 16).toCompletableFuture()
                .get(2, TimeUnit.SECONDS).error().orElseThrow().code()
        );

        final UserFileHandle oneOperationRead = first.request(request(
            UserFileMode.READ,
            UserFileLifetime.ONE_OPERATION
        )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();
        final var mismatch = first.writeUtf8Atomic(oneOperationRead, "bad")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(UserFileErrorCode.MODE_MISMATCH, mismatch.error().orElseThrow().code());
        assertEquals(UserFileHandleState.CLOSED, oneOperationRead.state());
    }

    @Test
    void closeRevokesGrantsAndSettlesPendingRequestBeforeSchedulerShutdown() throws Exception {
        final Path selected = temporary.resolve("input.csv");
        Files.writeString(selected, "value");
        final RuntimeUserFileAccessService grantService = service(
            permissions(UserFileMode.READ),
            UserFileGrantSource.fixedSelection(selected)
        );
        final UserFileHandle handle = grantService.request(request(
            UserFileMode.READ,
            UserFileLifetime.UNTIL_DISABLE
        )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();

        final CompletableFuture<UserFileGrantSource.Decision> pending = new CompletableFuture<>();
        final RuntimeUserFileAccessService pendingService = service(
            permissions(UserFileMode.READ),
            request -> pending
        );
        final var pendingResult = pendingService.request(request(
            UserFileMode.READ,
            UserFileLifetime.ONE_OPERATION
        ));

        grantService.close();
        assertEquals(UserFileHandleState.REVOKED, handle.state());
        assertEquals(
            UserFileErrorCode.GRANT_REVOKED,
            grantService.readUtf8(handle, 16).toCompletableFuture()
                .get(2, TimeUnit.SECONDS).error().orElseThrow().code()
        );

        pendingService.close();
        assertEquals(
            UserFileRequestStatus.UNAVAILABLE,
            pendingResult.toCompletableFuture().get(2, TimeUnit.SECONDS).status()
        );
        pending.complete(UserFileGrantSource.Decision.selected(selected));
        assertFalse(runtimeScheduler.isClosed());
    }

    @Test
    void oneOperationGrantIsConsumedEvenWhenCurrentPermissionIsMissing() throws Exception {
        final Path selected = temporary.resolve("input.csv");
        Files.writeString(selected, "value");
        final RuntimeUserFileAccessService service = service(
            permissions(UserFileMode.READ),
            UserFileGrantSource.fixedSelection(selected)
        );
        final UserFileHandle handle = service.request(request(
            UserFileMode.READ,
            UserFileLifetime.ONE_OPERATION
        )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();

        final var denied = service.writeUtf8Atomic(handle, "not-written")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(UserFileErrorCode.PERMISSION_DENIED, denied.error().orElseThrow().code());
        assertEquals(UserFileHandleState.CLOSED, handle.state());
        assertEquals("value", Files.readString(selected));
    }

    @Test
    void oversizedAndZeroByteReadsAreStructured() throws Exception {
        final Path selected = temporary.resolve("input.csv");
        Files.writeString(selected, "x");
        final RuntimeUserFileAccessService service = service(
            permissions(UserFileMode.READ),
            UserFileGrantSource.fixedSelection(selected)
        );
        final UserFileHandle zero = service.request(request(
            UserFileMode.READ,
            UserFileLifetime.ONE_OPERATION
        )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();
        final var zeroResult = service.readBytes(zero, 0).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertEquals(0, zeroResult.value().orElseThrow().length);
        assertTrue(zeroResult.truncated());

        final UserFileHandle oversized = service.request(request(
            UserFileMode.READ,
            UserFileLifetime.ONE_OPERATION
        )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();
        final var oversizedResult = service.readBytes(oversized, 9 * 1024 * 1024)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(
            UserFileErrorCode.SIZE_LIMIT_EXCEEDED,
            oversizedResult.error().orElseThrow().code()
        );
        assertEquals(UserFileHandleState.CLOSED, oversized.state());
    }

    private RuntimeUserFileAccessService service(
        final Set<String> permissions,
        final UserFileGrantSource source
    ) {
        return service(permissions, source, new RuntimeFailureCollector());
    }

    private RuntimeUserFileAccessService service(
        final Set<String> permissions,
        final UserFileGrantSource source,
        final RuntimeFailureCollector failures
    ) {
        if (scope == null) {
            scope = new DisposableScope();
            runtimeScheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 32, ignored -> { }, Clock.systemUTC()),
                SidecarDispatcher.noop(),
                ignored -> { }
            );
            tasks = new RuntimePluginTaskScheduler(PLUGIN_ID, runtimeScheduler, scope);
        }
        return new RuntimeUserFileAccessService(
            PLUGIN_ID,
            permissions,
            source,
            tasks,
            scope,
            new dev.turboism.cleanup.CleanupEvidenceCollector(),
            failures
        );
    }

    private static Set<String> permissions(final UserFileMode mode) {
        return mode == UserFileMode.READ
            ? Set.of(
                PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST,
                PermissionIds.TURBOISM_FILE_READ
            )
            : Set.of(
                PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST,
                PermissionIds.TURBOISM_FILE_WRITE
            );
    }

    private static Set<String> allFilePermissions() {
        return Set.of(
            PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST,
            PermissionIds.TURBOISM_FILE_READ,
            PermissionIds.TURBOISM_FILE_WRITE
        );
    }

    private static UserFileRequest request(
        final UserFileMode mode,
        final UserFileLifetime lifetime
    ) {
        return new UserFileRequest(
            "request-" + mode.name().toLowerCase(),
            "Choose CSV",
            List.of("csv"),
            mode,
            lifetime
        );
    }

    private static final class ForgedHandle implements UserFileHandle {
        @Override public String id() { return "forged"; }
        @Override public String displayName() { return "forged.csv"; }
        @Override public UserFileMode mode() { return UserFileMode.READ; }
        @Override public UserFileLifetime lifetime() { return UserFileLifetime.UNTIL_DISABLE; }
        @Override public UserFileHandleState state() { return UserFileHandleState.ACTIVE; }
        @Override public void revoke() { }
        @Override public void close() { }
    }
}
