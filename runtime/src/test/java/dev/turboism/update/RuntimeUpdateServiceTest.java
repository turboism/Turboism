package dev.turboism.update;

import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.plugin.core.CoreUpdateService;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.net.HttpURLConnection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeUpdateServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void startsAutomaticallyOnlyAfterTheConfiguredDelayAndPersistsTheAttempt(@TempDir final Path home)
        throws Exception {
        final DeferredTransport transport = new DeferredTransport();
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = service(
            home, scheduler, new MutableSettings(false), transport,
            Duration.ofMillis(150), Duration.ofDays(1)
        );
        try {
            service.start();
            assertFalse(transport.requested.await(20, TimeUnit.MILLISECONDS));
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            assertEquals(Optional.of(NOW), new UpdateStateStore(home).read().lastAutomaticAttempt());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void coalescesManualChecksAndEmitsOneReminderForOneVersion(@TempDir final Path home)
        throws Exception {
        final DeferredTransport transport = new DeferredTransport();
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = service(
            home, scheduler, new MutableSettings(false), transport,
            Duration.ofDays(1), Duration.ofDays(1)
        );
        final List<CoreUpdateService.Snapshot> snapshots = new ArrayList<>();
        service.subscribe(snapshots::add);
        try {
            service.start();
            final CompletionStage<CoreUpdateService.Snapshot> first = service.checkManual();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            final CompletionStage<CoreUpdateService.Snapshot> second = service.checkManual();
            assertSame(first, second);

            transport.completeWithVersion("1.2.3");
            final CoreUpdateService.Snapshot result = first.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(CoreUpdateService.Status.UPDATE_AVAILABLE, result.status());
            assertTrue(result.reminder());
            assertEquals(1, transport.calls.get());
            assertTrue(snapshots.stream().anyMatch(value -> value.status() == CoreUpdateService.Status.CHECKING));

            final CompletionStage<CoreUpdateService.Snapshot> repeated = service.checkManual();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            transport.completeWithVersion("1.2.3");
            assertFalse(repeated.toCompletableFuture().get(2, TimeUnit.SECONDS).reminder());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void automaticChecksCanBeDisabledWithoutDisablingAnExplicitManualCheck(@TempDir final Path home)
        throws Exception {
        final DeferredTransport transport = new DeferredTransport();
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = service(
            home, scheduler, new MutableSettings(false), transport,
            Duration.ofDays(1), Duration.ofDays(1)
        );
        try {
            assertTrue(service.savePreferences(new CoreUpdateService.Preferences(false)).saved());
            service.start();
            assertEquals(CoreUpdateService.Status.DISABLED, service.snapshot().status());
            assertEquals(0, transport.calls.get());

            final CompletionStage<CoreUpdateService.Snapshot> manual = service.checkManual();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            transport.completeWithVersion("0.9.0");
            assertEquals(CoreUpdateService.Status.UP_TO_DATE,
                manual.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void safeModeMakesBothAutomaticAndManualRequestsUnavailable(@TempDir final Path home)
        throws Exception {
        final DeferredTransport transport = new DeferredTransport();
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = service(
            home, scheduler, new MutableSettings(true), transport,
            Duration.ZERO, Duration.ofDays(1)
        );
        try {
            service.start();
            assertEquals(CoreUpdateService.Status.UNAVAILABLE, service.snapshot().status());
            assertEquals(CoreUpdateService.Status.UNAVAILABLE,
                service.checkManual().toCompletableFuture().get(2, TimeUnit.SECONDS).status());
            assertEquals(0, transport.calls.get());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void reportsNetworkFailureAndRejectsAResponseThatArrivesAfterClose(@TempDir final Path home)
        throws Exception {
        final DeferredTransport transport = new DeferredTransport();
        final List<String> diagnostics = new ArrayList<>();
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = new RuntimeUpdateService(
            home, scheduler, new MutableSettings(false), transport,
            Clock.fixed(NOW, ZoneOffset.UTC), installed("1.0.0"), Duration.ofDays(1), Duration.ofDays(1), diagnostics::add
        );
        try {
            final CompletionStage<CoreUpdateService.Snapshot> failed = service.checkManual();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            transport.fail(new IllegalStateException("offline"));
            assertEquals(CoreUpdateService.Status.UNAVAILABLE,
                failed.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
            assertTrue(diagnostics.contains("UPDATE_NETWORK_UNAVAILABLE"));

            final CompletionStage<CoreUpdateService.Snapshot> late = service.checkManual();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            service.close();
            assertEquals(CoreUpdateService.Status.CLOSED,
                late.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
            transport.completeWithVersion("9.9.9");
            assertEquals(CoreUpdateService.Status.CLOSED, service.snapshot().status());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void recoversFromAClockFutureAttemptBySchedulingABoundedFreshCheck(@TempDir final Path home)
        throws Exception {
        new UpdateStateStore(home).save(new UpdateStateStore.State(
            Optional.of(NOW.plusSeconds(1)), Optional.empty(), Optional.empty()
        ));
        final DeferredTransport transport = new DeferredTransport();
        final List<String> diagnostics = new ArrayList<>();
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = new RuntimeUpdateService(
            home, scheduler, new MutableSettings(false), transport,
            Clock.fixed(NOW, ZoneOffset.UTC), installed("1.0.0"), Duration.ofMillis(20), Duration.ofDays(1),
            diagnostics::add
        );
        try {
            assertTrue(diagnostics.contains("UPDATE_STATE_FUTURE_TIMESTAMP"));
            service.start();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void unknownOrDevelopmentLocalVersionNeverStartsNetworkRequests(@TempDir final Path home)
        throws Exception {
        final DeferredTransport transport = new DeferredTransport();
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = new RuntimeUpdateService(
            home, scheduler, new MutableSettings(false), transport,
            Clock.fixed(NOW, ZoneOffset.UTC), installed("dev"), Duration.ZERO, Duration.ofDays(1), ignored -> { }
        );
        try {
            final CoreUpdateService.Snapshot result = service.checkManual()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(CoreUpdateService.Status.UNAVAILABLE, result.status());
            assertEquals(0, transport.calls.get());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void closeDuringTransportStartupCancelsTheLateCreatedStage(@TempDir final Path home) throws Exception {
        final LateStageTransport transport = new LateStageTransport();
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = service(
            home, scheduler, new MutableSettings(false), transport,
            Duration.ofDays(1), Duration.ofDays(1)
        );
        try {
            final CompletionStage<CoreUpdateService.Snapshot> request = service.checkManual();
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));
            service.close();
            transport.release.countDown();
            assertTrue(transport.cancelled.await(2, TimeUnit.SECONDS));
            assertEquals(CoreUpdateService.Status.CLOSED,
                request.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void disablingAutomaticChecksDuringTransportStartupCancelsTheLateCreatedStage(@TempDir final Path home)
        throws Exception {
        final LateStageTransport transport = new LateStageTransport();
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = service(
            home, scheduler, new MutableSettings(false), transport,
            Duration.ZERO, Duration.ofDays(1)
        );
        try {
            service.start();
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));
            assertTrue(service.savePreferences(new CoreUpdateService.Preferences(false)).saved());
            transport.release.countDown();
            assertTrue(transport.cancelled.await(2, TimeUnit.SECONDS));
            assertEquals(CoreUpdateService.Status.DISABLED, service.snapshot().status());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void closeCancelsTheActualHttpConnectionRead(@TempDir final Path home) throws Exception {
        final BlockingHttpInputStream body = new BlockingHttpInputStream();
        final TrackingHttpConnection connection = new TrackingHttpConnection(body);
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = service(
            home, scheduler, new MutableSettings(false),
            new HttpUpdateTransport(Duration.ofSeconds(2), ignored -> connection),
            Duration.ofDays(1), Duration.ofDays(1)
        );
        try {
            final CompletionStage<CoreUpdateService.Snapshot> request = service.checkManual();
            assertTrue(body.readStarted.await(2, TimeUnit.SECONDS));
            service.close();
            assertTrue(body.closed.await(2, TimeUnit.SECONDS));
            assertTrue(connection.disconnected.get());
            assertEquals(CoreUpdateService.Status.CLOSED,
                request.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void disablingAutomaticChecksClosesTheActualHttpConnectionRead(@TempDir final Path home) throws Exception {
        final BlockingHttpInputStream body = new BlockingHttpInputStream();
        final TrackingHttpConnection connection = new TrackingHttpConnection(body);
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = service(
            home, scheduler, new MutableSettings(false),
            new HttpUpdateTransport(Duration.ofSeconds(2), ignored -> connection),
            Duration.ZERO, Duration.ofDays(1)
        );
        try {
            service.start();
            assertTrue(body.readStarted.await(2, TimeUnit.SECONDS));
            assertTrue(service.savePreferences(new CoreUpdateService.Preferences(false)).saved());
            assertTrue(body.closed.await(2, TimeUnit.SECONDS));
            assertTrue(connection.disconnected.get());
            assertEquals(CoreUpdateService.Status.DISABLED, service.snapshot().status());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void closeReturnsBeforeABlockingConnectionDisconnectCompletes(@TempDir final Path home) throws Exception {
        final BlockingHttpInputStream body = new BlockingHttpInputStream();
        final CountDownLatch disconnectStarted = new CountDownLatch(1);
        final CountDownLatch releaseDisconnect = new CountDownLatch(1);
        final TrackingHttpConnection connection = new TrackingHttpConnection(
            body, disconnectStarted, releaseDisconnect
        );
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = service(
            home, scheduler, new MutableSettings(false),
            new HttpUpdateTransport(Duration.ofSeconds(2), ignored -> connection),
            Duration.ofDays(1), Duration.ofDays(1)
        );
        Thread closer = null;
        try {
            final CompletionStage<CoreUpdateService.Snapshot> request = service.checkManual();
            assertTrue(body.readStarted.await(2, TimeUnit.SECONDS));
            final CountDownLatch closeReturned = new CountDownLatch(1);
            closer = new Thread(() -> {
                service.close();
                closeReturned.countDown();
            }, "update-service-close");
            closer.setDaemon(true);
            closer.start();
            assertTrue(
                closeReturned.await(500, TimeUnit.MILLISECONDS),
                "service.close must not wait for connection teardown"
            );
            assertTrue(disconnectStarted.await(2, TimeUnit.SECONDS));
            releaseDisconnect.countDown();
            assertTrue(body.closed.await(2, TimeUnit.SECONDS));
            assertTrue(connection.disconnected.get());
            assertEquals(CoreUpdateService.Status.CLOSED,
                request.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        } finally {
            releaseDisconnect.countDown();
            if (closer != null) closer.join(2_000);
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void disablingAutomaticChecksReturnsBeforeABlockingConnectionDisconnectCompletes(@TempDir final Path home)
        throws Exception {
        final BlockingHttpInputStream body = new BlockingHttpInputStream();
        final CountDownLatch disconnectStarted = new CountDownLatch(1);
        final CountDownLatch releaseDisconnect = new CountDownLatch(1);
        final TrackingHttpConnection connection = new TrackingHttpConnection(
            body, disconnectStarted, releaseDisconnect
        );
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = service(
            home, scheduler, new MutableSettings(false),
            new HttpUpdateTransport(Duration.ofSeconds(2), ignored -> connection),
            Duration.ZERO, Duration.ofDays(1)
        );
        Thread disabler = null;
        try {
            service.start();
            assertTrue(body.readStarted.await(2, TimeUnit.SECONDS));
            final CountDownLatch disableReturned = new CountDownLatch(1);
            final AtomicBoolean saved = new AtomicBoolean();
            disabler = new Thread(() -> {
                saved.set(service.savePreferences(new CoreUpdateService.Preferences(false)).saved());
                disableReturned.countDown();
            }, "update-service-disable");
            disabler.setDaemon(true);
            disabler.start();
            assertTrue(
                disableReturned.await(500, TimeUnit.MILLISECONDS),
                "disabling automatic checks must not wait for connection teardown"
            );
            assertTrue(disconnectStarted.await(2, TimeUnit.SECONDS));
            releaseDisconnect.countDown();
            assertTrue(body.closed.await(2, TimeUnit.SECONDS));
            assertTrue(connection.disconnected.get());
            assertTrue(saved.get());
            assertEquals(CoreUpdateService.Status.DISABLED, service.snapshot().status());
        } finally {
            releaseDisconnect.countDown();
            if (disabler != null) disabler.join(2_000);
            service.close();
            scheduler.shutdown();
        }
    }

    private static RuntimeUpdateService service(
        final Path home,
        final RuntimeScheduler scheduler,
        final MutableSettings settings,
        final UpdateTransport transport,
        final Duration startupDelay,
        final Duration interval
    ) {
        return new RuntimeUpdateService(
            home, scheduler, settings, transport, Clock.fixed(NOW, ZoneOffset.UTC), installed("1.0.0"),
            startupDelay, interval, ignored -> { }
        );
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 8, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    @Test
    void aLargerRecordedBuildNumberIsOfferedAsAnUpdate(@TempDir final Path home) throws Exception {
        final DeferredTransport transport = new DeferredTransport();
        final RuntimeScheduler scheduler = scheduler();
        final List<String> diagnostics = new ArrayList<>();
        final RuntimeUpdateService service = installedService(
            home, scheduler, transport, InstalledBuild.of(
                "0.43.10", java.util.OptionalLong.of(4L), "0.43.10 (stable, Build 4)"
            ), diagnostics::add
        );
        try {
            final CompletionStage<CoreUpdateService.Snapshot> request = service.checkManual();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            transport.completeWithBuild("0.43.10", 5L);
            final CoreUpdateService.Snapshot result = request.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(CoreUpdateService.Status.UPDATE_AVAILABLE, result.status());
            assertEquals(Optional.of("0.43.10"), result.availableVersion());
            assertEquals(java.util.OptionalLong.of(5L), result.availableBuildNumber());
            assertEquals(Optional.of("0.43.10 (Build 5)"), result.availableIdentity());
            assertEquals("0.43.10 (stable, Build 4)", result.localVersion());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void aLowerBuildNumberIsNeverAdvertisedAsAnUpdateAcrossVersionOrdering(@TempDir final Path home)
        throws Exception {
        final DeferredTransport transport = new DeferredTransport();
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = installedService(
            home, scheduler, transport, InstalledBuild.of(
                "0.43.10", java.util.OptionalLong.of(5L), "0.43.10 (stable, Build 5)"
            ), ignored -> { }
        );
        try {
            final CompletionStage<CoreUpdateService.Snapshot> request = service.checkManual();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            transport.completeWithBuild("0.43.11", 4L);
            assertEquals(CoreUpdateService.Status.UP_TO_DATE,
                request.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
            assertEquals(1, transport.calls.get());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void anEqualBuildNumberWithADifferentVersionIsAConflictNotAnUpdate(@TempDir final Path home)
        throws Exception {
        final DeferredTransport transport = new DeferredTransport();
        final RuntimeScheduler scheduler = scheduler();
        final List<String> diagnostics = new ArrayList<>();
        final RuntimeUpdateService service = installedService(
            home, scheduler, transport, InstalledBuild.of(
                "0.43.10", java.util.OptionalLong.of(4L), "0.43.10 (stable, Build 4)"
            ), diagnostics::add
        );
        try {
            final CompletionStage<CoreUpdateService.Snapshot> request = service.checkManual();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            transport.completeWithBuild("0.43.11", 4L);
            assertEquals(CoreUpdateService.Status.UP_TO_DATE,
                request.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
            assertTrue(diagnostics.contains("UPDATE_BUILD_IDENTITY_CONFLICT"));
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void unnumberedLegacyInstallationsCompareByNumericVersion(@TempDir final Path home) throws Exception {
        final DeferredTransport transport = new DeferredTransport();
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = installedService(
            home, scheduler, transport, InstalledBuild.of(
                "0.43.9", java.util.OptionalLong.empty(), "0.43.9"
            ), ignored -> { }
        );
        try {
            final CompletionStage<CoreUpdateService.Snapshot> request = service.checkManual();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            transport.completeWithBuild("0.43.10", 4L);
            final CoreUpdateService.Snapshot result = request.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(CoreUpdateService.Status.UPDATE_AVAILABLE, result.status());
            assertEquals(Optional.of("0.43.10 (Build 4)"), result.availableIdentity());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void aChannelWithoutAPublishedBuildIsUpToDateWhileAnUnavailableServiceIsNot(@TempDir final Path home)
        throws Exception {
        final DeferredTransport transport = new DeferredTransport();
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeUpdateService service = service(
            home, scheduler, new MutableSettings(false), transport,
            Duration.ofDays(1), Duration.ofDays(1)
        );
        try {
            final CompletionStage<CoreUpdateService.Snapshot> notPublished = service.checkManual();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            transport.completeWithDocument(
                "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"not_published\",\"release\":null}"
            );
            assertEquals(CoreUpdateService.Status.UP_TO_DATE,
                notPublished.toCompletableFuture().get(2, TimeUnit.SECONDS).status());

            final CompletionStage<CoreUpdateService.Snapshot> unavailable = service.checkManual();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            transport.completeWithDocument(
                "{\"schemaVersion\":1,\"channel\":\"stable\",\"status\":\"unavailable\",\"release\":null}"
            );
            assertEquals(CoreUpdateService.Status.UNAVAILABLE,
                unavailable.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void anInvalidDocumentIsUnavailableAndNeverUpToDate(@TempDir final Path home) throws Exception {
        final DeferredTransport transport = new DeferredTransport();
        final RuntimeScheduler scheduler = scheduler();
        final List<String> diagnostics = new ArrayList<>();
        final RuntimeUpdateService service = installedService(
            home, scheduler, transport, InstalledBuild.of("0.43.10", java.util.OptionalLong.empty(), "0.43.10"),
            diagnostics::add
        );
        try {
            final CompletionStage<CoreUpdateService.Snapshot> request = service.checkManual();
            assertTrue(transport.requested.await(2, TimeUnit.SECONDS));
            transport.completeWithDocument("{\"schemaVersion\":1,\"channel\":\"nightly\",\"status\":\"ready\"}");
            assertEquals(CoreUpdateService.Status.UNAVAILABLE,
                request.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
            assertTrue(diagnostics.contains("UPDATE_DISCOVERY_INVALID"));
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    private static RuntimeUpdateService installedService(
        final Path home,
        final RuntimeScheduler scheduler,
        final UpdateTransport transport,
        final InstalledBuild installed,
        final java.util.function.Consumer<String> diagnostic
    ) {
        return new RuntimeUpdateService(
            home, scheduler, new MutableSettings(false), transport,
            Clock.fixed(NOW, ZoneOffset.UTC), installed,
            Duration.ofDays(1), Duration.ofDays(1), diagnostic
        );
    }

    private static InstalledBuild installed(final String version) {
        return InstalledBuild.of(version, java.util.OptionalLong.empty(), version);
    }

    private static String validJson(final String version) {
        return validJson(version, null);
    }

    private static String validJson(final String version, final Long buildNumber) {
        return "{"
            + "\"schemaVersion\":1,"
            + "\"channel\":\"stable\","
            + "\"status\":\"ready\","
            + "\"release\":{"
            + "\"version\":\"" + version + "\","
            + (buildNumber == null ? "" : "\"buildNumber\":" + buildNumber + ",")
            + "\"publishedAt\":\"2026-09-07T00:00:00Z\""
            + "}}";
    }

    private static final class TrackingHttpConnection extends HttpURLConnection {
        private final BlockingHttpInputStream input;
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private final CountDownLatch disconnectStarted;
        private final CountDownLatch releaseDisconnect;

        TrackingHttpConnection(final BlockingHttpInputStream input) throws Exception {
            this(input, null, null);
        }

        TrackingHttpConnection(
            final BlockingHttpInputStream input,
            final CountDownLatch disconnectStarted,
            final CountDownLatch releaseDisconnect
        ) throws Exception {
            super(HttpUpdateTransport.ENDPOINT.toURL());
            this.input = input;
            this.disconnectStarted = disconnectStarted;
            this.releaseDisconnect = releaseDisconnect;
        }

        @Override
        public void disconnect() {
            disconnected.set(true);
            if (disconnectStarted != null && releaseDisconnect != null) {
                disconnectStarted.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        releaseDisconnect.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            input.close();
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }

        @Override
        public int getResponseCode() {
            return 200;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return input;
        }

        @Override
        public long getContentLengthLong() {
            return -1L;
        }
    }

    private static final class BlockingHttpInputStream extends InputStream {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile boolean isClosed;

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            readStarted.countDown();
            while (!isClosed && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
            }
            throw new IOException("closed");
        }

        @Override
        public int read() throws IOException {
            return read(new byte[1], 0, 1);
        }

        @Override
        public void close() {
            isClosed = true;
            closed.countDown();
        }
    }

    private static final class LateStageTransport implements UpdateTransport {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final CompletableFuture<Response> stage = new CompletableFuture<>() {
            @Override
            public boolean cancel(final boolean mayInterruptIfRunning) {
                cancelled.countDown();
                return super.cancel(mayInterruptIfRunning);
            }
        };

        @Override
        public CompletionStage<Response> fetch(final Optional<String> etag) {
            entered.countDown();
            boolean interrupted = false;
            for (;;) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return stage;
        }
    }

    private static final class DeferredTransport implements UpdateTransport {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch requested = new CountDownLatch(1);
        private volatile CompletableFuture<Response> pending = new CompletableFuture<>();

        @Override
        public CompletionStage<Response> fetch(final Optional<String> etag) {
            // Capture the deferred result before releasing the test thread, otherwise a
            // completion can race with the reset below and leave this request unresolved.
            final CompletableFuture<Response> current = pending;
            calls.incrementAndGet();
            requested.countDown();
            return current.minimalCompletionStage();
        }

        void completeWithVersion(final String version) {
            completeWithDocument(validJson(version));
        }

        void completeWithBuild(final String version, final long buildNumber) {
            completeWithDocument(validJson(version, buildNumber));
        }

        void completeWithDocument(final String document) {
            final CompletableFuture<Response> current = pending;
            reset();
            current.complete(new Response(
                200, document.getBytes(StandardCharsets.UTF_8), Optional.of("\"release-1\""))
            );
        }

        void fail(final Throwable failure) {
            final CompletableFuture<Response> current = pending;
            reset();
            current.completeExceptionally(failure);
        }

        private void reset() {
            pending = new CompletableFuture<>();
            requested = new CountDownLatch(1);
        }
    }

    private static final class MutableSettings implements RuntimeSettingsService {
        private volatile RuntimeSettings value;

        MutableSettings(final boolean safeMode) {
            value = new RuntimeSettings(safeMode, "INFO", false, false, false);
        }

        @Override
        public RuntimeSettings read() {
            return value;
        }

        @Override
        public RuntimeSettings save(final RuntimeSettings settings) {
            value = settings;
            return settings;
        }

        @Override
        public DockCleanupResult cleanEmptyDocks() {
            return new DockCleanupResult("No empty docks.");
        }
    }
}
