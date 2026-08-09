package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PanelTabMenuCoordinatorTest {

    @Test
    void replacesNativeBindingWhenPolicyChangesAndCleansUp() {
        PanelTabMenuCoordinator coordinator = new PanelTabMenuCoordinator();
        List<String> installed = new ArrayList<>();
        List<String> closed = new ArrayList<>();
        PanelTabMenuCoordinator.Host host = contributions -> {
            String id = contributions.get(0).contribution().id();
            installed.add(id);
            return () -> closed.add(id);
        };
        Registration binding = coordinator.bindHost(host);
        ContextMenuRegistry.ContextMenuContribution first = contribution("first");
        ContextMenuRegistry.ContextMenuContribution second = contribution("second");

        coordinator.update(7, List.of(new PanelTabMenuContribution(7, "plugin", first)));
        coordinator.update(7, List.of(new PanelTabMenuContribution(7, "plugin", second)));
        binding.close();
        coordinator.close();

        assertEquals(List.of("first", "second"), installed);
        assertEquals(List.of("first", "second"), closed);
    }

    private static ContextMenuRegistry.ContextMenuContribution contribution(final String id) {
        return new ContextMenuRegistry.ContextMenuContribution(
            id,
            "action." + id,
            id,
            null,
            "panel.docked",
            ContextMenuRegistry.Location.WORKSPACE_OBJECT,
            java.util.Set.of(),
            100,
            ContextMenuRegistry.Target.PANEL_TAB,
            ContextMenuRegistry.Operation.TOGGLE_PANEL_FLOATING,
            ContextMenuRegistry.ContextMenuEntry.item(id, id, "action." + id),
            ContextMenuRegistry.Placement.last()
        );
    }
}
