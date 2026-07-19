package dev.turboism.plugin.renderopt;

import dev.turboism.plugin.renderopt.b1.application.DefaultPluginConfigRegistry;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.CubismFacade;
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

class RenderOptPluginTest {

    @Test
    void enableRegistersRefreshActionAndOverlay() {
        TestPluginLogger logger = new TestPluginLogger();
        RecordingPluginContext context = new RecordingPluginContext(logger);
        RenderOptPlugin plugin = new RenderOptPlugin();

        plugin.init(context);
        plugin.enable();

        context.actions().execute("render-status.overlay.refresh");

        assertEquals(List.of("render-status.overlay.refresh"), context.actions().actions().stream().map(ActionRegistry.Action::id).toList());
        assertEquals(List.of(new OverlayContribution("render-status.overlay", "viewport", 50)), context.uiHost().overlays());
        assertEquals(
            List.of(new StatusNotification(
                "render-status.overlay.refreshed",
                "INFO",
                "Render status: 60.0 FPS via fake-renderer"
            )),
            context.uiHost().notifications()
        );
        assertEquals(
            List.of(
                "INFO: RenderOptPlugin initialized",
                "INFO: RenderOptPlugin enabled: render status overlay contribution enrolled in disposable scope"
            ),
            logger.messages()
        );
    }

    @Test
    void disposableScopeClosesActionOverlayAndLifecycleProvider() throws Exception {
        TestPluginLogger logger = new TestPluginLogger();
        RecordingPluginContext context = new RecordingPluginContext(logger);
        RenderOptPlugin plugin = new RenderOptPlugin();

        plugin.init(context);
        plugin.enable();
        context.disposableScope().close();

        assertTrue(context.actions().actions().isEmpty());
        assertTrue(context.uiHost().overlays().isEmpty());
        assertEquals(
            List.of(
                "INFO: RenderOptPlugin initialized",
                "INFO: RenderOptPlugin enabled: render status overlay contribution enrolled in disposable scope",
                "INFO: RenderOptPlugin render optimization lifecycle provider disposed"
            ),
            logger.messages()
        );
    }

    @Test
    void refreshActionFallsBackWhenRenderStatusUnavailable() {
        RecordingPluginContext context = new RecordingPluginContext(Optional.empty());
        RenderOptPlugin plugin = new RenderOptPlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("render-status.overlay.refresh");

        assertEquals(
            List.of(new StatusNotification(
                "render-status.overlay.unavailable",
                "WARNING",
                "Render status is unavailable in this host."
            )),
            context.uiHost().notifications()
        );
    }

    private static final class RecordingPluginContext implements PluginContext {
        private final DisposableScope disposableScope = new DisposableScope();
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingUiHost uiHost = new RecordingUiHost();
        private final CubismReadCapabilityService cubismRead;
        private final PluginLogger logger;

        RecordingPluginContext() {
            this(Optional.of(new RenderStatusSnapshot(true, 60.0, "fake-renderer")), new TestPluginLogger());
        }

        RecordingPluginContext(final PluginLogger logger) {
            this(Optional.of(new RenderStatusSnapshot(true, 60.0, "fake-renderer")), logger);
        }

        RecordingPluginContext(final Optional<RenderStatusSnapshot> renderStatus) {
            this(renderStatus, new TestPluginLogger());
        }

        private RecordingPluginContext(
            final Optional<RenderStatusSnapshot> renderStatus,
            final PluginLogger logger
        ) {
            this.cubismRead = new FixedCubismRead(renderStatus);
            this.logger = logger;
        }

        @Override
        public PluginDescriptor descriptor() {
            throw new UnsupportedOperationException("descriptor is not required by this test");
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginPaths paths() {
            throw new UnsupportedOperationException("paths are not required by this test");
        }

        @Override
        public PluginConfigRegistry config() {
            return new DefaultPluginConfigRegistry();
        }

        @Override
        public CubismFacade cubism() {
            throw new UnsupportedOperationException("cubism is not required by this test");
        }

        @Override
        public CubismReadCapabilityService cubismRead() {
            return cubismRead;
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            throw new UnsupportedOperationException("event bus is not required by this test");
        }

        @Override
        public RecordingActionRegistry actions() {
            return actions;
        }

        @Override
        public MenuRegistry menus() {
            throw new UnsupportedOperationException("menus are not required by this test");
        }

        @Override
        public UiScheduler uiScheduler() {
            throw new UnsupportedOperationException("ui scheduler is not required by this test");
        }

        @Override
        public DiagnosticReport diagnostics() {
            throw new UnsupportedOperationException("diagnostics are not required by this test");
        }

        @Override
        public DisposableScope disposableScope() {
            return disposableScope;
        }

        @Override
        public RecordingUiHost uiHost() {
            return uiHost;
        }
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
            return new UnsupportedOperationException("not used by render opt plugin test");
        }
    }

    private static final class RecordingActionRegistry implements ActionRegistry {
        private final List<Action> actions = new ArrayList<>();

        List<Action> actions() {
            return actions;
        }

        @Override
        public Registration register(String id, Action action) {
            actions.add(action);
            return () -> actions.remove(action);
        }

        void execute(String id) {
            actions.stream()
                .filter(action -> action.id().equals(id))
                .findFirst()
                .orElseThrow()
                .handler()
                .accept(new ActionContext() {
                });
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
            return new UnsupportedOperationException("not used by render opt plugin test");
        }
    }

    private static final class TestPluginLogger implements PluginLogger {

        private final List<String> messages = new java.util.ArrayList<>();

        @Override
        public void debug(String message) {
            messages.add("DEBUG: " + message);
        }

        @Override
        public void info(String message) {
            messages.add("INFO: " + message);
        }

        @Override
        public void warn(String message) {
            messages.add("WARN: " + message);
        }

        @Override
        public void error(String message) {
            messages.add("ERROR: " + message);
        }

        @Override
        public void error(String message, Throwable throwable) {
            messages.add("ERROR: " + message + ": " + throwable.getMessage());
        }

        List<String> messages() {
            return List.copyOf(messages);
        }
    }

}
