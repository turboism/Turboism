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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePluginManagementDuplicateActionTest {
    @TempDir Path home;

    @Test
    void repeatedDesiredStateActionsAreIdempotentAndNeverFailScheduler() throws Exception {
        installNow("example.plugin");
        final RuntimePluginManagementService service = new RuntimePluginManagementService(home, Optional::empty, List::of);
        final List<dev.turboism.core.diagnostics.PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        final RuntimeScheduler scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(500L, 1, 8, events::add, Clock.systemUTC()),
            SidecarDispatcher.noop(), events::add
        );
        final RuntimeActionRegistry actions = new RuntimeActionRegistry(
            scheduler, ignored -> { }, "turboism.core", PermissionChecker.allowAll()
        );
        final List<String> results = new CopyOnWriteArrayList<>();
        actions.register("disable", action("disable", ignored -> results.add(service.setEnabled("example.plugin", false).code())));
        actions.register("enable", action("enable", ignored -> results.add(service.setEnabled("example.plugin", true).code())));
        actions.register("uninstall", action("uninstall", ignored -> results.add(service.uninstall("example.plugin").code())));

        try {
            actions.execute("disable", new ActionRegistry.ActionContext() { });
            actions.execute("disable", new ActionRegistry.ActionContext() { });
            awaitSize(results, 2);
            assertEquals(List.of("PLUGIN_DISABLE_PENDING", "PLUGIN_DISABLE_PENDING"), results.subList(0, 2));

            actions.execute("enable", new ActionRegistry.ActionContext() { });
            actions.execute("enable", new ActionRegistry.ActionContext() { });
            awaitSize(results, 4);
            assertEquals(List.of("PLUGIN_ENABLE_PENDING", "PLUGIN_ENABLE_PENDING"), results.subList(2, 4));

            actions.execute("uninstall", new ActionRegistry.ActionContext() { });
            actions.execute("uninstall", new ActionRegistry.ActionContext() { });
            awaitSize(results, 6);
            assertEquals(List.of("PLUGIN_UNINSTALL_PENDING", "PLUGIN_UNINSTALL_PENDING"), results.subList(4, 6));
            assertFalse(events.stream().anyMatch(event -> event.phase().name().equals("FAILED")), events.toString());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    @Test
    void actionRouteContainsServiceFailureWithoutSchedulerFailure() throws Exception {
        installNow("example.plugin");
        Files.writeString(home.resolve("config.json"), "{}");
        final RuntimePluginManagementService service = new RuntimePluginManagementService(home, Optional::empty, List::of);
        final List<dev.turboism.core.diagnostics.PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        final RuntimeScheduler scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(500L, 1, 8, events::add, Clock.systemUTC()),
            SidecarDispatcher.noop(), events::add
        );
        final RuntimeActionRegistry actions = new RuntimeActionRegistry(
            scheduler, ignored -> { }, "turboism.core", PermissionChecker.allowAll()
        );
        final List<String> results = new CopyOnWriteArrayList<>();
        actions.register("disable", action("disable", ignored -> {
            try {
                results.add(service.setEnabled("example.plugin", false).code());
            } catch (RuntimeException failure) {
                results.add("PLUGIN_OPERATION_FAILED");
            }
        }));

        try {
            actions.execute("disable", new ActionRegistry.ActionContext() { });
            awaitSize(results, 1);
            assertEquals("PLUGIN_CONFIG_REJECTED", results.get(0));
            assertFalse(events.stream().anyMatch(event -> event.phase().name().equals("FAILED")), events.toString());
        } finally {
            service.close();
            scheduler.shutdown();
        }
    }

    private void installNow(final String id) throws Exception {
        final Path source = home.resolve(id + ".tplugin");
        Files.write(source, PluginManagementPackageFixture.packageBytes(id, "1.0.0"));
        final RuntimePluginManagementService service = new RuntimePluginManagementService(
            home, () -> Optional.of(source), List::of
        );
        try {
            assertTrue(service.install().accepted());
            assertTrue(RuntimePluginManagementService.applyPending(home).applied());
        } finally {
            service.close();
        }
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

    private static void awaitSize(final List<?> values, final int size) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (values.size() < size && System.nanoTime() < deadline) Thread.sleep(10L);
        assertEquals(size, values.size());
    }
}
