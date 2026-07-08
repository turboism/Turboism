package dev.turboism.core.menu;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.CubismPermissionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeMenuRegistryPermissionTest {

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
    void contributeWithoutPermissionThrowsCubismPermissionException() {
        // Given
        RuntimeMenuRegistry registry = new RuntimeMenuRegistry(
            scheduler(),
            PLUGIN_ID,
            (permissionId, operation) -> { throw new CubismPermissionException(operation + " denied"); }
        );

        // When / Then
        CubismPermissionException exception = assertThrows(
            CubismPermissionException.class,
            () -> registry.contribute(new TestContribution("Probe", "probe.action", 100))
        );
        assertEquals("menu.contribute denied", exception.getMessage());
    }

    private RuntimeScheduler scheduler() {
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 4, events::add, CLOCK),
            SidecarDispatcher.noop(),
            events::add
        );
        return scheduler;
    }

    private record TestContribution(String menuPath, String actionId, int order) implements MenuRegistry.MenuContribution {
    }
}
