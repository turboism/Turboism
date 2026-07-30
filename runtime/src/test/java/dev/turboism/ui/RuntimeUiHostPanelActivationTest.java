package dev.turboism.ui;

import dev.turboism.adapter.ui.StatusToolbarAdapterImpl;
import dev.turboism.adapter.ui.UiSurfaceAdapterImpl;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;
import dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeUiHostPanelActivationTest {

    @Test
    void scopesActivationToCallingPluginWithoutASeparateRiskPermission() {
        RuntimeEmbeddedPanelActivationCoordinator coordinator =
            new RuntimeEmbeddedPanelActivationCoordinator();
        List<String> activations = new ArrayList<>();
        coordinator.bind(
            5,
            (pluginId, panelId) -> activations.add(pluginId + ":" + panelId.value())
        );
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.from(List.of()),
            "plugin-a",
            UiHostStateSource.DEFAULT,
            new DisposableScope(),
            StatusToolbarAdapterImpl.safeMode(),
            UiSurfaceAdapterImpl.safeMode(),
            null,
            new EditorUiContributionAuthority(new RuntimeEditorUiHostLifecycle()),
            coordinator
        );

        service.activateEmbeddedPanel(EmbeddedPanelId.of("turboism.panel.main"));

        assertEquals(List.of("plugin-a:turboism.panel.main"), activations);
    }
}
