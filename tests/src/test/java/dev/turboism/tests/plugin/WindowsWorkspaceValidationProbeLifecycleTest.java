package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.CancellationToken;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskOutcomeStatus;
import dev.turboism.sdk.task.TaskProgress;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.task.TaskSubmissionStatus;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceService;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;
import dev.turboism.test.ui.FakeDirectUiScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused lifecycle coverage: enable schedules one bounded low-frequency scan guarded by a scan
 * lock; disable/shutdown clear the enabled flag under the lock, cancel the handle, and return
 * only after any in-flight scan has left the lock, so no probe polling survives disable.
 */
class WindowsWorkspaceValidationProbeLifecycleTest {

    private FakeTaskScheduler scheduler;
    private ExecutorService testExecutor;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void enableSchedulesOneScanAndDisableCancelsItSynchronously(@TempDir final Path stateDir)
        throws Exception {
        scheduler = new FakeTaskScheduler();
        final DisposableScope scope = new DisposableScope();
        final WindowsWorkspaceValidationProbe probe = new WindowsWorkspaceValidationProbe();
        probe.init(new FakePluginContext(stateDir, scheduler, scope, WorkspaceService.unavailable()));
        probe.enable();

        assertEquals(1, scheduler.handles().size(), "enable must schedule exactly one scan");
        final TaskHandle handle = scheduler.handles().get(0);

        final Path commands = stateDir.resolve("commands");
        Files.createDirectories(commands);
        Files.writeString(commands.resolve("1-status.cmd"), "status", StandardCharsets.UTF_8);
        scheduler.runOnce().get(5, TimeUnit.SECONDS);
        assertTrue(Files.exists(stateDir.resolve("results").resolve("000001-status.txt")),
            "the scheduled scan must process the pending command");
        assertFalse(Files.exists(commands.resolve("1-status.cmd")));

        Files.writeString(commands.resolve("2-status.cmd"), "status", StandardCharsets.UTF_8);
        probe.disable();
        assertTrue(handle.cancel(), "cancel must be accepted and synchronous");
        scheduler.runOnce().get(5, TimeUnit.SECONDS);
        assertTrue(Files.exists(commands.resolve("2-status.cmd")),
            "no probe polling may survive disable");
        assertFalse(Files.exists(stateDir.resolve("results").resolve("000002-status.txt")),
            "no result may be published after disable");

        scope.close();
    }

    @Test
    void shutdownAndScopeCloseAlsoCancelTheScheduledScan(@TempDir final Path stateDir)
        throws Exception {
        scheduler = new FakeTaskScheduler();
        final DisposableScope scope = new DisposableScope();
        final WindowsWorkspaceValidationProbe probe = new WindowsWorkspaceValidationProbe();
        probe.init(new FakePluginContext(stateDir, scheduler, scope, WorkspaceService.unavailable()));
        probe.enable();
        assertEquals(1, scheduler.handles().size());
        final TaskHandle handle = scheduler.handles().get(0);

        probe.shutdown();
        assertTrue(handle.cancel());
        scope.close();
        assertFalse(Files.exists(stateDir.resolve("results")));
    }

    @Test
    void disableWaitsForInFlightScanAndNoScanRunsAfter(@TempDir final Path stateDir)
        throws Exception {
        scheduler = new FakeTaskScheduler();
        final DisposableScope scope = new DisposableScope();
        final BlockingWorkspaceService blocking = new BlockingWorkspaceService();
        final WindowsWorkspaceValidationProbe probe = new WindowsWorkspaceValidationProbe();
        probe.init(new FakePluginContext(stateDir, scheduler, scope, blocking));
        probe.enable();

        final Path commands = stateDir.resolve("commands");
        Files.createDirectories(commands);
        Files.writeString(commands.resolve("1-status.cmd"), "status", StandardCharsets.UTF_8);

        // Start one scan; it enters the scan lock and blocks inside the service call.
        final Future<?> scan = scheduler.runOnce();
        assertTrue(blocking.entered.await(5, TimeUnit.SECONDS),
            "the scan must reach the in-flight service call");

        // disable() must not return while that scan is in flight.
        testExecutor = Executors.newSingleThreadExecutor();
        final Future<?> disabled = testExecutor.submit(probe::disable);
        Thread.sleep(300L);
        assertFalse(disabled.isDone(), "disable must synchronously wait for the in-flight scan");

        blocking.release.countDown();
        disabled.get(5, TimeUnit.SECONDS);
        scan.get(5, TimeUnit.SECONDS);
        assertTrue(scheduler.handles().get(0).cancel(), "handle must have been cancelled");

        // No subsequent scan may execute after disable returned.
        Files.writeString(commands.resolve("2-status.cmd"), "status", StandardCharsets.UTF_8);
        scheduler.runOnce().get(5, TimeUnit.SECONDS);
        assertTrue(Files.exists(commands.resolve("2-status.cmd")),
            "a scan starting after disable must observe the disabled flag before any service/I/O");
        assertFalse(Files.exists(stateDir.resolve("results").resolve("000002-status.txt")));
        scope.close();
    }

    /** Honest single-run scheduler: the test controls each scan via {@link #runOnce()}. */
    static final class FakeTaskScheduler implements PluginTaskScheduler {

        private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "fake-plugin-task");
            thread.setDaemon(true);
            return thread;
        });
        private final List<TaskHandle> handles = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicReference<FixedDelayTaskRequest> captured = new AtomicReference<>();

        @Override
        public TaskSubmission submit(final PluginTaskRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskSubmission scheduleWithFixedDelay(final FixedDelayTaskRequest request) {
            if (!captured.compareAndSet(null, request)) {
                throw new IllegalStateException("fake scheduler accepts one scheduled task");
            }
            final TaskHandle handle = new TaskHandle() {
                private volatile boolean cancelled;

                @Override
                public TaskId id() {
                    return request.id();
                }

                @Override
                public TaskProgress progress() {
                    return new TaskProgress(0, Optional.empty());
                }

                @Override
                public boolean cancel() {
                    cancelled = true;
                    return true;
                }

                @Override
                public CompletionStage<TaskOutcome> completion() {
                    return CompletableFuture.completedFuture(new TaskOutcome(
                        request.id(), TaskOutcomeStatus.CANCELED, 0, Optional.empty(), Optional.empty()
                    ));
                }

                @Override
                public void close() {
                    cancelled = true;
                }
            };
            handles.add(handle);
            return new TaskSubmission(TaskSubmissionStatus.ACCEPTED, handle, Optional.empty());
        }

        /** Runs one scan action on a fresh daemon thread, mirroring one scheduled tick. */
        Future<?> runOnce() {
            final FixedDelayTaskRequest request = captured.get();
            if (request == null) {
                throw new IllegalStateException("no scheduled task captured");
            }
            return executor.submit(() -> {
                try {
                    request.action().run(new CancellationToken() {
                        @Override
                        public boolean isCancellationRequested() {
                            return false;
                        }

                        @Override
                        public void checkCanceled() {
                            // Never cancels in the fake.
                        }
                    });
                } catch (Exception ignored) {
                    // The probe's scan catches and records its own failures.
                }
            });
        }

        List<TaskHandle> handles() {
            return handles;
        }

        void shutdown() {
            executor.shutdownNow();
        }
    }

    /** Blocks in current() until the test releases it, proving a scan is in flight. */
    static final class BlockingWorkspaceService implements WorkspaceService {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public CompletionStage<WorkspaceStatus> current() {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(new WorkspaceStatus(
                WorkspaceStatus.Availability.UNAVAILABLE,
                Optional.empty(),
                List.of(),
                Optional.of("blocked")
            ));
        }

        @Override
        public CompletionStage<WorkspaceOperationResult> switchTo(final WorkspaceId workspaceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<WorkspaceOperationResult> updateDefault() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<WorkspaceOperationResult> resetToDefault() {
            throw new UnsupportedOperationException();
        }
    }

    /** Minimal context: temp stateDir, controllable scheduler, injectable workspace, no host. */
    static final class FakePluginContext implements PluginContext {

        private final PluginPaths paths;
        private final PluginTaskScheduler tasks;
        private final DisposableScope scope;
        private final WorkspaceService workspace;

        FakePluginContext(
            final Path stateDir,
            final PluginTaskScheduler tasks,
            final DisposableScope scope,
            final WorkspaceService workspace
        ) {
            this.paths = new PluginPaths() {
                @Override
                public Path dataDir() {
                    return stateDir;
                }

                @Override
                public Path logsDir() {
                    return stateDir.resolve("logs");
                }

                @Override
                public Path stateDir() {
                    return stateDir;
                }

                @Override
                public Path cacheDir() {
                    return stateDir.resolve("cache");
                }
            };
            this.tasks = tasks;
            this.scope = scope;
            this.workspace = workspace;
        }

        @Override
        public PluginDescriptor descriptor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginLogger logger() {
            return new PluginLogger() {
                @Override public void debug(String message) { }
                @Override public void info(String message) { }
                @Override public void warn(String message) { }
                @Override public void error(String message) { }
                @Override public void error(String message, Throwable throwable) { }
            };
        }

        @Override
        public PluginPaths paths() {
            return paths;
        }

        @Override
        public PluginTaskScheduler tasks() {
            return tasks;
        }

        @Override
        public WorkspaceService workspace() {
            return workspace;
        }

        @Override
        public CubismFacade cubism() {
            return new CubismFacade() {
                @Override
                public CubismRuntimeSnapshot runtime() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<ProjectSnapshot> activeProject() {
                    return Optional.empty();
                }

                @Override
                public Optional<DocumentSnapshot> activeDocument() {
                    return Optional.empty();
                }

                @Override
                public Optional<ModelSnapshot> activeModel() {
                    return Optional.empty();
                }

                @Override
                public boolean isHostPresent() {
                    return false;
                }

                @Override
                public TransactionManager transactionManager() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.turboism.sdk.action.ActionRegistry actions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MenuRegistry menus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public UiScheduler uiScheduler() {
            return new FakeDirectUiScheduler();
        }

        @Override
        public DiagnosticReport diagnostics() {
            return new DiagnosticReport() {
                @Override
                public Instant createdAt() {
                    return Instant.EPOCH;
                }

                @Override
                public List<Problem> problems() {
                    return List.of();
                }
            };
        }

        @Override
        public DisposableScope disposableScope() {
            return scope;
        }
    }
}
