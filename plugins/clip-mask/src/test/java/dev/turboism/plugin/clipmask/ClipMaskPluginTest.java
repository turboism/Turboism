package dev.turboism.plugin.clipmask;

import dev.turboism.sdk.action.ActionRegistry;
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

class ClipMaskPluginTest {

    @Test
    void enableRegistersInspectActionPanelAndDialog() {
        TestPluginLogger logger = new TestPluginLogger();
        RecordingPluginContext context = new RecordingPluginContext(logger);
        ClipMaskPlugin plugin = new ClipMaskPlugin();

        plugin.init(context);
        plugin.enable();

        context.actions().execute("clip-mask.inspector.inspect");

        assertEquals(
            List.of("clip-mask.inspector.inspect"),
            context.actions().actions().stream().map(ActionRegistry.Action::id).toList()
        );
        assertEquals(
            List.of(new EmbeddedPanelContribution("clip-mask.inspector.panel", "Clip Mask Inspector", "side", 40)),
            context.uiHost().panels()
        );
        assertEquals(
            List.of(new DialogRequest(
                "clip-mask.inspector.dialog",
                "Clip Mask Inspector",
                "Clip Mask Inspector is ready. Use Inspect to refresh status."
            )),
            context.uiHost().dialogs()
        );
        assertEquals(
            List.of(new StatusNotification(
                "clip-mask.inspector.refreshed",
                "INFO",
                "Clip masks: 1 target meshes, 1 inverted, 1 mask source refs"
            )),
            context.uiHost().notifications()
        );
        assertEquals(
            List.of(
                "INFO: ClipMaskPlugin initialized",
                "INFO: ClipMaskPlugin enabled: clip-mask inspector panel and dialog enrolled in disposable scope"
            ),
            logger.messages()
        );
    }

    @Test
    void enableDoesNotReadClipMasks() {
        RecordingPluginContext context = new RecordingPluginContext(
            new FixedCubismRead(List.of(), true),
            new TestPluginLogger()
        );
        ClipMaskPlugin plugin = new ClipMaskPlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(1, context.actions().actions().size());
        assertEquals(1, context.uiHost().panels().size());
        assertEquals(1, context.uiHost().dialogs().size());
    }

    @Test
    void disposableScopeClosesActionPanelAndDialog() throws Exception {
        TestPluginLogger logger = new TestPluginLogger();
        RecordingPluginContext context = new RecordingPluginContext(logger);
        ClipMaskPlugin plugin = new ClipMaskPlugin();

        plugin.init(context);
        plugin.enable();
        context.disposableScope().close();

        assertTrue(context.actions().actions().isEmpty());
        assertTrue(context.uiHost().panels().isEmpty());
        assertTrue(context.uiHost().dialogs().isEmpty());
    }

    @Test
    void disposableScopeCloseIsIdempotent() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        ClipMaskPlugin plugin = new ClipMaskPlugin();

        plugin.init(context);
        plugin.enable();
        context.disposableScope().close();
        context.disposableScope().close();

        assertTrue(context.actions().actions().isEmpty());
        assertTrue(context.uiHost().panels().isEmpty());
        assertTrue(context.uiHost().dialogs().isEmpty());
    }

    @Test
    void enableFailureRollsBackPartialRegistrationsWhenDialogFails() {
        RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.uiHost().failOnDialog = true;
        ClipMaskPlugin plugin = new ClipMaskPlugin();

        plugin.init(context);
        RuntimeException failure = org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class,
            plugin::enable
        );
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("dialog denied"));
        assertTrue(context.actions().actions().isEmpty());
        assertTrue(context.uiHost().panels().isEmpty());
        assertTrue(context.uiHost().dialogs().isEmpty());
    }

    @Test
    void inspectActionFallsBackWhenNoMasks() {
        RecordingPluginContext context = new RecordingPluginContext(List.of());
        ClipMaskPlugin plugin = new ClipMaskPlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("clip-mask.inspector.inspect");

        assertEquals(
            List.of(new StatusNotification(
                "clip-mask.inspector.unavailable",
                "WARNING",
                "No clip masks are available in this host."
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

        RecordingPluginContext(final PluginLogger logger) {
            this(List.of(new ClipMaskSnapshot("mesh-1", List.of("src"), true)), logger);
        }

        RecordingPluginContext(final List<ClipMaskSnapshot> masks) {
            this(masks, new TestPluginLogger());
        }

        private RecordingPluginContext(final List<ClipMaskSnapshot> masks, final PluginLogger logger) {
            this(new FixedCubismRead(masks), logger);
        }

        private RecordingPluginContext(
            final CubismReadCapabilityService cubismRead,
            final PluginLogger logger
        ) {
            this.cubismRead = cubismRead;
            this.logger = logger;
        }

        @Override public PluginDescriptor descriptor() { throw new UnsupportedOperationException("descriptor"); }
        @Override public PluginLogger logger() { return logger; }
        @Override public PluginPaths paths() { throw new UnsupportedOperationException("paths"); }
        @Override public CubismFacade cubism() { throw new UnsupportedOperationException("cubism"); }
        @Override public CubismReadCapabilityService cubismRead() { return cubismRead; }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { throw new UnsupportedOperationException("event bus"); }
        @Override public RecordingActionRegistry actions() { return actions; }
        @Override public MenuRegistry menus() { throw new UnsupportedOperationException("menus"); }
        @Override public UiScheduler uiScheduler() { throw new UnsupportedOperationException("ui scheduler"); }
        @Override public DiagnosticReport diagnostics() { throw new UnsupportedOperationException("diagnostics"); }
        @Override public DisposableScope disposableScope() { return disposableScope; }
        @Override public RecordingUiHost uiHost() { return uiHost; }
    }

    private record FixedCubismRead(
        List<ClipMaskSnapshot> clipMasks,
        boolean failOnClipMaskRead
    ) implements CubismReadCapabilityService {
        private FixedCubismRead(final List<ClipMaskSnapshot> clipMasks) {
            this(clipMasks, false);
        }
        @Override public Optional<ProjectSnapshot> activeProject() { throw unsupported(); }
        @Override public Optional<DocumentSnapshot> activeDocument() { throw unsupported(); }
        @Override public Optional<ModelSnapshot> activeModel() { throw unsupported(); }
        @Override public SelectionSnapshot selection() { throw unsupported(); }
        @Override public List<ParameterSnapshot> parameters() { throw unsupported(); }
        @Override public List<ModelObjectSnapshot> modelObjects() { throw unsupported(); }
        @Override public List<ArtMeshSnapshot> meshes() { throw unsupported(); }
        @Override public List<DeformerSnapshot> deformers() { throw unsupported(); }
        @Override public List<PsdDocumentSnapshot> psdDocuments() { throw unsupported(); }
        @Override public List<ClipMaskSnapshot> clipMasks() {
            if (failOnClipMaskRead) {
                throw new AssertionError("enable must not read clip masks");
            }
            return clipMasks;
        }
        @Override public List<TextureAtlasSnapshot> textureAtlases() { throw unsupported(); }
        @Override public Optional<RenderStatusSnapshot> renderStatus() { throw unsupported(); }
        @Override public Optional<WorkspaceSnapshot> workspace() { throw unsupported(); }
        @Override public Optional<ThemeStatusSnapshot> themeStatus() { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by clip-mask plugin test");
        }
    }

    private static final class RecordingActionRegistry implements ActionRegistry {
        private final List<Action> actions = new ArrayList<>();

        List<Action> actions() { return actions; }

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
        private final List<EmbeddedPanelContribution> panels = new ArrayList<>();
        private final List<DialogRequest> dialogs = new ArrayList<>();
        private final List<StatusNotification> notifications = new ArrayList<>();
        private boolean failOnDialog;

        List<EmbeddedPanelContribution> panels() { return panels; }
        List<DialogRequest> dialogs() { return dialogs; }
        List<StatusNotification> notifications() { return notifications; }

        @Override public Registration contributeOverlay(OverlayContribution contribution) { throw unsupported(); }
        @Override public ContextSourceSnapshot contextSource() { throw unsupported(); }
        @Override public ViewportSnapshot viewport() { throw unsupported(); }

        @Override
        public Registration openDialog(DialogRequest request) {
            if (failOnDialog) {
                throw new RuntimeException("dialog denied");
            }
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
            return new UnsupportedOperationException("not used by clip-mask plugin test");
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

        List<String> messages() { return List.copyOf(messages); }
    }
}
