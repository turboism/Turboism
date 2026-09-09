package dev.turboism.core.runtime.psd;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePsdExportServiceTest {
    private static final RawImageId SOURCE = new RawImageId("raw-source");
    private static final List<String> REQUIRED_PERMISSIONS = List.of(
        PermissionIds.TURBOISM_CUBISM_MODEL_READ,
        PermissionIds.TURBOISM_FILE_WRITE,
        PermissionIds.TURBOISM_FILE_READ
    );

    @TempDir
    Path temporaryRoot;

    @Test
    void readableAndMatchingNativeObservationRemainsFailedWithoutHandle() throws Exception {
        final AtomicBoolean active = new AtomicBoolean(true);
        final List<String> permissionCalls = new ArrayList<>();
        final AtomicReference<Path> destination = new AtomicReference<>();
        final AtomicInteger hostCalls = new AtomicInteger();
        final RuntimePsdExportService service = service(
            active,
            permissionChecker(new AtomicBoolean(true), permissionCalls),
            Runnable::run
        );
        try {
            final PsdExportHost host = (source, path, admission) -> {
                destination.set(path);
                hostCalls.incrementAndGet();
                admission.run();
                return new PsdExportHost.Observation("EXPORTED", "MATCHED", true, true);
            };

            final PsdExportResult result = awaitCompletion(service.exportRawImagePsd(host, SOURCE));

            assertEquals(PsdExportResult.Status.FAILED, result.status());
            assertEquals(SOURCE, result.source());
            assertTrue(result.file().isEmpty());
            assertTrue(result.initialRevision().isEmpty());
            assertEquals(
                "PSD_NATIVE_EXPORT;status=EXPORTED;integrity=MATCHED;readable=true;structure=true",
                result.diagnostic()
            );
            assertEquals(1, hostCalls.get());
            for (String permission : REQUIRED_PERMISSIONS) {
                assertEquals(5L, permissionCalls.stream().filter(permission::equals).count());
            }
            assertNotNull(destination.get());
            assertEquals("external-edit.psd", destination.get().getFileName().toString());
        } finally {
            service.close();
        }
    }

    @Test
    void nativeUnavailableIsTypedUnavailable() throws Exception {
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicInteger hostCalls = new AtomicInteger();
        final RuntimePsdExportService service = service(active, allowAll(), Runnable::run);
        try {
            final PsdExportHost host = (source, destination, admission) -> {
                hostCalls.incrementAndGet();
                admission.run();
                return PsdExportHost.Observation.unavailable();
            };

            final PsdExportResult result = awaitCompletion(service.exportRawImagePsd(host, SOURCE));

            assertEquals(PsdExportResult.Status.UNAVAILABLE, result.status());
            assertTrue(result.file().isEmpty());
            assertTrue(result.initialRevision().isEmpty());
            assertEquals(
                "PSD_NATIVE_EXPORT;status=UNAVAILABLE;integrity=UNAVAILABLE;readable=false;structure=false",
                result.diagnostic()
            );
            assertEquals(1, hostCalls.get());
        } finally {
            service.close();
        }
    }

    @Test
    void permissionDenialStopsBeforeNative() throws Exception {
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicBoolean allowed = new AtomicBoolean(false);
        final List<String> permissionCalls = new ArrayList<>();
        final AtomicInteger hostCalls = new AtomicInteger();
        final RuntimePsdExportService service = service(
            active,
            permissionChecker(allowed, permissionCalls),
            Runnable::run
        );
        try {
            final PsdExportHost host = (source, destination, admission) -> {
                hostCalls.incrementAndGet();
                return PsdExportHost.Observation.unavailable();
            };

            final PsdExportResult result = awaitCompletion(service.exportRawImagePsd(host, SOURCE));

            assertEquals(PsdExportResult.Status.REJECTED, result.status());
            assertTrue(result.file().isEmpty());
            assertTrue(result.initialRevision().isEmpty());
            assertEquals(0, hostCalls.get());
            assertEquals(List.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ, PermissionIds.TURBOISM_CUBISM_MODEL_READ), permissionCalls);
        } finally {
            service.close();
        }
    }

    @Test
    void nativeAdmissionRechecksPermissionBeforeNativeEntry() throws Exception {
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final AtomicBoolean admissionRejected = new AtomicBoolean(false);
        final AtomicInteger hostCalls = new AtomicInteger();
        final AtomicInteger nativeCalls = new AtomicInteger();
        final RuntimePsdExportService service = service(
            active,
            permissionChecker(allowed, new ArrayList<>()),
            Runnable::run
        );
        try {
            final PsdExportHost host = (source, destination, admission) -> {
                hostCalls.incrementAndGet();
                allowed.set(false);
                try {
                    admission.run();
                } catch (CubismPermissionException expected) {
                    admissionRejected.set(true);
                    return PsdExportHost.Observation.unavailable();
                }
                nativeCalls.incrementAndGet();
                return PsdExportHost.Observation.unavailable();
            };

            final PsdExportResult result = awaitCompletion(service.exportRawImagePsd(host, SOURCE));

            assertEquals(PsdExportResult.Status.REJECTED, result.status());
            assertTrue(admissionRejected.get());
            assertEquals(1, hostCalls.get());
            assertEquals(0, nativeCalls.get());
        } finally {
            service.close();
        }
    }

    @Test
    void scopeRevocationBeforeWorkerSkipsNativeAndTemporaryAllocation() throws Exception {
        final AtomicBoolean active = new AtomicBoolean(false);
        final AtomicInteger hostCalls = new AtomicInteger();
        final AtomicInteger allocations = new AtomicInteger();
        final RuntimePsdExportService service = new RuntimePsdExportService(
            "test.plugin",
            allowAll(),
            active::get,
            Runnable::run,
            () -> {
                allocations.incrementAndGet();
                return PsdTemporaryFile.createIn(temporaryRoot);
            }
        );
        try {
            final PsdExportHost host = (source, destination, admission) -> {
                hostCalls.incrementAndGet();
                return PsdExportHost.Observation.unavailable();
            };

            final PsdExportResult result = awaitCompletion(service.exportRawImagePsd(host, SOURCE));

            assertEquals(PsdExportResult.Status.UNAVAILABLE, result.status());
            assertEquals(0, hostCalls.get());
            assertEquals(0, allocations.get());
            assertTrue(result.file().isEmpty());
            assertTrue(result.initialRevision().isEmpty());
        } finally {
            service.close();
        }
    }

    @Test
    void queueIsBoundedAtOneWorkerAndEightPendingRequests() throws Exception {
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicInteger hostCalls = new AtomicInteger();
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final RuntimePsdExportService service = service(active, allowAll(), Runnable::run);
        try {
            final PsdExportHost host = (source, destination, admission) -> {
                final int call = hostCalls.incrementAndGet();
                admission.run();
                if (call == 1) {
                    running.countDown();
                    awaitLatchUnchecked(release);
                }
                return PsdExportHost.Observation.unavailable();
            };

            final CompletionStage<PsdExportResult> first = service.exportRawImagePsd(host, SOURCE);
            awaitLatch(running);
            final List<CompletionStage<PsdExportResult>> queued = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                queued.add(service.exportRawImagePsd(host, SOURCE));
            }
            final CompletionStage<PsdExportResult> overflow = service.exportRawImagePsd(host, SOURCE);

            assertEquals(PsdExportResult.Status.REJECTED, awaitCompletion(overflow).status());
            release.countDown();
            assertEquals(PsdExportResult.Status.UNAVAILABLE, awaitCompletion(first).status());
            for (CompletionStage<PsdExportResult> request : queued) {
                assertEquals(PsdExportResult.Status.UNAVAILABLE, awaitCompletion(request).status());
            }
            assertEquals(9, hostCalls.get());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void closeDoesNotInterruptNativeAndQueuedWorkFailsClosed() throws Exception {
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicInteger hostCalls = new AtomicInteger();
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final RuntimePsdExportService service = service(active, allowAll(), Runnable::run);
        try {
            final PsdExportHost host = (source, destination, admission) -> {
                hostCalls.incrementAndGet();
                admission.run();
                running.countDown();
                awaitLatchUnchecked(release);
                interrupted.set(Thread.currentThread().isInterrupted());
                return PsdExportHost.Observation.unavailable();
            };

            final CompletionStage<PsdExportResult> runningRequest = service.exportRawImagePsd(host, SOURCE);
            awaitLatch(running);
            final CompletionStage<PsdExportResult> queuedRequest = service.exportRawImagePsd(host, SOURCE);

            final long closeStarted = System.nanoTime();
            service.close();
            final long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted);
            assertTrue(closeMillis < 1_000, "close waited for the native worker: " + closeMillis + "ms");

            release.countDown();
            assertEquals(PsdExportResult.Status.UNAVAILABLE, awaitCompletion(runningRequest).status());
            assertEquals(PsdExportResult.Status.UNAVAILABLE, awaitCompletion(queuedRequest).status());
            assertFalse(interrupted.get());
            assertEquals(1, hostCalls.get());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void scopeRevocationBeforeCompletionReclassifiesObservation() throws Exception {
        final AtomicBoolean active = new AtomicBoolean(true);
        final Queue<Runnable> pendingPublications = new ConcurrentLinkedQueue<>();
        final CountDownLatch publicationQueued = new CountDownLatch(1);
        final RuntimePsdExportService service = service(
            active,
            allowAll(),
            publication -> {
                pendingPublications.add(publication);
                publicationQueued.countDown();
            }
        );
        try {
            final PsdExportHost host = (source, destination, admission) -> {
                admission.run();
                return new PsdExportHost.Observation("EXPORTED", "MATCHED", true, true);
            };

            final CompletionStage<PsdExportResult> stage = service.exportRawImagePsd(host, SOURCE);
            awaitLatch(publicationQueued);
            active.set(false);
            pendingPublications.remove().run();

            final PsdExportResult result = awaitCompletion(stage);
            assertEquals(PsdExportResult.Status.UNAVAILABLE, result.status());
            assertTrue(result.file().isEmpty());
            assertTrue(result.initialRevision().isEmpty());
        } finally {
            service.close();
        }
    }

    @Test
    void completionRunsOnPluginDispatcher() throws Exception {
        final AtomicBoolean active = new AtomicBoolean(true);
        final ExecutorService pluginLane = Executors.newSingleThreadExecutor(named("plugin.tasks.test"));
        final RuntimePsdExportService service = service(active, allowAll(), pluginLane::execute);
        try {
            final CountDownLatch callbackDone = new CountDownLatch(1);
            final AtomicReference<String> callbackThread = new AtomicReference<>();
            final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            final PsdExportHost host = (source, destination, admission) -> {
                admission.run();
                return PsdExportHost.Observation.unavailable();
            };

            final CompletionStage<PsdExportResult> stage = service.exportRawImagePsd(host, SOURCE);
            stage.whenComplete((result, failure) -> {
                callbackThread.set(Thread.currentThread().getName());
                callbackFailure.set(failure);
                callbackDone.countDown();
            });

            awaitLatch(callbackDone);
            assertEquals(PsdExportResult.Status.UNAVAILABLE, awaitCompletion(stage).status());
            assertEquals("plugin.tasks.test", callbackThread.get());
            assertEquals(null, callbackFailure.get());
            assertFalse(Thread.currentThread().getName().equals(callbackThread.get()));
        } finally {
            service.close();
            pluginLane.shutdown();
            assertTrue(pluginLane.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void diagnosticSanitizesNativeStatusAndIntegrity() throws Exception {
        final AtomicBoolean active = new AtomicBoolean(true);
        final RuntimePsdExportService service = service(active, allowAll(), Runnable::run);
        try {
            final PsdExportHost host = (source, destination, admission) -> {
                admission.run();
                return new PsdExportHost.Observation(
                    "/tmp/private.psd",
                    "exception: /secret/native-message",
                    true,
                    true
                );
            };

            final PsdExportResult result = awaitCompletion(service.exportRawImagePsd(host, SOURCE));

            assertEquals(PsdExportResult.Status.FAILED, result.status());
            assertEquals(
                "PSD_NATIVE_EXPORT;status=UNKNOWN;integrity=UNKNOWN;readable=true;structure=true",
                result.diagnostic()
            );
            assertFalse(result.diagnostic().contains("/tmp/private.psd"));
            assertFalse(result.diagnostic().contains("/secret/native-message"));
        } finally {
            service.close();
        }
    }

    private RuntimePsdExportService service(
        final AtomicBoolean active,
        final PermissionChecker permissionChecker,
        final Consumer<Runnable> dispatcher
    ) {
        return new RuntimePsdExportService(
            "test.plugin",
            permissionChecker,
            active::get,
            dispatcher,
            () -> PsdTemporaryFile.createIn(temporaryRoot)
        );
    }

    private static PermissionChecker allowAll() {
        return (permission, operation) -> { };
    }

    private static PermissionChecker permissionChecker(
        final AtomicBoolean allowed,
        final List<String> calls
    ) {
        return (permission, operation) -> {
            calls.add(permission);
            if (!allowed.get()) {
                throw new CubismPermissionException("permission denied");
            }
        };
    }

    private static PsdExportResult awaitCompletion(final CompletionStage<PsdExportResult> stage)
        throws Exception {
        return stage.toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private static void awaitLatch(final CountDownLatch latch) throws Exception {
        assertTrue(latch.await(3, TimeUnit.SECONDS), "timed out waiting for test latch");
    }

    private static void awaitLatchUnchecked(final CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test latch was interrupted", interrupted);
        }
    }

    private static ThreadFactory named(final String name) {
        return task -> {
            final Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
