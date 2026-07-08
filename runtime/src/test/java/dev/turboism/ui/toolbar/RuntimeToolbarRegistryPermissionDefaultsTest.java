package dev.turboism.ui.toolbar;

import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeToolbarRegistryPermissionDefaultsTest {

    @Test
    void mainToolbarRegistryRejectsContributionWhenNoPermission() {
        // Given
        PermissionChecker checker = PermissionChecker.from(
            new CubismPermissionGate(
                "plugin",
                List.of(),
                event -> { },
                Clock.systemUTC()
            )
        );
        RuntimeScheduler scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 2, event -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            event -> { }
        );
        RuntimeMainToolbarRegistry registry = new RuntimeMainToolbarRegistry(checker, scheduler, "plugin");

        // Then
        CubismPermissionException exception = assertThrows(CubismPermissionException.class, () ->
            registry.contribute(new MainToolbarRegistry.MainToolbarContribution(
                "test", "test", "label", "icon", "end", 1
            ))
        );
        assertTrue(exception.getMessage().contains(PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE));
    }
}
