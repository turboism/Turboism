package dev.turboism.plugin.webdavbackup;

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
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Headless-safe coverage of the target rebuild path: the init/enable race
 * (refreshTarget before the async registerSchema completes) must retry with
 * backoff and eventually build the target, and disable() must cancel the
 * pending retries. The retry-exhaustion sequence (five backoffs, ~15.5s) is
 * covered by the interactive session, not here.
 */
final class BackupPluginTestFixtures {

    static final class FakeContext implements PluginContext {
        final RecordingLogger logger = new RecordingLogger();
        final GatedRegistry registry = new GatedRegistry();
        final RecordingEventBus bus = new RecordingEventBus();
        java.nio.file.Path hostBackupDir;
        List<dev.turboism.sdk.cubism.backup.BackupArtifactHandle> backupArtifacts = List.of();
        CompletionStage<dev.turboism.sdk.cubism.backup.BackupRunResult> backupAfterSave;
        final List<dev.turboism.sdk.task.PluginTaskKind> submittedTaskKinds = new CopyOnWriteArrayList<>();
        volatile boolean rejectTasks;

        @Override
        public dev.turboism.sdk.task.PluginTaskScheduler tasks() {
            return new dev.turboism.sdk.task.PluginTaskScheduler() {
                @Override
                public dev.turboism.sdk.task.TaskSubmission submit(
                        final dev.turboism.sdk.task.PluginTaskRequest request) {
                    final dev.turboism.sdk.task.TaskSubmission rejected =
                            dev.turboism.sdk.task.PluginTaskScheduler.unavailable()
                                    .submit(request);
                    if (rejectTasks) {
                        return rejected;
                    }
                    submittedTaskKinds.add(request.kind());
                    final Thread worker = new Thread(
                            () -> {
                                try {
                                    request.action().run(new dev.turboism.sdk.plugin.CancellationToken() {
                                        @Override
                                        public boolean isCancellationRequested() {
                                            return false;
                                        }

                                        @Override
                                        public void checkCanceled() {}
                                    });
                                } catch (Exception failure) {
                                    logger.warn("TEST_TASK_FAILED " + failure);
                                }
                            },
                            "backup-plugin-test-task");
                    worker.setDaemon(true);
                    worker.start();
                    // The plugin only consumes accepted(); the handle is never inspected.
                    return new dev.turboism.sdk.task.TaskSubmission(
                            dev.turboism.sdk.task.TaskSubmissionStatus.ACCEPTED, rejected.handle(), Optional.empty());
                }

                @Override
                public dev.turboism.sdk.task.TaskSubmission scheduleWithFixedDelay(
                        final dev.turboism.sdk.task.FixedDelayTaskRequest request) {
                    return dev.turboism.sdk.task.PluginTaskScheduler.unavailable()
                            .scheduleWithFixedDelay(request);
                }
            };
        }

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
                    return () -> {};
                }
            };
        }

        @Override
        public MenuRegistry menus() {
            return new MenuRegistry() {
                @Override
                public Registration contribute(final MenuContribution contribution) {
                    return () -> {};
                }
            };
        }

        @Override
        public PluginConfigRegistry config() {
            return registry;
        }

        public dev.turboism.sdk.cubism.backup.EditorAutoBackupService backup() {
            return new dev.turboism.sdk.cubism.backup.EditorAutoBackupService() {
                @Override
                public dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings settings() {
                    return new dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings(
                            true, 5, 50, Optional.ofNullable(hostBackupDir).map(java.nio.file.Path::toString));
                }

                @Override
                public dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings updateSettings(
                        final dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings settings) {
                    return settings;
                }

                @Override
                public List<dev.turboism.sdk.cubism.backup.EditorAutoBackupStatus> statuses() {
                    return List.of();
                }

                @Override
                public CompletionStage<dev.turboism.sdk.cubism.backup.BackupRunResult> backupNow() {
                    return CompletableFuture.completedFuture(
                            new dev.turboism.sdk.cubism.backup.BackupRunResult(1_000L, backupArtifacts, List.of()));
                }

                @Override
                public CompletionStage<dev.turboism.sdk.cubism.backup.BackupRunResult> backupAfterSave(
                        final dev.turboism.sdk.cubism.ProjectContentSnapshot saved) {
                    if (backupAfterSave != null) {
                        return backupAfterSave;
                    }
                    return CompletableFuture.completedFuture(
                            new dev.turboism.sdk.cubism.backup.BackupRunResult(1_000L, backupArtifacts, List.of()));
                }

                @Override
                public List<dev.turboism.sdk.cubism.backup.BackupArtifactHandle> artifacts() {
                    if (hostBackupDir == null) {
                        return List.of();
                    }
                    try (var stream = java.nio.file.Files.list(hostBackupDir)) {
                        return stream.filter(java.nio.file.Files::isRegularFile)
                                .<dev.turboism.sdk.cubism.backup.BackupArtifactHandle>map(TestBackupArtifactHandle::of)
                                .toList();
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException("fake artifact listing failed", failure);
                    }
                }

                @Override
                public Registration registerSyncTarget(final dev.turboism.sdk.cubism.backup.BackupSyncTarget target) {
                    return () -> {};
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
                    return () -> {};
                }

                @Override
                public Registration runOnUiThreadLater(final Runnable work, final java.time.Duration delay) {
                    return () -> {};
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

        @Override
        public dev.turboism.sdk.plugin.PluginServiceDirectory services() {
            return dev.turboism.sdk.plugin.PluginServices.builder()
                    .supply(dev.turboism.sdk.cubism.backup.EditorAutoBackupService.class, () -> this.backup())
                    .fallback(dev.turboism.sdk.plugin.PluginServices.of(this))
                    .build();
        }
    }

    /** Registry whose registerSchema completes only when the test says so. */
    static final class GatedRegistry implements PluginConfigRegistry {
        final CompletableFuture<Void> schema = new CompletableFuture<>();
        final Map<String, Object> values = new HashMap<>();

        void completeSchema() {
            schema.complete(null);
        }

        @Override
        public CompletionStage<Void> registerSchema(
                final dev.turboism.sdk.config.ConfigSchema schema,
                final List<dev.turboism.sdk.config.ConfigMigration> migrations) {
            return this.schema;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
            final T value = (T) values.getOrDefault(key.name(), key.defaultValue());
            return CompletableFuture.completedFuture(
                    new ConfigReadResult<>(new ConfigValue<>(value, ConfigValueSource.STORED, 1L), Optional.empty()));
        }

        @Override
        public <T> CompletionStage<ConfigWriteResult> write(
                final ConfigKey<T> key, final T value, final long expected) {
            values.put(key.name(), value);
            return CompletableFuture.completedFuture(new ConfigWriteResult(true, expected + 1, Optional.empty()));
        }

        @Override
        public Registration readScope(final String relativePath) {
            return () -> {};
        }

        @Override
        public Registration writeScope(final String relativePath) {
            return () -> {};
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
    static final class RecordingEventBus implements EventBus {
        final List<java.util.function.Consumer<?>> listeners = new CopyOnWriteArrayList<>();

        @Override
        public <T extends EventBus.TurboismEvent> Registration subscribe(
                final Class<T> type, final java.util.function.Consumer<T> listener) {
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

    static final class RecordingLogger implements PluginLogger {
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
