package dev.turboism.plugin.renderopt.service;

import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderStatusOverlayServiceTest {

    @Test
    void registerOverlayContributesExpectedOverlay() {
        RecordingUiHost uiHost = new RecordingUiHost();
        RenderStatusOverlayService service = new RenderStatusOverlayService(new FixedCubismRead(Optional.empty()), uiHost);

        service.registerOverlay();

        assertEquals(
            List.of(new OverlayContribution("render-status.overlay", "viewport", 50)),
            uiHost.overlays()
        );
    }

    @Test
    void closeRegistrationRemovesOverlay() {
        RecordingUiHost uiHost = new RecordingUiHost();
        RenderStatusOverlayService service = new RenderStatusOverlayService(new FixedCubismRead(Optional.empty()), uiHost);

        Registration registration = service.registerOverlay();
        registration.close();

        assertTrue(uiHost.overlays().isEmpty());
    }

    @Test
    void refreshStatusEmitsInfoWithFpsAndRendererWhenPresent() {
        RecordingUiHost uiHost = new RecordingUiHost();
        RenderStatusOverlayService service = new RenderStatusOverlayService(
            new FixedCubismRead(Optional.of(new RenderStatusSnapshot(true, 60.0, "fake-renderer"))),
            uiHost
        );

        service.refreshStatus();

        assertEquals(
            List.of(new StatusNotification(
                "render-status.overlay.refreshed",
                "INFO",
                "Render status: 60.0 FPS via fake-renderer"
            )),
            uiHost.notifications()
        );
    }

    @Test
    void refreshStatusEmitsWarningWhenRenderStatusIsUnavailable() {
        RecordingUiHost uiHost = new RecordingUiHost();
        RenderStatusOverlayService service = new RenderStatusOverlayService(new FixedCubismRead(Optional.empty()), uiHost);

        service.refreshStatus();

        assertEquals(
            List.of(new StatusNotification(
                "render-status.overlay.unavailable",
                "WARNING",
                "Render status is unavailable in this host."
            )),
            uiHost.notifications()
        );
    }

    private record FixedCubismRead(Optional<RenderStatusSnapshot> renderStatus) implements CubismReadCapabilityService {
        @Override public Optional<ProjectSnapshot> activeProject() { throw unsupported(); }
        @Override public Optional<DocumentSnapshot> activeDocument() { throw unsupported(); }
        @Override public Optional<ModelSnapshot> activeModel() { throw unsupported(); }
        @Override public SelectionSnapshot selection() { throw unsupported(); }
        @Override public List<ParameterSnapshot> parameters() { throw unsupported(); }
        @Override public List<ModelObjectSnapshot> modelObjects() { throw unsupported(); }
        @Override public List<ArtMeshSnapshot> meshes() { throw unsupported(); }
        @Override public List<DeformerSnapshot> deformers() { throw unsupported(); }
        @Override public List<PsdDocumentSnapshot> psdDocuments() { throw unsupported(); }
        @Override public List<ClipMaskSnapshot> clipMasks() { throw unsupported(); }
        @Override public List<TextureAtlasSnapshot> textureAtlases() { throw unsupported(); }
        @Override public Optional<WorkspaceSnapshot> workspace() { throw unsupported(); }
        @Override public Optional<ThemeStatusSnapshot> themeStatus() { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by render status overlay service test");
        }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        private final List<OverlayContribution> overlays = new ArrayList<>();
        private final List<StatusNotification> notifications = new ArrayList<>();

        List<OverlayContribution> overlays() {
            return overlays;
        }

        List<StatusNotification> notifications() {
            return notifications;
        }

        @Override
        public Registration contributeOverlay(OverlayContribution contribution) {
            overlays.add(contribution);
            return () -> overlays.remove(contribution);
        }

        @Override public ContextSourceSnapshot contextSource() { throw unsupported(); }
        @Override public ViewportSnapshot viewport() { throw unsupported(); }
        @Override public Registration openDialog(DialogRequest request) { throw unsupported(); }
        @Override public boolean confirmDialog(DialogRequest request) { throw unsupported(); }
        @Override public Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution) { throw unsupported(); }
        @Override public Optional<String> requestFile(FileChooserRequest request) { throw unsupported(); }

        @Override
        public Registration notifyStatus(StatusNotification notification) {
            notifications.add(notification);
            return () -> notifications.remove(notification);
        }

        @Override public Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution) { throw unsupported(); }
        @Override public Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution) { throw unsupported(); }
        @Override public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution) { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by render status overlay service test");
        }
    }
}
