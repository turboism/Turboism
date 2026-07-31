package dev.turboism.pluginmanagement;

import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.action.ActionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePluginManagementInstallWorkflowTest {
    @TempDir Path home;

    @Test
    void chooserMayOutliveLightweightBudgetBeforeBoundedInstallRuns() throws Exception {
        final Path source = home.resolve("sample.tplugin");
        Files.write(source, PluginManagementPackageFixture.packageBytes("example.plugin", "1.0.0"));
        final ControlledChooser chooser = new ControlledChooser();
        final List<dev.turboism.core.diagnostics.PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        final RuntimeScheduler scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(50L, 1, 8, events::add, Clock.systemUTC()),
            SidecarDispatcher.noop(), events::add
        );
        final RuntimePluginManagementService service = new RuntimePluginManagementService(home, chooser, List::of);
        final CountDownLatch completed = new CountDownLatch(1);
        final List<dev.turboism.plugin.core.CorePluginManagement.OperationResult> results = new CopyOnWriteArrayList<>();
        final RuntimeActionRegistry actions = new RuntimeActionRegistry(
            scheduler, ignored -> { }, "turboism.core", PermissionChecker.allowAll()
        );
        actions.register("install", action("install", ignored -> service.requestInstall(result -> {
            results.add(result);
            completed.countDown();
        })));

        try {
            actions.execute("install", new ActionRegistry.ActionContext() { });
            assertTrue(chooser.opened.await(1, TimeUnit.SECONDS));
            Thread.sleep(120L);
            assertFalse(events.stream().anyMatch(event -> event.phase().name().equals("TIMED_OUT")), events.toString());
            assertTrue(results.isEmpty());

            chooser.complete(Optional.of(source));
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals("PLUGIN_INSTALL_PENDING", results.get(0).code());
            assertTrue(Files.isRegularFile(home.resolve("state/runtime/plugin-management/pending.json")));
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void chooserCancellationDoesNotSubmitInstallOrReportSchedulerTimeout() throws Exception {
        final ControlledChooser chooser = new ControlledChooser();
        final List<dev.turboism.core.diagnostics.PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        final RuntimeScheduler scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(50L, 1, 8, events::add, Clock.systemUTC()),
            SidecarDispatcher.noop(), events::add
        );
        final RuntimePluginManagementService service = new RuntimePluginManagementService(home, chooser, List::of);
        final CountDownLatch completed = new CountDownLatch(1);
        final List<dev.turboism.plugin.core.CorePluginManagement.OperationResult> results = new CopyOnWriteArrayList<>();
        final RuntimeActionRegistry actions = new RuntimeActionRegistry(
            scheduler, ignored -> { }, "turboism.core", PermissionChecker.allowAll()
        );
        actions.register("install", action("install", ignored -> service.requestInstall(result -> {
            results.add(result);
            completed.countDown();
        })));

        try {
            actions.execute("install", new ActionRegistry.ActionContext() { });
            assertTrue(chooser.opened.await(1, TimeUnit.SECONDS));
            Thread.sleep(120L);
            chooser.complete(Optional.empty());
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals("PLUGIN_INSTALL_CANCELLED", results.get(0).code());
            assertFalse(events.stream().anyMatch(event -> event.phase().name().equals("TIMED_OUT")), events.toString());
            assertFalse(Files.exists(home.resolve("state/runtime/plugin-management/pending.json")));
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void closeCancelsPendingChooserAndFencesLateSelection() throws Exception {
        final Path source = home.resolve("sample.tplugin");
        Files.write(source, PluginManagementPackageFixture.packageBytes("example.plugin", "1.0.0"));
        final ControlledChooser chooser = new ControlledChooser();
        final RuntimePluginManagementService service = new RuntimePluginManagementService(home, chooser, List::of);
        final List<dev.turboism.plugin.core.CorePluginManagement.OperationResult> results = new CopyOnWriteArrayList<>();

        service.requestInstall(results::add);
        assertTrue(chooser.opened.await(1, TimeUnit.SECONDS));
        service.close();
        chooser.complete(Optional.of(source));
        Thread.sleep(100L);

        assertTrue(chooser.closed.get());
        assertTrue(results.isEmpty());
        assertFalse(Files.exists(home.resolve("state/runtime/plugin-management/pending.json")));
    }

    private static ActionRegistry.Action action(
        final String id,
        final Consumer<ActionRegistry.ActionContext> handler
    ) {
        return new ActionRegistry.Action() {
            @Override public String id() { return id; }
            @Override public String label() { return id; }
            @Override public Consumer<ActionRegistry.ActionContext> handler() { return handler; }
        };
    }

    private static final class ControlledChooser implements RuntimePluginManagementService.PackageChooser {
        private final CountDownLatch opened = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Consumer<Optional<Path>> completion;

        @Override public void choose(final Consumer<Optional<Path>> completion) {
            this.completion = completion;
            opened.countDown();
        }

        void complete(final Optional<Path> selection) {
            final Consumer<Optional<Path>> current = completion;
            if (current != null) current.accept(selection);
        }

        @Override public void close() {
            closed.set(true);
        }
    }
}
