package dev.turboism.ui;

import dev.turboism.adapter.ui.StatusToolbarAdapterImpl;
import dev.turboism.adapter.ui.UiSurfaceAdapterImpl;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;
import dev.turboism.ui.panel.PanelCollapsibleContentCoordinator;
import dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void collapsibleSectionContributionIsRejectedWithoutPanelContributePermission() {
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.from(List.of()),
            "plugin-a",
            UiHostStateSource.DEFAULT,
            new DisposableScope(),
            StatusToolbarAdapterImpl.safeMode(),
            UiSurfaceAdapterImpl.safeMode(),
            null,
            new EditorUiContributionAuthority(new RuntimeEditorUiHostLifecycle()),
            new RuntimeEmbeddedPanelActivationCoordinator()
        );

        CubismPermissionException failure = assertThrows(CubismPermissionException.class, () ->
            service.contributeCollapsibleSection(new CollapsibleSectionContribution(
                EmbeddedPanelId.of("turboism.panel.main"),
                "status",
                "Status",
                0,
                true,
                PanelView.column(PanelView.text("state")))));
        assertTrue(failure.getMessage().contains("turboism.ui.panel.contribute"));
    }

    @Test
    void collapsibleSectionContributionRoutesToContentCoordinatorAndScopesCleanup() {
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin-a",
            UiHostStateSource.DEFAULT,
            new DisposableScope(),
            StatusToolbarAdapterImpl.safeMode(),
            UiSurfaceAdapterImpl.safeMode(),
            null,
            new EditorUiContributionAuthority(new RuntimeEditorUiHostLifecycle()),
            new RuntimeEmbeddedPanelActivationCoordinator()
        );
        final EmbeddedPanelId panel = EmbeddedPanelId.of("turboism.panel.main");
        final PanelCollapsibleContentCoordinator coordinator =
            PanelCollapsibleContentCoordinator.shared();
        final Registration registration = service.contributeCollapsibleSection(
            new CollapsibleSectionContribution(
                panel, "status", "Status", 0, true,
                PanelView.column(PanelView.text("state"))));
        try {
            assertEquals(1, coordinator.injectedSections(panel).size());
        } finally {
            // 兜底清理共享内容表，避免断言失败时污染其他用例。
            registration.close();
        }
        assertTrue(coordinator.injectedSections(panel).isEmpty());
    }
}
