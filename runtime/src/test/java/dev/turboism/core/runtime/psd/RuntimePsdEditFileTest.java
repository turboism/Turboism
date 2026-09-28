package dev.turboism.core.runtime.psd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Handle-level contract for the runtime-issued PSD edit file: runtime tests only, no live host,
 * no native PSD write and no external application is started.
 */
class RuntimePsdEditFileTest {
    private static final PsdEditRegistry.Binding BINDING = new PsdEditRegistry.Binding("session-a", 3L);

    @TempDir
    Path root;

    @Test
    void openHandsTheValidatedAllocationToTheDefaultApplicationAfterAdmission() throws Exception {
        final PsdEditRegistry registry = new PsdEditRegistry();
        final Allocation allocation = allocation();
        final List<String> permissions = new ArrayList<>();
        final AtomicReference<Path> launched = new AtomicReference<>();
        final AtomicBoolean active = new AtomicBoolean(true);
        final RuntimePsdEditFile file =
                handle(registry, allocation, recording(active, permissions), active, launched::set);

        final PsdFileOperationResult result = await(file.openInDefaultApplication());

        assertEquals(PsdFileOperationResult.Status.OPENED, result.status());
        assertEquals(allocation.temporary.validatedPath(), launched.get());
        assertTrue(permissions.contains(PermissionIds.TURBOISM_PROCESS));
        assertTrue(permissions.contains(PermissionIds.TURBOISM_FILE_READ));
    }

    @Test
    void openIsRejectedWithoutProcessPermissionAndNeverLaunches() throws Exception {
        final PsdEditRegistry registry = new PsdEditRegistry();
        final Allocation allocation = allocation();
        final AtomicReference<Path> launched = new AtomicReference<>();
        final AtomicBoolean active = new AtomicBoolean(true);
        final PermissionChecker denyAll = (permission, operation) -> {
            throw new CubismPermissionException("permission denied");
        };
        final RuntimePsdEditFile file = handle(registry, allocation, denyAll, active, launched::set);

        final PsdFileOperationResult result = await(file.openInDefaultApplication());

        assertEquals(PsdFileOperationResult.Status.REJECTED, result.status());
        assertTrue(result.diagnostic().contains("permission"));
        assertNull(launched.get());
    }

    @Test
    void openFailsClosedForARevokedHandleAndAfterStop() throws Exception {
        final PsdEditRegistry registry = new PsdEditRegistry();
        final Allocation allocation = allocation();
        final AtomicBoolean active = new AtomicBoolean(true);
        final RuntimePsdEditFile file = handle(registry, allocation, allowAll(), active, path -> {});

        registry.revoke(file);
        assertEquals(
                PsdFileOperationResult.Status.REJECTED,
                await(file.openInDefaultApplication()).status());

        registry.register(BINDING, file, allocation.temporary);
        assertEquals(PsdFileOperationResult.Status.STOPPED, await(file.stop()).status());
        assertEquals(PsdFileOperationResult.Status.STOPPED, await(file.stop()).status());
        assertEquals(
                PsdFileOperationResult.Status.UNAVAILABLE,
                await(file.openInDefaultApplication()).status());
    }

    @Test
    void openReportsFailureAndRetainsTheFileWhenNoDefaultApplicationLaunches() throws Exception {
        final PsdEditRegistry registry = new PsdEditRegistry();
        final Allocation allocation = allocation();
        final AtomicBoolean active = new AtomicBoolean(true);
        final RuntimePsdEditFile file = handle(registry, allocation, allowAll(), active, path -> {
            throw new IOException("no default application");
        });

        final PsdFileOperationResult result = await(file.openInDefaultApplication());

        assertEquals(PsdFileOperationResult.Status.FAILED, result.status());
        assertTrue(result.diagnostic().contains("launch=failed"));
        assertTrue(
                Files.exists(allocation.temporary.validatedPath()),
                "a failed launch must retain the temporary PSD for the OS/user to clean up");
    }

    @Test
    void subscriptionPublishesStagedRevisionsAndIsolatesConsumerFailures() throws Exception {
        final PsdEditRegistry registry = new PsdEditRegistry();
        final Allocation allocation = allocation();
        final AtomicBoolean active = new AtomicBoolean(true);
        final RuntimePsdEditFile file = handle(registry, allocation, allowAll(), active, path -> {});

        final List<PsdFileRevision> delivered = new ArrayList<>();
        file.observeSaves(revision -> {
            throw new IllegalStateException("isolated consumer failure");
        });
        final Registration registration = file.observeSaves(delivered::add);

        file.publishStableSave(PsdStableSnapshot.capture(allocation.temporary));
        file.publishStableSave(PsdStableSnapshot.capture(allocation.temporary));

        assertEquals(2, delivered.size());
        registration.close();
        file.publishStableSave(PsdStableSnapshot.capture(allocation.temporary));
        assertEquals(2, delivered.size());
    }

    @Test
    void subscriptionQuotaAndStoppedAdmissionAreEnforced() throws Exception {
        final PsdEditRegistry registry = new PsdEditRegistry();
        final Allocation allocation = allocation();
        final AtomicBoolean active = new AtomicBoolean(true);
        final RuntimePsdEditFile file = handle(registry, allocation, allowAll(), active, path -> {});

        for (int i = 0; i < RuntimePsdEditFile.MAX_SUBSCRIPTIONS; i++) {
            file.observeSaves(revision -> {});
        }
        assertThrows(IllegalStateException.class, () -> file.observeSaves(revision -> {}));

        await(file.stop());
        assertThrows(IllegalStateException.class, () -> file.observeSaves(revision -> {}));
        assertThrows(
                IllegalStateException.class,
                () -> file.publishStableSave(PsdStableSnapshot.capture(allocation.temporary)));
        assertTrue(Files.exists(allocation.temporary.validatedPath()));
    }

    /**
     * Reproduces the service issuance path: register, capture the export baseline, issue it, then
     * start watching with an inert scheduler so these tests never race a background pass.
     */
    private RuntimePsdEditFile handle(
            final PsdEditRegistry registry,
            final Allocation allocation,
            final PermissionChecker permissionChecker,
            final AtomicBoolean active,
            final PsdDefaultApplicationLauncher launcher)
            throws IOException {
        final PsdStableSnapshot.Snapshot baseline = PsdStableSnapshot.capture(allocation.temporary);
        final RuntimePsdEditFile file = new RuntimePsdEditFile(
                "test.plugin",
                BINDING,
                allocation.temporary,
                registry,
                permissionChecker,
                active::get,
                Runnable::run,
                Runnable::run,
                launcher,
                (task, delayMillis) -> () -> {},
                System::nanoTime,
                baseline.sha256());
        registry.register(BINDING, file, allocation.temporary);
        file.beginWatching(registry.issueRevision(BINDING, file, baseline));
        return file;
    }

    private Allocation allocation() throws IOException {
        final PsdTemporaryFile temporary = PsdTemporaryFile.createIn(root);
        Files.writeString(temporary.validatedPath(), "runtime PSD fixture");
        return new Allocation(temporary);
    }

    private static PermissionChecker allowAll() {
        return (permission, operation) -> {};
    }

    private static PermissionChecker recording(final AtomicBoolean allowed, final List<String> calls) {
        return (permission, operation) -> {
            calls.add(permission);
            if (!allowed.get()) throw new CubismPermissionException("permission denied");
        };
    }

    private static PsdFileOperationResult await(final CompletionStage<PsdFileOperationResult> stage) throws Exception {
        return stage.toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private record Allocation(PsdTemporaryFile temporary) {}
}
