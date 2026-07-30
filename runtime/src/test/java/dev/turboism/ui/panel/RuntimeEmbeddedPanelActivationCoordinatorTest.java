package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeEmbeddedPanelActivationCoordinatorTest {

    private static final EmbeddedPanelId PANEL_ID = EmbeddedPanelId.of("turboism.panel.main");

    @Test
    void routesOnlyWhileOneCurrentGenerationTargetIsBound() {
        RuntimeEmbeddedPanelActivationCoordinator coordinator =
            new RuntimeEmbeddedPanelActivationCoordinator();
        List<String> activations = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> coordinator.activate("plugin-a", PANEL_ID));

        Registration first = coordinator.bind(
            3,
            (pluginId, panelId) -> activations.add("3:" + pluginId + ":" + panelId.value())
        );
        coordinator.activate("plugin-a", PANEL_ID);
        assertThrows(
            IllegalStateException.class,
            () -> coordinator.bind(4, (pluginId, panelId) -> { })
        );

        first.close();
        Registration second = coordinator.bind(
            4,
            (pluginId, panelId) -> activations.add("4:" + pluginId + ":" + panelId.value())
        );
        first.close();
        coordinator.activate("plugin-a", PANEL_ID);
        second.close();

        assertEquals(
            List.of(
                "3:plugin-a:turboism.panel.main",
                "4:plugin-a:turboism.panel.main"
            ),
            activations
        );
        assertThrows(IllegalStateException.class, () -> coordinator.activate("plugin-a", PANEL_ID));
    }

    @Test
    void closePermanentlyFailsClosed() {
        RuntimeEmbeddedPanelActivationCoordinator coordinator =
            new RuntimeEmbeddedPanelActivationCoordinator();
        coordinator.close();

        assertThrows(
            IllegalStateException.class,
            () -> coordinator.bind(1, (pluginId, panelId) -> { })
        );
        assertThrows(IllegalStateException.class, () -> coordinator.activate("plugin-a", PANEL_ID));
    }
}
