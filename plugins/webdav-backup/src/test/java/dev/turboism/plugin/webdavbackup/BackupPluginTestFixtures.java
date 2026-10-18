package dev.turboism.plugin.webdavbackup;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.test.config.InMemoryConfigRegistry;
import dev.turboism.test.event.RecordingEventBus;
import dev.turboism.test.plugin.FakePluginContext;
import dev.turboism.test.plugin.RecordingPluginLogger;
import java.time.Duration;
import java.util.List;
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

    static final class FakeContext extends FakePluginContext {
        final RecordingPluginLogger logger;
        final GatedRegistry registry;
        final RecordingEventBus bus;
        java.nio.file.Path hostBackupDir;
        List<dev.turboism.sdk.cubism.backup.BackupArtifactHandle> backupArtifacts = List.of();
        CompletionStage<dev.turboism.sdk.cubism.backup.BackupRunResult> backupAfterSave;
        final List<dev.turboism.sdk.task.PluginTaskKind> submittedTaskKinds = new CopyOnWriteArrayList<>();
        volatile boolean rejectTasks;

        FakeContext() {
            this(new RecordingPluginLogger(), new GatedRegistry(), new RecordingEventBus());
        }

        FakeContext(
                final dev.turboism.sdk.action.ActionRegistry actions, final dev.turboism.sdk.menu.MenuRegistry menus) {
            this(new RecordingPluginLogger(), new GatedRegistry(), new RecordingEventBus(), actions, menus);
        }

        private FakeContext(
                final RecordingPluginLogger logger, final GatedRegistry registry, final RecordingEventBus bus) {
            this(logger, registry, bus, null, null);
        }

        private FakeContext(
                final RecordingPluginLogger logger,
                final GatedRegistry registry,
                final RecordingEventBus bus,
                final dev.turboism.sdk.action.ActionRegistry actions,
                final dev.turboism.sdk.menu.MenuRegistry menus) {
            super(FakePluginContext.builder()
                    .logger(logger)
                    .config(registry)
                    .eventBus(bus)
                    .actions(actions)
                    .menus(menus));
            this.logger = logger;
            this.registry = registry;
            this.bus = bus;
        }

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
            return logger.messages().stream().anyMatch(line -> line.contains(fragment));
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
    static final class GatedRegistry extends InMemoryConfigRegistry {
        final CompletableFuture<Void> gate = new CompletableFuture<>();

        void completeSchema() {
            gate.complete(null);
        }

        @Override
        public CompletionStage<Void> registerSchema(
                final dev.turboism.sdk.config.ConfigSchema schema,
                final List<dev.turboism.sdk.config.ConfigMigration> migrations) {
            super.registerSchema(schema, migrations);
            return gate;
        }
    }
}
