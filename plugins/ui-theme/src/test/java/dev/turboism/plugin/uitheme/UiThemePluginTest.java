package dev.turboism.plugin.uitheme;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.appearance.AppearanceStatus;
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
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiThemePluginTest {

    @Test
    void registersThemeContextMenuContributionsAndActions_whenEnabled() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(
            List.of("ui-theme.toggle", "ui-theme.apply"),
            context.contextMenus().contributions().stream()
                .map(ContextMenuRegistry.ContextMenuContribution::id)
                .toList()
        );
        assertEquals(
            List.of(
                "ui-theme.package.status.check",
                "ui-theme.package.import",
                "ui-theme.appearance.apply-builtin"
            ),
            context.actions().actions().stream()
                .map(ActionRegistry.Action::id)
                .toList()
        );
    }

    @Test
    void removesThemeContributionsAndActions_whenDisposableScopeCloses() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.disposableScope().close();

        assertTrue(context.contextMenus().contributions().isEmpty());
        assertTrue(context.actions().actions().isEmpty());
    }

    @Test
    void statusActionUsesCubismReadAndUiHostCapabilities_whenInvoked() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.package.status.check");

        assertEquals(
            List.of(new StatusNotification(
                "ui-theme.package.status.available",
                "INFO",
                "Theme package available: Aurora (aurora)"
            )),
            context.uiHost().notifications()
        );
    }

    @Test
    void importActionDoesNotNotify_whenHostConfirmationDeclines() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(
            new RecordingUiHost(Optional.of("themes/aurora.zip"), false)
        );
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.package.import");

        assertEquals(1, context.uiHost().dialogs().size());
        assertEquals(List.of(), context.uiHost().notifications());
    }

    @Test
    void importActionEmitsStarted_whenHostConfirmationAccepts() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(
            new RecordingUiHost(Optional.of("themes/aurora.zip"), true)
        );
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.package.import");

        assertEquals(
            List.of(new StatusNotification(
                "ui-theme.package.import.started",
                "INFO",
                "Theme package import started: themes/aurora.zip"
            )),
            context.uiHost().notifications()
        );
    }

    @Test
    void builtinAppearanceActionUsesSemanticPaletteAndReportsUnavailable() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.appearance.apply-builtin");

        AppearanceRequest request = context.appearanceService().lastRequest();
        assertEquals("turboism.nord", request.appearanceId());
        assertEquals(AppearanceBase.DARK, request.base());
        assertEquals("#88C0D0", request.palette().accent());
        assertEquals("#242933", request.palette().viewportBackground());
        assertEquals(
            "ui-theme.appearance.apply.unavailable",
            context.uiHost().notifications().get(0).id()
        );
    }

    @Test
    void statusActionAllows_whenStatusNotifyPermissionGranted() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(
            new PermissionGatedUiHost(true)
        );
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.package.status.check");

        assertEquals(1, context.uiHost().notifications().size());
    }

    @Test
    void statusActionDenies_whenStatusNotifyPermissionMissing() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(
            new PermissionGatedUiHost(false)
        );
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();

        CubismPermissionException denied = assertThrows(
            CubismPermissionException.class,
            () -> context.actions().execute("ui-theme.package.status.check")
        );
        assertTrue(denied.getMessage().contains(PermissionIds.TURBOISM_UI_STATUS_NOTIFY));
        assertEquals(List.of(), context.uiHost().notifications());
    }

    private static final class RecordingPluginContext implements PluginContext {
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingContextMenuRegistry contextMenus = new RecordingContextMenuRegistry();
        private final RecordingUiHost uiHost;
        private final DisposableScope disposableScope = new DisposableScope();
        private final PluginLogger logger = new NoopPluginLogger();
        private final CubismReadCapabilityService cubismRead = new ThemeOnlyReadCapabilityService();
        private final RecordingAppearanceService appearance = new RecordingAppearanceService();

        RecordingPluginContext() {
            this(new RecordingUiHost());
        }

        RecordingPluginContext(final RecordingUiHost uiHost) {
            this.uiHost = uiHost;
        }

        RecordingContextMenuRegistry contextMenus() {
            return contextMenus;
        }

        RecordingAppearanceService appearanceService() {
            return appearance;
        }

        @Override
        public PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginPaths paths() {
            return null;
        }

        @Override
        public CubismFacade cubism() {
            return null;
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
            return null;
        }

        @Override
        public RecordingActionRegistry actions() {
            return actions;
        }

        @Override
        public MenuRegistry menus() {
            return null;
        }

        @Override
        public ContextMenuRegistry contextMenu() {
            return contextMenus;
        }

        @Override
        public RecordingUiHost uiHost() {
            return uiHost;
        }

        @Override
        public AppearanceService appearance() {
            return appearance;
        }

        @Override
        public PluginConfigRegistry config() {
            return null;
        }

        @Override
        public UiScheduler uiScheduler() {
            return null;
        }

        @Override
        public DiagnosticReport diagnostics() {
            return null;
        }

        @Override
        public DisposableScope disposableScope() {
            return disposableScope;
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

    private static final class RecordingContextMenuRegistry implements ContextMenuRegistry {
        private final List<ContextMenuContribution> contributions = new ArrayList<>();

        List<ContextMenuContribution> contributions() {
            return contributions;
        }

        @Override
        public Registration contribute(ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static class RecordingUiHost implements UiHostCapabilityService {
        private final Optional<String> selectedFile;
        private final boolean confirmResult;
        private final List<DialogRequest> dialogs = new ArrayList<>();
        private final List<StatusNotification> notifications = new ArrayList<>();

        RecordingUiHost() {
            this(Optional.empty(), true);
        }

        RecordingUiHost(final Optional<String> selectedFile, final boolean confirmResult) {
            this.selectedFile = selectedFile;
            this.confirmResult = confirmResult;
        }

        List<DialogRequest> dialogs() {
            return dialogs;
        }

        List<StatusNotification> notifications() {
            return notifications;
        }

        @Override
        public Registration contributeOverlay(OverlayContribution contribution) {
            throw new UnsupportedOperationException("overlay contributions are not used by this plugin test");
        }

        @Override
        public ContextSourceSnapshot contextSource() {
            throw new UnsupportedOperationException("context source is not used by this plugin test");
        }

        @Override
        public ViewportSnapshot viewport() {
            throw new UnsupportedOperationException("viewport is not used by this plugin test");
        }

        @Override
        public Registration openDialog(DialogRequest request) {
            dialogs.add(request);
            return () -> dialogs.remove(request);
        }

        @Override
        public boolean confirmDialog(DialogRequest request) {
            dialogs.add(request);
            return confirmResult;
        }

        @Override
        public Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution) {
            throw new UnsupportedOperationException("embedded panels are not used by this plugin test");
        }

        @Override
        public Optional<String> requestFile(FileChooserRequest request) {
            return selectedFile;
        }

        @Override
        public Registration notifyStatus(StatusNotification notification) {
            notifications.add(notification);
            return () -> notifications.remove(notification);
        }

        @Override
        public Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution) {
            throw new UnsupportedOperationException("context menus are not used through uiHost here");
        }

        @Override
        public Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution) {
            throw new UnsupportedOperationException("main toolbar is not used by this plugin test");
        }

        @Override
        public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            throw new UnsupportedOperationException("palette toolbar is not used by this plugin test");
        }
    }

    private static final class PermissionGatedUiHost extends RecordingUiHost {
        private final boolean allowStatusNotify;

        PermissionGatedUiHost(final boolean allowStatusNotify) {
            this.allowStatusNotify = allowStatusNotify;
        }

        @Override
        public Registration notifyStatus(StatusNotification notification) {
            if (!allowStatusNotify) {
                throw new CubismPermissionException(
                    "Missing required permission " + PermissionIds.TURBOISM_UI_STATUS_NOTIFY + " for ui.status.notify"
                );
            }
            return super.notifyStatus(notification);
        }
    }

    private static final class RecordingAppearanceService implements AppearanceService {
        private AppearanceRequest lastRequest;
        private final AppearanceStatus status = new AppearanceStatus(
            AppearanceStatus.Availability.UNAVAILABLE,
            AppearanceStatus.Source.NATIVE,
            Optional.empty(),
            AppearanceBase.NATIVE,
            0,
            Optional.of("appearance.unavailable")
        );

        AppearanceRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public java.util.concurrent.CompletionStage<AppearanceStatus> current() {
            return java.util.concurrent.CompletableFuture.completedFuture(status);
        }

        @Override
        public java.util.concurrent.CompletionStage<AppearanceApplyResult> apply(
            final AppearanceRequest request
        ) {
            lastRequest = request;
            return java.util.concurrent.CompletableFuture.completedFuture(
                new AppearanceApplyResult(
                    AppearanceApplyResult.Outcome.UNAVAILABLE,
                    status,
                    Optional.of("appearance.unavailable")
                )
            );
        }

        @Override
        public java.util.concurrent.CompletionStage<AppearanceRestoreResult> restoreOwnedAppearance() {
            return java.util.concurrent.CompletableFuture.completedFuture(
                new AppearanceRestoreResult(
                    AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE,
                    status,
                    Optional.empty()
                )
            );
        }
    }

    private static final class ThemeOnlyReadCapabilityService implements CubismReadCapabilityService {
        @Override
        public Optional<ProjectSnapshot> activeProject() {
            throw unsupported();
        }

        @Override
        public Optional<DocumentSnapshot> activeDocument() {
            throw unsupported();
        }

        @Override
        public Optional<ModelSnapshot> activeModel() {
            throw unsupported();
        }

        @Override
        public SelectionSnapshot selection() {
            throw unsupported();
        }

        @Override
        public List<ParameterSnapshot> parameters() {
            throw unsupported();
        }

        @Override
        public List<ModelObjectSnapshot> modelObjects() {
            throw unsupported();
        }

        @Override
        public List<ArtMeshSnapshot> meshes() {
            throw unsupported();
        }

        @Override
        public List<DeformerSnapshot> deformers() {
            throw unsupported();
        }

        @Override
        public List<PsdDocumentSnapshot> psdDocuments() {
            throw unsupported();
        }

        @Override
        public List<ClipMaskSnapshot> clipMasks() {
            throw unsupported();
        }

        @Override
        public List<TextureAtlasSnapshot> textureAtlases() {
            throw unsupported();
        }

        @Override
        public Optional<RenderStatusSnapshot> renderStatus() {
            throw unsupported();
        }

        @Override
        public Optional<WorkspaceSnapshot> workspace() {
            throw unsupported();
        }

        @Override
        public Optional<ThemeStatusSnapshot> themeStatus() {
            return Optional.of(new ThemeStatusSnapshot("aurora", "Aurora", true));
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("only themeStatus is used by this plugin test");
        }
    }

    private static final class NoopPluginLogger implements PluginLogger {
        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }
    }
}
