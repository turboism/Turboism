package dev.turboism.core.action;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeActionRegistryPermissionTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.test";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    private RuntimeScheduler scheduler;

    @AfterEach
    void shutdownScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void registerWithoutPermissionThrowsCubismPermissionException() {
        // Given
        RuntimeActionRegistry registry = registry((permissionId, operation) -> {
            throw new CubismPermissionException(operation + " denied");
        });

        // When / Then
        CubismPermissionException exception = assertThrows(
            CubismPermissionException.class,
            () -> registry.register("probe.action", action())
        );
        assertEquals("action.register denied", exception.getMessage());
    }

    @Test
    void executeWithoutPermissionThrowsCubismPermissionException() {
        // Given
        RuntimeActionRegistry registry = registry((permissionId, operation) -> {
            if ("action.execute".equals(operation)) {
                throw new CubismPermissionException(operation + " denied");
            }
        });
        Registration registration = registry.register("probe.action", action());

        // When / Then
        CubismPermissionException exception = assertThrows(
            CubismPermissionException.class,
            () -> registry.execute("probe.action", new ActionRegistry.ActionContext() {})
        );
        assertEquals("action.execute denied", exception.getMessage());
        registration.close();
    }

    private RuntimeActionRegistry registry(dev.turboism.permissions.PermissionChecker permissionChecker) {
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 4, events::add, CLOCK),
            SidecarDispatcher.noop(),
            events::add
        );
        List<StartupReport.DiagnosticProblem> problems = new CopyOnWriteArrayList<>();
        return new RuntimeActionRegistry(scheduler, problems::add, PLUGIN_ID, permissionChecker);
    }

    private static TestAction action() {
        return new TestAction("probe.action", "Probe Action", context -> { });
    }

    private record TestAction(
        String id,
        String label,
        Consumer<ActionRegistry.ActionContext> handler
    ) implements ActionRegistry.Action {
    }
}
