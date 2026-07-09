package dev.turboism.plugin.clipmask.service;

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

class ClipMaskInspectorServiceTest {

    @Test
    void registerPanelContributesExpectedPanel() {
        RecordingUiHost uiHost = new RecordingUiHost();
        ClipMaskInspectorService service = new ClipMaskInspectorService(new FixedCubismRead(List.of()), uiHost);

        service.registerPanel();

        assertEquals(
            List.of(new EmbeddedPanelContribution("clip-mask.inspector.panel", "Clip Mask Inspector", "side", 40)),
            uiHost.panels()
        );
    }

    @Test
    void openInspectorDialogContributesStaticReadyBodyWithoutReadingMasks() {
        RecordingUiHost uiHost = new RecordingUiHost();
        ClipMaskInspectorService service = new ClipMaskInspectorService(new FixedCubismRead(List.of()), uiHost);

        service.openInspectorDialog();

        assertEquals(
            List.of(new DialogRequest(
                "clip-mask.inspector.dialog",
                "Clip Mask Inspector",
                "Clip Mask Inspector is ready. Use Inspect to refresh status."
            )),
            uiHost.dialogs()
        );
    }

    @Test
    void closeRegistrationRemovesPanelAndDialog() {
        RecordingUiHost uiHost = new RecordingUiHost();
        ClipMaskInspectorService service = new ClipMaskInspectorService(new FixedCubismRead(List.of()), uiHost);

        Registration panel = service.registerPanel();
        Registration dialog = service.openInspectorDialog();
        panel.close();
        dialog.close();

        assertTrue(uiHost.panels().isEmpty());
        assertTrue(uiHost.dialogs().isEmpty());
    }

    @Test
    void inspectEmitsInfoSummaryWhenMasksPresent() {
        RecordingUiHost uiHost = new RecordingUiHost();
        List<ClipMaskSnapshot> masks = List.of(
            new ClipMaskSnapshot("mask-1", List.of("mesh-src"), List.of("mesh-a", "mesh-b"), true),
            new ClipMaskSnapshot("mask-2", List.of("mesh-src-2"), List.of("mesh-c"), false)
        );
        ClipMaskInspectorService service = new ClipMaskInspectorService(new FixedCubismRead(masks), uiHost);

        service.inspect();

        assertEquals(
            List.of(new StatusNotification(
                "clip-mask.inspector.refreshed",
                "INFO",
                "Clip masks: 2 total, 1 enabled, 3 clipped mesh refs"
            )),
            uiHost.notifications()
        );
    }

    @Test
    void inspectEmitsWarningWhenNoMasks() {
        RecordingUiHost uiHost = new RecordingUiHost();
        ClipMaskInspectorService service = new ClipMaskInspectorService(new FixedCubismRead(List.of()), uiHost);

        service.inspect();

        assertEquals(
            List.of(new StatusNotification(
                "clip-mask.inspector.unavailable",
                "WARNING",
                "No clip masks are available in this host."
            )),
            uiHost.notifications()
        );
    }

    private record FixedCubismRead(List<ClipMaskSnapshot> clipMasks) implements CubismReadCapabilityService {
        @Override public Optional<ProjectSnapshot> activeProject() { throw unsupported(); }
        @Override public Optional<DocumentSnapshot> activeDocument() { throw unsupported(); }
        @Override public Optional<ModelSnapshot> activeModel() { throw unsupported(); }
        @Override public SelectionSnapshot selection() {
            return new SelectionSnapshot(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
        }
        @Override public List<ParameterSnapshot> parameters() { throw unsupported(); }
        @Override public List<ModelObjectSnapshot> modelObjects() { throw unsupported(); }
        @Override public List<ArtMeshSnapshot> meshes() { return List.of(); }
        @Override public List<DeformerSnapshot> deformers() { throw unsupported(); }
        @Override public List<PsdDocumentSnapshot> psdDocuments() { throw unsupported(); }
        @Override public List<ClipMaskSnapshot> clipMasks() { return clipMasks; }
        @Override public List<TextureAtlasSnapshot> textureAtlases() { throw unsupported(); }
        @Override public Optional<RenderStatusSnapshot> renderStatus() { throw unsupported(); }
        @Override public Optional<WorkspaceSnapshot> workspace() { throw unsupported(); }
        @Override public Optional<ThemeStatusSnapshot> themeStatus() { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by clip-mask inspector service test");
        }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        private final List<EmbeddedPanelContribution> panels = new ArrayList<>();
        private final List<DialogRequest> dialogs = new ArrayList<>();
        private final List<StatusNotification> notifications = new ArrayList<>();

        List<EmbeddedPanelContribution> panels() { return panels; }
        List<DialogRequest> dialogs() { return dialogs; }
        List<StatusNotification> notifications() { return notifications; }

        @Override
        public Registration contributeOverlay(OverlayContribution contribution) { throw unsupported(); }

        @Override public ContextSourceSnapshot contextSource() { throw unsupported(); }
        @Override public ViewportSnapshot viewport() { throw unsupported(); }

        @Override
        public Registration openDialog(DialogRequest request) {
            dialogs.add(request);
            return () -> dialogs.remove(request);
        }

        @Override public boolean confirmDialog(DialogRequest request) { throw unsupported(); }

        @Override
        public Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution) {
            panels.add(contribution);
            return () -> panels.remove(contribution);
        }

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
            return new UnsupportedOperationException("not used by clip-mask inspector service test");
        }
    }
}
