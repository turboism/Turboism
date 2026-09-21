package dev.turboism.preview;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.internal.core.CorePluginEntrypoint;
import dev.turboism.internal.core.CorePluginServices;
import dev.turboism.sdk.plugin.TurboismPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the runtime-owned core composition seam: a failing entrypoint must produce a useful
 * diagnostic, close the runtime, and a completed runtime must never load the core twice.
 */
class LocalPluginRuntimeCoreEntrypointTest {

    @TempDir
    Path temporary;

    @Test
    void failedCoreCompositionClosesRuntimeWithDiagnostic() throws Exception {
        final Path home = temporary.resolve("home");
        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        final IllegalStateException compositionFailure =
            new IllegalStateException("core-compose-boom");
        final CorePluginEntrypoint failing = new CorePluginEntrypoint() {
            @Override
            public TurboismPlugin createPlugin(final CorePluginServices services) {
                throw compositionFailure;
            }

            @Override
            public ClassLoader pluginClassLoader() {
                return getClass().getClassLoader();
            }
        };

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime =
                new LocalPluginRuntime(home, scheduler, host.adapterAccess(), log, failing);
            final IllegalStateException thrown =
                assertThrows(IllegalStateException.class, runtime::loadAll);
            assertEquals("Runtime-owned core failed to load", thrown.getMessage());
            assertNotNull(
                findCause(thrown, compositionFailure),
                "the composition failure must surface in the diagnostic chain"
            );
            // The runtime closed itself before propagating; a retry is rejected, not re-driven.
            assertThrows(IllegalStateException.class, runtime::loadAll);
        } finally {
            host.close();
            scheduler.shutdown();
        }
    }

    @Test
    void successfulRuntimeRejectsASecondLoad() throws Exception {
        final Path home = temporary.resolve("home2");
        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                home, scheduler, host.adapterAccess(), log,
                new dev.turboism.plugin.core.MainToolbarPluginEntrypoint()
            );
            try {
                runtime.loadAll();
                assertTrue(runtime.loadedPlugins().stream()
                    .anyMatch(plugin -> plugin.id().equals(
                        dev.turboism.internal.core.CorePluginManagement.CORE_PLUGIN_ID)));
                final IllegalStateException second =
                    assertThrows(IllegalStateException.class, runtime::loadAll);
                assertTrue(second.getMessage() != null);
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
