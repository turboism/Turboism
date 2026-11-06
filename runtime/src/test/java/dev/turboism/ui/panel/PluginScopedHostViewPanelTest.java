package dev.turboism.ui.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.HostViewAnchors;
import dev.turboism.sdk.ui.HostViewPanelHandle;
import dev.turboism.sdk.ui.PanelView;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class PluginScopedHostViewPanelTest {

    private static final String ANCHOR = HostViewAnchors.TEXTURE_ATLAS_EDITOR;

    @Test
    void attachDemandsThePanelContributionPermission() {
        final RuntimeHostViewPanelRegistry registry = new RuntimeHostViewPanelRegistry();
        final List<String> refusals = new ArrayList<>();
        final PluginScopedHostViewPanel panels = new PluginScopedHostViewPanel(
                registry,
                (permissionId, operation) -> {
                    refusals.add(permissionId + "/" + operation);
                    throw new CubismPermissionException("permission not granted: " + permissionId);
                },
                new DisposableScope());
        final JPanel view = new JPanel(new BorderLayout());
        registry.ingress(ANCHOR).accept(view);

        assertThrows(CubismPermissionException.class, () -> panels.attach(ANCHOR, PanelView.text("line"), 0));

        assertEquals(List.of("turboism.ui.panel.contribute/ui.host-view-panel.contribute"), refusals);
        assertEquals(0, view.getComponentCount(), "a refused attachment never reaches the host view");
        registry.close();
    }

    @Test
    void attachedHandlesAreReleasedWithThePluginGenerationScope() throws Exception {
        final RuntimeHostViewPanelRegistry registry = new RuntimeHostViewPanelRegistry();
        final JPanel view = new JPanel(new BorderLayout());
        registry.ingress(ANCHOR).accept(view);
        final DisposableScope scope = new DisposableScope();
        final PluginScopedHostViewPanel panels =
                new PluginScopedHostViewPanel(registry, PermissionChecker.allowAll(), scope);

        final HostViewPanelHandle handle = panels.attach(ANCHOR, PanelView.text("line"), 0);
        assertEquals(1, ((JPanel) view.getComponent(0)).getComponentCount());

        scope.close();

        assertEquals(0, view.getComponentCount(), "the generation fence removes the panel from the host view");
        assertFalse(handle.isVisible());
        handle.setContent(PanelView.text("late"));
        assertEquals(0, view.getComponentCount(), "a fenced update is dropped silently");
        registry.close();
    }

    @Test
    void aSealedScopeReportsUnavailableAndNeverOrphansAnAttachment() {
        final RuntimeHostViewPanelRegistry registry = new RuntimeHostViewPanelRegistry();
        final JPanel view = new JPanel(new BorderLayout());
        registry.ingress(ANCHOR).accept(view);
        final DisposableScope scope = new DisposableScope();
        final PluginScopedHostViewPanel panels =
                new PluginScopedHostViewPanel(registry, PermissionChecker.allowAll(), scope);

        assertTrue(panels.isAvailable());

        scope.seal();

        assertFalse(panels.isAvailable(), "a fenced plugin generation has no usable host view surface");
        assertThrows(IllegalStateException.class, () -> panels.attach(ANCHOR, PanelView.text("line"), 0));
        assertEquals(0, view.getComponentCount(), "the refused attachment is closed instead of orphaned");
        registry.close();
        assertFalse(panels.isAvailable());
    }
}
