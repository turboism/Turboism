package dev.turboism.plugin.webdavbackup;

import static dev.turboism.plugin.webdavbackup.BackupPluginTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless-safe coverage of the target rebuild path: the init/enable race
 * (refreshTarget before the async registerSchema completes) must retry with
 * backoff and eventually build the target, and disable() must cancel the
 * pending retries. The retry-exhaustion sequence (five backoffs, ~15.5s) is
 * covered by the interactive session, not here.
 */
final class BackupPluginTest {

    private final List<BackupPlugin> plugins = new ArrayList<>();

    private HttpServer server;
    private int serverPort;
    private volatile java.util.concurrent.CountDownLatch putGate;

    @BeforeEach
    void startMockWebDavServer() throws IOException {
        // Random free port (0 = ephemeral); the target must be built only after
        // the server is bound and listening, so the first request never races.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleMockWebDav);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        serverPort = server.getAddress().getPort();
    }

    @AfterEach
    void stopMockWebDavServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleMockWebDav(final HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "MKCOL" -> exchange.sendResponseHeaders(405, -1); // collection exists
            case "PROPFIND" -> exchange.sendResponseHeaders(207, -1);
            case "PUT" -> {
                exchange.getRequestBody().readAllBytes();
                final java.util.concurrent.CountDownLatch gate = putGate;
                if (gate != null) {
                    try {
                        gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                exchange.sendResponseHeaders(201, -1);
            }
            default -> exchange.sendResponseHeaders(501, -1);
        }
        exchange.close();
    }

    @AfterEach
    void shutDownPlugins() {
        for (BackupPlugin plugin : plugins) {
            plugin.shutdown();
        }
        plugins.clear();
    }

    @Test
    void refreshTargetRetriesUntilTheBindingInitializes() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofMillis(50), Duration.ofHours(1))));
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable(); // registerSchema is still pending: read() yields null
        assertTrue(
                context.awaitLog("WEBDAV_TARGET_RETRY attempt=1", Duration.ofSeconds(2)),
                "a not-yet-initialized binding must schedule a bounded retry");
        context.registry.completeSchema();
        assertTrue(
                context.awaitLog("WEBDAV_TARGET_READY", Duration.ofSeconds(4)),
                "the retry (or the init completion) must build the target once the schema is registered");
        assertTrue(context.awaitLog("WebDAV backup sync binding initialized", Duration.ofSeconds(1)));
    }

    @Test
    void disableCancelsPendingTargetRetries() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofHours(1), Duration.ofHours(2))));
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        assertTrue(context.awaitLog("WEBDAV_TARGET_RETRY attempt=1", Duration.ofSeconds(2)));
        plugin.disable(); // clears the retry state; binding is disabled
        context.registry.completeSchema();
        awaitWorkerThreadExit();
        assertEquals(0, liveWorkerThreads(), "disable drains all retry workers before returning");
        assertFalse(context.hasLog("WEBDAV_TARGET_READY"), "a cancelled retry must never build the target");
        assertFalse(context.hasLog("WEBDAV_TARGET_RETRY attempt=2"), "disable must stop the retry chain");
    }

    @Test
    void enableAfterInitBuildsTheTargetWithoutRetries() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofMillis(50), Duration.ofHours(1))));
        plugins.add(plugin);
        plugin.init(context);
        context.registry.completeSchema();
        assertTrue(context.awaitLog("WebDAV backup sync binding initialized", Duration.ofSeconds(2)));
        plugin.enable();
        assertTrue(
                context.awaitLog("WEBDAV_TARGET_READY", Duration.ofSeconds(2)),
                "an initialized binding must build the target on enable");
        assertFalse(context.hasLog("WEBDAV_TARGET_RETRY"));
    }

    @Test
    void applySavedConfigBuildsTheTargetDeterministically() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofMillis(50), Duration.ofHours(1))));
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable(); // registerSchema stays pending: read-driven construction never succeeds
        plugin.applySavedConfig(savedConfig());
        assertTrue(
                context.awaitLog("WEBDAV_TARGET_READY", Duration.ofSeconds(2)),
                "the dialog-persisted config must build the target without any binding read");
        final java.nio.file.Path artifact = java.nio.file.Files.createTempFile("turboism-backup-test-", ".cmo3");
        java.nio.file.Files.writeString(artifact, "backup");
        context.backupArtifacts = List.of(TestBackupArtifactHandle.of(artifact));
        plugin.onModelSaved(new dev.turboism.sdk.cubism.ProjectContentSnapshot(
                "model:test",
                "model.cmo3",
                dev.turboism.sdk.cubism.ProjectContentKind.MODEL,
                java.util.Optional.empty(),
                List.of()));
        assertTrue(
                context.awaitLog("WEBDAV_SYNC_UPLOAD file=" + artifact.getFileName(), Duration.ofSeconds(2)),
                "the command result must reach the sync target built from the saved config");
        assertEquals(
                List.of(dev.turboism.sdk.task.PluginTaskKind.LONG_RUNNING),
                context.submittedTaskKinds,
                "save-triggered uploads must run on the long-task lane, not the 5s completion lane");
        assertFalse(context.hasLog("WEBDAV_SYNC_SKIPPED"), "a deterministic target must never be skipped");
    }

    @Test
    void rejectedUploadTaskIsDiagnosedInsteadOfRunningInline() throws Exception {
        FakeContext context = new FakeContext();
        context.rejectTasks = true;
        BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofMillis(50), Duration.ofHours(1))));
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        plugin.applySavedConfig(savedConfig());
        assertTrue(context.awaitLog("WEBDAV_TARGET_READY", Duration.ofSeconds(2)));
        final java.nio.file.Path artifact = java.nio.file.Files.createTempFile("turboism-backup-rejected-", ".cmo3");
        java.nio.file.Files.writeString(artifact, "backup");
        context.backupArtifacts = List.of(TestBackupArtifactHandle.of(artifact));
        plugin.onModelSaved(new dev.turboism.sdk.cubism.ProjectContentSnapshot(
                "model:test",
                "model.cmo3",
                dev.turboism.sdk.cubism.ProjectContentKind.MODEL,
                java.util.Optional.empty(),
                List.of()));
        assertTrue(context.awaitLog("WEBDAV_SYNC_REJECTED reason=RUNTIME_UNAVAILABLE", Duration.ofSeconds(2)));
        assertFalse(
                context.hasLog("WEBDAV_SYNC_UPLOAD"),
                "a rejected task must not fall back to uploading on the completion lane");
    }

    @Test
    void onBackupCompletedLazilyRebuildsTheTargetFromTheLastSavedConfig() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofMillis(50), Duration.ofHours(1))));
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        plugin.applySavedConfig(savedConfig());
        // The first retry clears the uninitialized target and schedules the next attempt.
        assertTrue(context.awaitLog("WEBDAV_TARGET_RETRY attempt=2", Duration.ofSeconds(2)));
        final java.nio.file.Path artifact = java.nio.file.Files.createTempFile("turboism-backup-lazy-", ".cmo3");
        java.nio.file.Files.writeString(artifact, "backup");
        context.backupArtifacts = List.of(TestBackupArtifactHandle.of(artifact));
        plugin.onModelSaved(new dev.turboism.sdk.cubism.ProjectContentSnapshot(
                "model:test",
                "model.cmo3",
                dev.turboism.sdk.cubism.ProjectContentKind.MODEL,
                java.util.Optional.empty(),
                List.of()));
        assertTrue(
                context.awaitLog("WEBDAV_TARGET_LAZY_REBUILT", Duration.ofSeconds(2)),
                "a nulled target must be rebuilt lazily from the last saved config");
        assertTrue(context.awaitLog("WEBDAV_SYNC_UPLOAD file=" + artifact.getFileName(), Duration.ofSeconds(2)));
        assertFalse(context.hasLog("WEBDAV_SYNC_SKIPPED"), "the lazy rebuild must prevent the skip path");
    }

    @Test
    void applySavedConfigLogsTheTriggerModeAndAutoModeIgnoresEvents() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofMillis(50), Duration.ofHours(1))));
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        plugin.applySavedConfig(autoConfig());
        assertTrue(
                context.awaitLog("WEBDAV_TRIGGER_MODE mode=AUTO_BACKUP_SYNC", Duration.ofSeconds(2)),
                "the trigger mode must be logged");
        context.bus.publish(completedEvent());
        // publish() dispatches synchronously and an upload is submitted before the handler returns
        assertTrue(context.submittedTaskKinds.isEmpty(), "AUTO_BACKUP_SYNC must not submit an upload task");
        assertFalse(
                context.hasLog("WEBDAV_SYNC_UPLOAD"),
                "AUTO_BACKUP_SYNC must not react to BackupCompletedEvent (the scanner owns it)");
    }

    @Test
    void autoBackupScanUploadsNewHostArtifactsWithoutDeletingThem() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofMillis(50), Duration.ofHours(1))));
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        java.nio.file.Path backupDir = java.nio.file.Files.createTempDirectory("host-backup-");
        context.hostBackupDir = backupDir;
        java.nio.file.Files.writeString(backupDir.resolve("model_backup2026_08_08_120000.cmo3"), "host-artifact");
        plugin.applySavedConfig(autoConfig());
        plugin.scanOnce();
        assertTrue(
                context.awaitLog("WEBDAV_SYNC_UPLOAD file=model_backup2026_08_08_120000.cmo3", Duration.ofSeconds(2)),
                "the scanner must upload a new host artifact");
        assertTrue(
                java.nio.file.Files.exists(backupDir.resolve("model_backup2026_08_08_120000.cmo3")),
                "AUTO_BACKUP_SYNC must never delete host artifacts");
        int uploads = (int) context.logger.messages().stream()
                .filter(line -> line.startsWith("WEBDAV_SYNC_UPLOAD"))
                .count();
        plugin.scanOnce();
        assertEquals(
                uploads,
                (int) context.logger.messages().stream()
                        .filter(line -> line.startsWith("WEBDAV_SYNC_UPLOAD"))
                        .count(),
                "the dedup set must skip an already scanned artifact");
    }

    @Test
    void lateSaveCompletionAfterShutdownDoesNotReenterPluginState() throws Exception {
        final FakeContext context = new FakeContext();
        final CompletableFuture<dev.turboism.sdk.cubism.backup.BackupRunResult> pending = new CompletableFuture<>();
        context.backupAfterSave = pending;
        final BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofMillis(50), Duration.ofHours(1))));
        plugins.add(plugin);
        plugin.init(context);
        context.registry.completeSchema();
        assertTrue(context.awaitLog("WebDAV backup sync binding initialized", Duration.ofSeconds(2)));
        plugin.enable();
        plugin.onModelSaved(new dev.turboism.sdk.cubism.ProjectContentSnapshot(
                "model:test",
                "model.cmo3",
                dev.turboism.sdk.cubism.ProjectContentKind.MODEL,
                java.util.Optional.empty(),
                List.of()));
        plugin.shutdown();
        plugins.remove(plugin);

        assertDoesNotThrow(() -> pending.completeExceptionally(new IllegalStateException("late completion")));
        // CompletableFuture completion invokes the registered callback synchronously.
        assertFalse(context.hasLog("SAVE_BACKUP_FAILED"));
    }

    @Test
    void saveTriggeredTempArtifactsAreDeletedAfterTheEvent() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofMillis(50), Duration.ofHours(1))));
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        plugin.applySavedConfig(savedConfig());
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("turboism-backup-");
        java.nio.file.Path artifact = tempDir.resolve("model_backup2026_08_08_120000.cmo3");
        java.nio.file.Files.writeString(artifact, "temp-content");
        context.backupArtifacts = List.of(TestBackupArtifactHandle.of(artifact));
        plugin.onModelSaved(new dev.turboism.sdk.cubism.ProjectContentSnapshot(
                "model:test",
                "model.cmo3",
                dev.turboism.sdk.cubism.ProjectContentKind.MODEL,
                java.util.Optional.empty(),
                List.of()));
        assertTrue(
                context.awaitLog("WEBDAV_TEMP_CLEANUP file=model_backup2026_08_08_120000.cmo3", Duration.ofSeconds(2)),
                "the temp artifact must be cleaned up after the upload attempt");
        assertFalse(java.nio.file.Files.exists(artifact), "the save-triggered temp file must be deleted");
    }

    @Test
    void disableDiscardsPendingTempArtifactsWhileAnUploadIsInFlight() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofMillis(50), Duration.ofHours(1))));
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        plugin.applySavedConfig(savedConfig());
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("turboism-backup-");
        java.nio.file.Path artifact = tempDir.resolve("model_backup2026_08_08_120000.cmo3");
        java.nio.file.Files.writeString(artifact, "temp-content");
        context.backupArtifacts = List.of(TestBackupArtifactHandle.of(artifact));
        putGate = new java.util.concurrent.CountDownLatch(1);
        try {
            plugin.onModelSaved(new dev.turboism.sdk.cubism.ProjectContentSnapshot(
                    "model:test",
                    "model.cmo3",
                    dev.turboism.sdk.cubism.ProjectContentKind.MODEL,
                    java.util.Optional.empty(),
                    List.of()));
            assertTrue(
                    context.awaitLog("WEBDAV_SYNC_UPLOAD file=", Duration.ofSeconds(2)),
                    "the upload task must be in flight so the artifact stays pending");
            // The plugin's static permission grant list is still in effect at
            // disable() time, so the permission-checked discard succeeds.
            plugin.disable();
            assertTrue(
                    context.hasLog("WEBDAV_TEMP_CLEANUP file=model_backup2026_08_08_120000.cmo3"),
                    "disable must discard the pending temp artifact");
            assertFalse(java.nio.file.Files.exists(artifact), "the pending temp file must be deleted during disable");
        } finally {
            putGate.countDown();
            putGate = null;
        }
    }

    @Test
    void disableTerminatesEveryPluginWorkerThread() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofMillis(50), Duration.ofHours(1))));
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        plugin.applySavedConfig(savedConfig());
        assertTrue(context.awaitLog("WEBDAV_TARGET_READY", Duration.ofSeconds(2)));
        // A completed upload guarantees the reader pool spawned its threads.
        java.nio.file.Path artifact = java.nio.file.Files.createTempFile("turboism-backup-threads-", ".cmo3");
        java.nio.file.Files.writeString(artifact, "backup");
        context.backupArtifacts = List.of(TestBackupArtifactHandle.of(artifact));
        plugin.onModelSaved(new dev.turboism.sdk.cubism.ProjectContentSnapshot(
                "model:test",
                "model.cmo3",
                dev.turboism.sdk.cubism.ProjectContentKind.MODEL,
                java.util.Optional.empty(),
                List.of()));
        assertTrue(
                context.awaitLog("WEBDAV_SYNC_COMPLETED", Duration.ofSeconds(4)),
                "the upload must finish so the reader threads demonstrably ran");
        assertTrue(liveWorkerThreads() > 0, "plugin worker threads must exist while enabled");

        plugin.disable();
        plugins.remove(plugin);

        awaitWorkerThreadExit();
        assertEquals(0, liveWorkerThreads(), "no plugin worker thread may survive disable()");
    }

    @Test
    void menuFailureDuringEnableRollsBackActionAndWorkers() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger actionsRevoked =
                new java.util.concurrent.atomic.AtomicInteger();
        final dev.turboism.sdk.action.ActionRegistry actionRegistry = (id, action) -> actionsRevoked::incrementAndGet;
        final dev.turboism.sdk.menu.MenuRegistry failingMenu = contribution -> {
            throw new IllegalStateException("fixture menu failure");
        };
        final FakeContext context = new FakeContext(actionRegistry, failingMenu);
        final BackupPlugin plugin =
                new BackupPlugin(new BackupPlugin.RetryPolicy(List.of(Duration.ofMillis(50), Duration.ofHours(1))));
        plugins.add(plugin);
        plugin.init(context);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, plugin::enable);
        assertFalse(plugin.isEnabled(), "plugin must not remain enabled on failure");
        assertEquals(1, actionsRevoked.get(), "action must be revoked on partial enable failure");
    }

    /** Live threads owned by plugin worker executors. */
    private static List<Thread> workerThreads() {
        final List<Thread> threads = new ArrayList<>();
        for (final Thread thread : Thread.getAllStackTraces().keySet()) {
            final String name = thread.getName();
            if (thread.isAlive()
                    && (name.startsWith("webdav-upload-reader-") || name.startsWith("turboism-webdav-tasks-"))) {
                threads.add(thread);
            }
        }
        return threads;
    }

    private static int liveWorkerThreads() {
        return workerThreads().size();
    }

    /**
     * An executor reports itself terminated before its last worker thread has finished
     * exiting, so a returned {@code disable()} only guarantees the pool is terminated;
     * joining the threads is the exact signal that they are gone.
     */
    private static void awaitWorkerThreadExit() throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        for (final Thread thread : workerThreads()) {
            final long remainingMillis = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
            if (remainingMillis <= 0) {
                return;
            }
            thread.join(remainingMillis);
        }
    }

    private dev.turboism.plugin.webdavbackup.webdav.WebDavConfig autoConfig() {
        return new dev.turboism.plugin.webdavbackup.webdav.WebDavConfig(
                true,
                java.net.URI.create("http://127.0.0.1:" + serverPort),
                "alice",
                "pw",
                "/backup",
                true,
                2,
                500L,
                30,
                dev.turboism.plugin.webdavbackup.webdav.WebDavConfig.RemoteTrigger.AUTO_BACKUP_SYNC);
    }

    private dev.turboism.plugin.webdavbackup.webdav.WebDavConfig savedConfig() {
        return new dev.turboism.plugin.webdavbackup.webdav.WebDavConfig(
                true,
                java.net.URI.create("http://127.0.0.1:" + serverPort),
                "alice",
                "pw",
                "/backup",
                true,
                2,
                500L,
                30);
    }

    private static dev.turboism.sdk.cubism.backup.BackupCompletedEvent completedEvent() {
        return new dev.turboism.sdk.cubism.backup.BackupCompletedEvent(
                1_000L,
                List.of(new dev.turboism.sdk.cubism.backup.BackupArtifact("model_backup.cmo3", 128L, false)),
                List.of(new dev.turboism.sdk.cubism.backup.BackupDocumentStatus("model.cmo3", 1_000L, 900L, false)));
    }

    /** Recording plugin context with a gated config registry. */
}
