package dev.turboism.plugin.backup;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-safe coverage of the target rebuild path: the init/enable race
 * (refreshTarget before the async registerSchema completes) must retry with
 * backoff and eventually build the target, and disable() must cancel the
 * pending retries. The retry-exhaustion sequence (five backoffs, ~15.5s) is
 * covered by the interactive session, not here.
 */
final class BackupPluginTest {

    private final List<BackupPlugin> plugins = new ArrayList<>();

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
        BackupPlugin plugin = new BackupPlugin();
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable(); // registerSchema is still pending: read() yields null
        assertTrue(context.awaitLog("WEBDAV_TARGET_RETRY attempt=1", Duration.ofSeconds(2)),
            "a not-yet-initialized binding must schedule a bounded retry");
        context.registry.completeSchema();
        assertTrue(context.awaitLog("WEBDAV_TARGET_READY", Duration.ofSeconds(4)),
            "the retry (or the init completion) must build the target once the schema is registered");
        assertTrue(context.awaitLog("WebDAV backup sync binding initialized", Duration.ofSeconds(1)));
    }

    @Test
    void disableCancelsPendingTargetRetries() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin = new BackupPlugin();
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        assertTrue(context.awaitLog("WEBDAV_TARGET_RETRY attempt=1", Duration.ofSeconds(2)));
        plugin.disable(); // clears the retry state; binding is disabled
        context.registry.completeSchema();
        Thread.sleep(1_200L); // long enough for the first backoff (500ms) to elapse
        assertFalse(context.hasLog("WEBDAV_TARGET_READY"),
            "a cancelled retry must never build the target");
        assertFalse(context.hasLog("WEBDAV_TARGET_RETRY attempt=2"),
            "disable must stop the retry chain");
    }

    @Test
    void enableAfterInitBuildsTheTargetWithoutRetries() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin = new BackupPlugin();
        plugins.add(plugin);
        plugin.init(context);
        context.registry.completeSchema();
        assertTrue(context.awaitLog("WebDAV backup sync binding initialized", Duration.ofSeconds(2)));
        plugin.enable();
        assertTrue(context.awaitLog("WEBDAV_TARGET_READY", Duration.ofSeconds(2)),
            "an initialized binding must build the target on enable");
        assertFalse(context.hasLog("WEBDAV_TARGET_RETRY"));
    }

    @Test
    void applySavedConfigBuildsTheTargetDeterministically() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin = new BackupPlugin();
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable(); // registerSchema stays pending: read-driven construction never succeeds
        plugin.applySavedConfig(savedConfig());
        assertTrue(context.awaitLog("WEBDAV_TARGET_READY", Duration.ofSeconds(2)),
            "the dialog-persisted config must build the target without any binding read");
        context.bus.fire(completedEvent());
        assertTrue(context.awaitLog("WEBDAV_SYNC_UPLOAD file=model.cmo3", Duration.ofSeconds(2)),
            "the event must reach the sync target built from the saved config");
        assertFalse(context.hasLog("WEBDAV_SYNC_SKIPPED"),
            "a deterministic target must never be skipped");
    }

    @Test
    void onBackupCompletedLazilyRebuildsTheTargetFromTheLastSavedConfig() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin = new BackupPlugin();
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        plugin.applySavedConfig(savedConfig());
        // The pending retry (500ms) wakes with the binding still uninitialized
        // and clears the target; the last-saved config must still serve events.
        Thread.sleep(900L);
        context.bus.fire(completedEvent());
        assertTrue(context.awaitLog("WEBDAV_TARGET_LAZY_REBUILT", Duration.ofSeconds(2)),
            "a nulled target must be rebuilt lazily from the last saved config");
        assertTrue(context.awaitLog("WEBDAV_SYNC_UPLOAD file=model.cmo3", Duration.ofSeconds(2)));
        assertFalse(context.hasLog("WEBDAV_SYNC_SKIPPED"),
            "the lazy rebuild must prevent the skip path");
    }

    @Test
    void applySavedConfigLogsTheTriggerModeAndAutoModeIgnoresEvents() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin = new BackupPlugin();
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        plugin.applySavedConfig(autoConfig());
        assertTrue(context.awaitLog("WEBDAV_TRIGGER_MODE mode=AUTO_BACKUP_SYNC", Duration.ofSeconds(2)),
            "the trigger mode must be logged");
        context.bus.fire(completedEvent());
        Thread.sleep(300L);
        assertFalse(context.hasLog("WEBDAV_SYNC_UPLOAD"),
            "AUTO_BACKUP_SYNC must not react to BackupCompletedEvent (the scanner owns it)");
    }

    @Test
    void autoBackupScanUploadsNewHostArtifactsWithoutDeletingThem() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin = new BackupPlugin();
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        java.nio.file.Path backupDir = java.nio.file.Files.createTempDirectory("host-backup-");
        context.hostBackupDir = backupDir;
        java.nio.file.Files.writeString(
            backupDir.resolve("model_backup2026_08_08_120000.cmo3"), "host-artifact");
        plugin.applySavedConfig(autoConfig());
        plugin.scanOnce();
        assertTrue(context.awaitLog("WEBDAV_SYNC_UPLOAD file=model_backup2026_08_08_120000.cmo3",
                Duration.ofSeconds(2)),
            "the scanner must upload a new host artifact");
        assertTrue(java.nio.file.Files.exists(backupDir.resolve("model_backup2026_08_08_120000.cmo3")),
            "AUTO_BACKUP_SYNC must never delete host artifacts");
        int uploads = (int) context.logger.lines.stream()
            .filter(line -> line.startsWith("WEBDAV_SYNC_UPLOAD")).count();
        plugin.scanOnce();
        assertEquals(uploads, (int) context.logger.lines.stream()
                .filter(line -> line.startsWith("WEBDAV_SYNC_UPLOAD")).count(),
            "the dedup set must skip an already scanned artifact");
    }

    @Test
    void saveTriggeredTempArtifactsAreDeletedAfterTheEvent() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin = new BackupPlugin();
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        plugin.applySavedConfig(savedConfig());
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("turboism-backup-");
        java.nio.file.Path artifact = tempDir.resolve("model_backup2026_08_08_120000.cmo3");
        java.nio.file.Files.writeString(artifact, "temp-content");
        context.bus.fire(new dev.turboism.sdk.cubism.backup.BackupCompletedEvent(
            1_000L,
            List.of(artifact.toFile()),
            List.of(new dev.turboism.sdk.cubism.backup.EditorAutoBackupStatus(
                "model.cmo3", "model.cmo3", 1_000L, 900L, false))
        ));
        assertTrue(context.awaitLog("WEBDAV_TEMP_CLEANUP file=model_backup2026_08_08_120000.cmo3",
                Duration.ofSeconds(2)),
            "the temp artifact must be cleaned up after the upload attempt");
        assertFalse(java.nio.file.Files.exists(artifact),
            "the save-triggered temp file must be deleted");
    }

    private static dev.turboism.plugin.backup.webdav.WebDavConfig autoConfig() {
        return new dev.turboism.plugin.backup.webdav.WebDavConfig(
            true, java.net.URI.create("https://dav.example"), "alice", "pw",
            "/backup", true, 2, 500L, 30,
            dev.turboism.plugin.backup.webdav.WebDavConfig.RemoteTrigger.AUTO_BACKUP_SYNC);
    }

    private static dev.turboism.plugin.backup.webdav.WebDavConfig savedConfig() {
        return new dev.turboism.plugin.backup.webdav.WebDavConfig(
            true, java.net.URI.create("https://dav.example"), "alice", "pw",
            "/backup", true, 2, 500L, 30);
    }

    private static dev.turboism.sdk.cubism.backup.BackupCompletedEvent completedEvent() {
        return new dev.turboism.sdk.cubism.backup.BackupCompletedEvent(
            1_000L,
            List.of(new java.io.File("model.cmo3")),
            List.of(new dev.turboism.sdk.cubism.backup.EditorAutoBackupStatus(
                "model.cmo3", "model.cmo3", 1_000L, 900L, false))
        );
    }

    /** Recording plugin context with a gated config registry. */
    private static final class FakeContext implements PluginContext {
        final RecordingLogger logger = new RecordingLogger();
        final GatedRegistry registry = new GatedRegistry();
        final RecordingEventBus bus = new RecordingEventBus();
        java.nio.file.Path hostBackupDir;

        @Override
        public PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginPaths paths() {
            return null;
        }

        @Override
        public CubismFacade cubism() {
            return null;
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            return bus;
        }

        @Override
        public ActionRegistry actions() {
            return new ActionRegistry() {
                @Override
                public Registration register(final String id, final Action action) {
                    return () -> { };
                }
            };
        }

        @Override
        public MenuRegistry menus() {
            return new MenuRegistry() {
                @Override
                public Registration contribute(final MenuContribution contribution) {
                    return () -> { };
                }
            };
        }

        @Override
        public PluginConfigRegistry config() {
            return registry;
        }

        @Override
        public dev.turboism.sdk.cubism.backup.EditorAutoBackupService backup() {
            return new dev.turboism.sdk.cubism.backup.EditorAutoBackupService() {
                @Override
                public dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings settings() {
                    return new dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings(
                        true, 5, 50, hostBackupDir == null ? null : hostBackupDir.toString());
                }

                @Override
                public dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings updateSettings(
                    final dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings settings
                ) {
                    return settings;
                }

                @Override
                public List<dev.turboism.sdk.cubism.backup.EditorAutoBackupStatus> statuses() {
                    return List.of();
                }

                @Override
                public CompletionStage<dev.turboism.sdk.cubism.backup.BackupCompletedEvent> backupNow() {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<dev.turboism.sdk.cubism.backup.BackupCompletedEvent> backupAfterSave(
                    final dev.turboism.sdk.cubism.ProjectContentSnapshot saved
                ) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public Registration registerSyncTarget(final dev.turboism.sdk.cubism.backup.BackupSyncTarget target) {
                    return () -> { };
                }
            };
        }

        @Override
        public dev.turboism.sdk.plugin.DisposableScope disposableScope() {
            return new dev.turboism.sdk.plugin.DisposableScope();
        }

        @Override
        public dev.turboism.sdk.ui.UiScheduler uiScheduler() {
            return new dev.turboism.sdk.ui.UiScheduler() {
                @Override
                public Registration runOnUiThread(final Runnable work) {
                    return () -> { };
                }

                @Override
                public Registration runOnUiThreadLater(final Runnable work, final java.time.Duration delay) {
                    return () -> { };
                }
            };
        }

        @Override
        public dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
            return new dev.turboism.sdk.diagnostics.DiagnosticReport() {
                @Override
                public java.time.Instant createdAt() {
                    return java.time.Instant.EPOCH;
                }

                @Override
                public List<dev.turboism.sdk.diagnostics.DiagnosticReport.Problem> problems() {
                    return List.of();
                }
            };
        }

        boolean awaitLog(final String fragment, final Duration timeout) throws InterruptedException {
            final long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (hasLog(fragment)) {
                    return true;
                }
                Thread.sleep(50L);
            }
            return hasLog(fragment);
        }

        boolean hasLog(final String fragment) {
            return logger.lines.stream().anyMatch(line -> line.contains(fragment));
        }
    }

    /** Registry whose registerSchema completes only when the test says so. */
    private static final class GatedRegistry implements PluginConfigRegistry {
        private final CompletableFuture<Void> schema = new CompletableFuture<>();
        private final Map<String, Object> values = new HashMap<>();

        void completeSchema() {
            schema.complete(null);
        }

        @Override
        public CompletionStage<Void> registerSchema(
            final dev.turboism.sdk.config.ConfigSchema schema,
            final List<dev.turboism.sdk.config.ConfigMigration> migrations
        ) {
            return this.schema;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
            final T value = (T) values.getOrDefault(key.name(), key.defaultValue());
            return CompletableFuture.completedFuture(new ConfigReadResult<>(
                new ConfigValue<>(value, ConfigValueSource.STORED, 1L), Optional.empty()));
        }

        @Override
        public <T> CompletionStage<ConfigWriteResult> write(
            final ConfigKey<T> key, final T value, final long expected
        ) {
            values.put(key.name(), value);
            return CompletableFuture.completedFuture(new ConfigWriteResult(true, expected + 1, Optional.empty()));
        }

        @Override
        public Registration readScope(final String relativePath) {
            return () -> { };
        }

        @Override
        public Registration writeScope(final String relativePath) {
            return () -> { };
        }

        @Override
        public Optional<String> readString(final String relativePath, final String key) {
            return Optional.empty();
        }

        @Override
        public void writeString(final String relativePath, final String key, final String value)
            throws PluginConfigException {
            throw new PluginConfigException("not supported");
        }
    }

    /** Event bus that captures the subscribed listener so tests can fire events. */
    private static final class RecordingEventBus implements EventBus {
        final List<java.util.function.Consumer<?>> listeners = new CopyOnWriteArrayList<>();

        @Override
        public <T extends EventBus.TurboismEvent> Registration subscribe(
            final Class<T> type, final java.util.function.Consumer<T> listener
        ) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        @Override
        public <T extends EventBus.TurboismEvent> void publish(final T event) {
            fire((dev.turboism.sdk.cubism.backup.BackupCompletedEvent) event);
        }

        @SuppressWarnings("unchecked")
        void fire(final dev.turboism.sdk.cubism.backup.BackupCompletedEvent event) {
            for (java.util.function.Consumer<?> listener : listeners) {
                ((java.util.function.Consumer<dev.turboism.sdk.cubism.backup.BackupCompletedEvent>) listener)
                    .accept(event);
            }
        }
    }

    private static final class RecordingLogger implements PluginLogger {
        final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public void debug(final String message) {
            lines.add(message);
        }

        @Override
        public void info(final String message) {
            lines.add(message);
        }

        @Override
        public void warn(final String message) {
            lines.add(message);
        }

        @Override
        public void error(final String message) {
            lines.add(message);
        }

        @Override
        public void error(final String message, final Throwable throwable) {
            lines.add(message);
        }
    }
}
