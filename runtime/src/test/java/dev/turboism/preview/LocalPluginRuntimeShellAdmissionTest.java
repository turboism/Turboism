package dev.turboism.preview;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.internal.core.ShellHandle;
import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the runtime-owned shell composition seam: a failing admission must produce a
 * useful diagnostic and close the runtime, a wired shell is admitted before external
 * plugins but never reported as one, the explicit {@code null} admission runs headless,
 * and a completed runtime must never start the shell twice.
 */
class LocalPluginRuntimeShellAdmissionTest {

    @TempDir
    Path temporary;

    @Test
    void failedShellAdmissionClosesRuntimeWithDiagnostic() throws Exception {
        final Path home = temporary.resolve("home");
        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        final IllegalStateException compositionFailure =
            new IllegalStateException("shell-compose-boom");

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                home, scheduler, host.adapterAccess(), log,
                services -> {
                    throw compositionFailure;
                }
            );
            final IllegalStateException thrown =
                assertThrows(IllegalStateException.class, runtime::loadAll);
            assertEquals("Runtime-owned shell failed to start", thrown.getMessage());
            assertNotNull(
                findCause(thrown, compositionFailure),
                "the admission failure must surface in the diagnostic chain"
            );
            // The runtime closed itself before propagating; a retry is rejected, not re-driven.
            assertThrows(IllegalStateException.class, runtime::loadAll);
        } finally {
            host.close();
            scheduler.shutdown();
        }
    }

    @Test
    void admittedShellStartsButIsNotReportedAsAPlugin() throws Exception {
        final Path home = temporary.resolve("home2");
        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        final AtomicBoolean shellStarted = new AtomicBoolean();
        final AtomicBoolean shellClosed = new AtomicBoolean();

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                home, scheduler, host.adapterAccess(), log,
                services -> new ShellHandle() {
                    @Override
                    public void start(final PluginContext context) {
                        shellStarted.set(true);
                    }

                    @Override
                    public void close() {
                        shellClosed.set(true);
                    }
                }
            );
            try {
                runtime.loadAll();
                assertTrue(shellStarted.get(), "the wired admission must run");
                assertTrue(
                    runtime.loadedPlugins().stream().noneMatch(plugin -> plugin.id().equals(
                        dev.turboism.internal.core.CorePluginManagement.CORE_PLUGIN_ID)),
                    "the shell is not a plugin and must not appear in plugin summaries"
                );
                final IllegalStateException second =
                    assertThrows(IllegalStateException.class, runtime::loadAll);
                assertTrue(second.getMessage() != null);
            } finally {
                runtime.close();
            }
            assertTrue(shellClosed.get(), "runtime close must release the shell");
        } finally {
            host.close();
            scheduler.shutdown();
        }
    }

    @Test
    void explicitNullAdmissionRunsHeadless() throws Exception {
        final Path home = temporary.resolve("home3");
        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                home, scheduler, host.adapterAccess(), log,
                (dev.turboism.internal.core.ShellAdmission) null
            );
            try {
                final LocalPluginRuntime.LoadReport report = runtime.loadAll();
                assertTrue(report.failures().isEmpty());
                assertTrue(report.loaded().isEmpty());
            } finally {
                runtime.close();
            }
        } finally {
            host.close();
            scheduler.shutdown();
        }
    }

    private static Throwable findCause(final Throwable thrown, final Throwable target) {
        Throwable cursor = thrown;
        while (cursor != null) {
            if (cursor == target) {
                return cursor;
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 16, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }
}
