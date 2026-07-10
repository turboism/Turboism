package dev.turboism.plugin.mesh;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DeformerType;
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
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
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

class MeshPluginTest {

    @Test
    void enableRegistersInspectAction() {
        TestPluginLogger logger = new TestPluginLogger();
        RecordingPluginContext context = new RecordingPluginContext(logger);
        MeshPlugin plugin = new MeshPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions().execute("mesh.inspector.inspect");

        assertEquals(List.of("mesh.inspector.inspect"),
            context.actions().actions().stream().map(ActionRegistry.Action::id).toList());
        assertEquals(
            List.of(new StatusNotification(
                "mesh.inspector.refreshed",
                "INFO",
                "Meshes: 1, deformers: 0, context: workspace"
            )),
            context.uiHost().notifications()
        );
    }

    @Test
    void disposableScopeClosesAction() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        MeshPlugin plugin = new MeshPlugin();
        plugin.init(context);
        plugin.enable();
        context.disposableScope().close();
        assertTrue(context.actions().actions().isEmpty());
    }

    private static final class RecordingPluginContext implements PluginContext {
        private final DisposableScope disposableScope = new DisposableScope();
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingUiHost uiHost = new RecordingUiHost();
        private final PluginLogger logger;

        RecordingPluginContext(PluginLogger logger) { this.logger = logger; }

        @Override public PluginDescriptor descriptor() { throw new UnsupportedOperationException(); }
        @Override public PluginLogger logger() { return logger; }
        @Override public PluginPaths paths() { throw new UnsupportedOperationException(); }
        @Override public CubismFacade cubism() { throw new UnsupportedOperationException(); }
        @Override public CubismReadCapabilityService cubismRead() { return new FixedCubismRead(); }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { throw new UnsupportedOperationException(); }
        @Override public RecordingActionRegistry actions() { return actions; }
        @Override public MenuRegistry menus() { throw new UnsupportedOperationException(); }
        @Override public UiScheduler uiScheduler() { throw new UnsupportedOperationException(); }
        @Override public DiagnosticReport diagnostics() { throw new UnsupportedOperationException(); }
        @Override public DisposableScope disposableScope() { return disposableScope; }
        @Override public RecordingUiHost uiHost() { return uiHost; }
    }

    private static final class FixedCubismRead implements CubismReadCapabilityService {
        @Override public Optional<ProjectSnapshot> activeProject() { throw unsupported(); }
        @Override public Optional<DocumentSnapshot> activeDocument() { throw unsupported(); }
        @Override public Optional<ModelSnapshot> activeModel() { throw unsupported(); }
        @Override public SelectionSnapshot selection() { throw unsupported(); }
        @Override public List<ParameterSnapshot> parameters() { throw unsupported(); }
        @Override public List<ModelObjectSnapshot> modelObjects() { throw unsupported(); }
        @Override public List<ArtMeshSnapshot> meshes() {
            return List.of(new ArtMeshSnapshot("m1", "Mesh1", Optional.empty(), true, true));
        }
        @Override public List<DeformerSnapshot> deformers() { return List.of(); }
        @Override public List<PsdDocumentSnapshot> psdDocuments() { throw unsupported(); }
        @Override public List<ClipMaskSnapshot> clipMasks() { throw unsupported(); }
        @Override public List<TextureAtlasSnapshot> textureAtlases() { throw unsupported(); }
        @Override public Optional<RenderStatusSnapshot> renderStatus() { throw unsupported(); }
        @Override public Optional<WorkspaceSnapshot> workspace() { throw unsupported(); }
        @Override public Optional<ThemeStatusSnapshot> themeStatus() { throw unsupported(); }
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    private static final class RecordingActionRegistry implements ActionRegistry {
        private final List<Action> actions = new ArrayList<>();
        List<Action> actions() { return actions; }
        @Override public Registration register(String id, Action action) {
            actions.add(action);
            return () -> actions.remove(action);
        }
        void execute(String id) {
            actions.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow()
                .handler().accept(new ActionContext() {});
        }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        private final List<StatusNotification> notifications = new ArrayList<>();
        List<StatusNotification> notifications() { return notifications; }
        @Override public Registration contributeOverlay(OverlayContribution contribution) { throw unsupported(); }
        @Override public ContextSourceSnapshot contextSource() {
            return new ContextSourceSnapshot(
                "ctx-1", "workspace", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
            );
        }
        @Override public ViewportSnapshot viewport() { throw unsupported(); }
        @Override public Registration openDialog(DialogRequest request) { throw unsupported(); }
        @Override public boolean confirmDialog(DialogRequest request) { throw unsupported(); }
        @Override public Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution) { throw unsupported(); }
        @Override public Optional<String> requestFile(FileChooserRequest request) { throw unsupported(); }
        @Override public Registration notifyStatus(StatusNotification notification) {
            notifications.add(notification);
            return () -> notifications.remove(notification);
        }
        @Override public Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution) { throw unsupported(); }
        @Override public Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution) { throw unsupported(); }
        @Override public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution) { throw unsupported(); }
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    private static final class TestPluginLogger implements PluginLogger {
        private final List<String> messages = new ArrayList<>();
        @Override public void debug(String message) { messages.add("DEBUG: " + message); }
        @Override public void info(String message) { messages.add("INFO: " + message); }
        @Override public void warn(String message) { messages.add("WARN: " + message); }
        @Override public void error(String message) { messages.add("ERROR: " + message); }
        @Override public void error(String message, Throwable throwable) {
            messages.add("ERROR: " + message + ": " + throwable.getMessage());
        }
    }
}
