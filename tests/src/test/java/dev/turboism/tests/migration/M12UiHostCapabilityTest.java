package dev.turboism.tests.migration;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.RuntimeUiHostCapabilityService;
import dev.turboism.ui.UiHostStateSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M12UiHostCapabilityTest {

    @Test
    void uiHostServiceRegistersAllM12UiDescriptorsAndCleansUpIdempotently() {
        RuntimeUiHostCapabilityService service = serviceWithAllPermissions();

        Registration overlay = service.contributeOverlay(new OverlayContribution("overlay-1", "viewport", 10));
        Registration dialog = service.openDialog(new DialogRequest("dialog-1", "Inspect", "Body"));
        Registration panel = service.contributeEmbeddedPanel(new EmbeddedPanelContribution("panel-1", "Panel", "right", 20));
        Registration status = service.notifyStatus(new StatusNotification("status-1", "INFO", "Ready"));
        Registration context = service.contributeContextMenu(new ContextMenuRegistry.ContextMenuContribution("ctx-1", "Inspect", null, "parameter", 10));
        Registration mainToolbar = service.contributeMainToolbar(new MainToolbarRegistry.MainToolbarContribution("main-1", "action.main", "label.main", "icons/main.svg", "right", 10));
        Registration paletteToolbar = service.contributePaletteToolbar(new PaletteToolbarRegistry.PaletteToolbarContribution("palette-1", "action.palette", "label.palette", "icons/palette.svg", "LOG", "right", 10));

        assertEquals(1, service.overlays().size());
        assertEquals(1, service.dialogs().size());
        assertEquals(1, service.panels().size());
        assertEquals(1, service.notifications().size());
        assertEquals(1, service.contextMenus().size());
        assertEquals(1, service.mainToolbars().size());
        assertEquals(1, service.paletteToolbars().size());

        overlay.close();
        overlay.close();
        dialog.close();
        panel.close();
        status.close();
        context.close();
        mainToolbar.close();
        paletteToolbar.close();

        assertTrue(service.overlays().isEmpty());
        assertTrue(service.dialogs().isEmpty());
        assertTrue(service.panels().isEmpty());
        assertTrue(service.notifications().isEmpty());
        assertTrue(service.contextMenus().isEmpty());
        assertTrue(service.mainToolbars().isEmpty());
        assertTrue(service.paletteToolbars().isEmpty());
    }

    @Test
    void uiHostServiceUsesTypedViewportAndFileChooserResponsesOnly() {
        UiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.from(allPermissions()),
            "plugin.demo",
            new TestUiHostStateSource()
        );

        assertEquals(new ViewportSnapshot("viewport-1", 1280, 720, 1.5), service.viewport());
        assertEquals(Optional.of("workspace/model.psd"), service.requestFile(new FileChooserRequest("file-1", "Open PSD", List.of("psd"))));
    }

    @Test
    void uiHostServiceDeniesMissingPermissionsBeforeRecordingDescriptors() {
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.from(List.of(permission(RuntimeUiHostCapabilityService.UI_STATUS_NOTIFY))),
            "plugin.demo",
            new TestUiHostStateSource()
        );

        assertThrows(CubismPermissionException.class, () -> service.contributeOverlay(new OverlayContribution("overlay-1", "viewport", 10)));
        assertTrue(service.overlays().isEmpty());
        assertThrows(CubismPermissionException.class, () -> service.openDialog(new DialogRequest("dialog-1", "Dialog", "Body")));
        assertTrue(service.dialogs().isEmpty());
        assertThrows(CubismPermissionException.class, () -> service.contributeEmbeddedPanel(new EmbeddedPanelContribution("panel-1", "Panel", "right", 20)));
        assertTrue(service.panels().isEmpty());

        Registration notification = service.notifyStatus(new StatusNotification("status-1", "INFO", "Ready"));
        assertEquals(1, service.notifications().size());
        notification.close();
        assertTrue(service.notifications().isEmpty());
    }

    @Test
    void uiHostReturnedListsAreImmutable() {
        RuntimeUiHostCapabilityService service = serviceWithAllPermissions();
        service.contributeOverlay(new OverlayContribution("overlay-1", "viewport", 10));

        assertThrows(UnsupportedOperationException.class, () -> service.overlays().add(new OverlayContribution("overlay-2", "viewport", 20)));
    }

    private static RuntimeUiHostCapabilityService serviceWithAllPermissions() {
        return new RuntimeUiHostCapabilityService(
            PermissionChecker.from(allPermissions()),
            "plugin.demo",
            new TestUiHostStateSource()
        );
    }

    private static List<PluginPermission> allPermissions() {
        return List.of(
            permission(RuntimeUiHostCapabilityService.UI_CONTEXT_SOURCE_READ),
            permission(RuntimeUiHostCapabilityService.UI_OVERLAY_CONTRIBUTE),
            permission(RuntimeUiHostCapabilityService.UI_DIALOG_CONTRIBUTE),
            permission(RuntimeUiHostCapabilityService.UI_PANEL_CONTRIBUTE),
            permission(RuntimeUiHostCapabilityService.UI_FILE_CHOOSER_REQUEST),
            permission(RuntimeUiHostCapabilityService.UI_STATUS_NOTIFY),
            permission(RuntimeUiHostCapabilityService.UI_TOOLBAR_CONTRIBUTE),
            permission(RuntimeUiHostCapabilityService.UI_CONTEXT_MENU_CONTRIBUTE)
        );
    }

    private static PluginPermission permission(String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "application"; }
            @Override public String reason() { return "test"; }
        };
    }

    private static final class TestUiHostStateSource implements UiHostStateSource {
        @Override
        public ViewportSnapshot viewport() {
            return new ViewportSnapshot("viewport-1", 1280, 720, 1.5);
        }

        @Override
        public Optional<String> chooseFile(FileChooserRequest request) {
            return Optional.of("workspace/model." + request.allowedExtensions().get(0));
        }
    }
}
