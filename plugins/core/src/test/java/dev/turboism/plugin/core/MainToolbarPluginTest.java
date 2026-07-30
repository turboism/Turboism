package dev.turboism.plugin.core;

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
import dev.turboism.sdk.i18n.PluginLocalization;
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
import dev.turboism.sdk.ui.EmbeddedPanelId;
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

import java.nio.file.Path;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainToolbarPluginTest {

    @Test
    void enableRegistersHomeActionAndMainToolbarContribution() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        MainToolbarPlugin plugin = plugin();

        plugin.init(context);
        plugin.enable();

        assertTrue(context.actions().actions().stream()
            .map(ActionRegistry.Action::id)
            .toList()
            .containsAll(List.of(
                "turboism.core.open", "settings.save", "settings.clean-empty-docks"
            )));
        assertEquals(
            List.of(new MainToolbarRegistry.MainToolbarButtonContribution(
                "turboism.core.home-entry",
                "turboism.core.open",
                "main-toolbar.home.aria-label",
                "main-toolbar.home.tooltip",
                new MainToolbarRegistry.IconVariants(
                    "icons/main-toolbar-home.png",
                    Optional.of("icons/main-toolbar-home-hover.png"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
                ),
                MainToolbarRegistry.Placement.after(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY),
                10
            )),
            context.mainToolbar().buttonContributions()
        );
        assertEquals(1, context.uiHost().panelContributions().size());
        final EmbeddedPanelContribution panel = context.uiHost().panelContributions().get(0);
        assertEquals("turboism.panel.main", panel.id());
        assertTrue(panel.content().toString().contains("Safe Mode"));
        assertTrue(panel.content().toString().contains("Clean empty docks"));
        assertEquals(
            List.of(
                "Turboism/Settings:turboism.core.open:10",
                "Turboism/Plugin Management:turboism.core.open:11"
            ),
            context.menus().contributions().stream()
                .map(value -> value.menuPath() + ":" + value.actionId() + ":" + value.order())
                .toList()
        );
    }

    @Test
    void homeActionRequestsTypedTurboismPanelActivation_whenInvoked() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        MainToolbarPlugin plugin = plugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("turboism.core.open");

        assertEquals(
            List.of(EmbeddedPanelId.of("turboism.panel.main")),
            context.uiHost().activatedPanels()
        );
        assertTrue(context.uiHost().notifications().isEmpty());
    }

    @Test
    void disposableScopeClosesActionAndToolbarContribution() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        MainToolbarPlugin plugin = plugin();

        plugin.init(context);
        plugin.enable();
        context.disposableScope().close();

        assertTrue(context.actions().actions().isEmpty());
        assertTrue(context.mainToolbar().buttonContributions().isEmpty());
        assertTrue(context.uiHost().panelContributions().isEmpty());
        assertTrue(context.menus().contributions().isEmpty());
    }

    @Test
    void enableAllowsMainToolbar_whenPermissionGranted() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new PermissionGatedUiHost(true, true));
        MainToolbarPlugin plugin = plugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(1, context.mainToolbar().buttonContributions().size());
    }

    @Test
    void enableDeniesMainToolbar_whenPermissionMissing() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new PermissionGatedUiHost(false, true));
        MainToolbarPlugin plugin = plugin();

        plugin.init(context);
        CubismPermissionException denied = assertThrows(
            CubismPermissionException.class,
            plugin::enable
        );
        assertTrue(denied.getMessage().contains(PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE));
        assertTrue(context.mainToolbar().buttonContributions().isEmpty());
    }

    @Test
    void homeActionDoesNotRequireStatusNotificationPermission() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new PermissionGatedUiHost(true, false));
        MainToolbarPlugin plugin = plugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("turboism.core.open");

        assertEquals(
            List.of(EmbeddedPanelId.of("turboism.panel.main")),
            context.uiHost().activatedPanels()
        );
        assertEquals(List.of(), context.uiHost().notifications());
    }

    private static MainToolbarPlugin plugin() {
        return CorePluginServices.instantiate(
            new CorePluginServices(settings(), plugins()),
            MainToolbarPlugin::new
        );
    }

    private static dev.turboism.sdk.runtime.RuntimeSettingsService settings() {
        return new dev.turboism.sdk.runtime.RuntimeSettingsService() {
            private dev.turboism.sdk.runtime.RuntimeSettings value =
                new dev.turboism.sdk.runtime.RuntimeSettings(false, "INFO", false, false, false);
            @Override public dev.turboism.sdk.runtime.RuntimeSettings read() { return value; }
            @Override public dev.turboism.sdk.runtime.RuntimeSettings save(
                dev.turboism.sdk.runtime.RuntimeSettings settings
            ) { value = settings; return value; }
            @Override public DockCleanupResult cleanEmptyDocks() {
                return new DockCleanupResult("Empty dock cleanup completed.");
            }
        };
    }

    private static CorePluginManagement plugins() {
        return new CorePluginManagement() {
            @Override public List<PluginInfo> plugins() { return List.of(); }
            @Override public OperationResult install() { return OperationResult.rejected("Unavailable"); }
            @Override public OperationResult uninstall(String id) { return OperationResult.rejected("Unavailable"); }
            @Override public OperationResult setEnabled(String id, boolean enabled) {
                return OperationResult.rejected("Unavailable");
            }
        };
    }

    private static final class RecordingPluginContext implements PluginContext {
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingMenuRegistry menus = new RecordingMenuRegistry();
        private final RecordingUiHost uiHost;
        private final RecordingMainToolbarRegistry mainToolbar;
        private final DisposableScope disposableScope = new DisposableScope();
        private final PluginLogger logger = new NoopPluginLogger();
        private final CubismReadCapabilityService cubismRead = new ProjectReadCapabilityService();

        RecordingPluginContext() {
            this(new RecordingUiHost());
        }

        RecordingPluginContext(final RecordingUiHost uiHost) {
            this.uiHost = uiHost;
            this.mainToolbar = new RecordingMainToolbarRegistry(uiHost);
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
        public RecordingMenuRegistry menus() {
            return menus;
        }

        @Override
        public RecordingMainToolbarRegistry mainToolbar() {
            return mainToolbar;
        }

        @Override
        public RecordingUiHost uiHost() {
            return uiHost;
        }

        @Override
        public PluginLocalization localization() {
            return new PluginLocalization() {
                @Override
                public Locale locale() {
                    return Locale.ENGLISH;
                }

                @Override
                public String text(final String key) {
                    return switch (key) {
                        case "main-toolbar.settings-menu.label" -> "Settings";
                        case "main-toolbar.plugins-menu.label" -> "Plugin Management";
                        default -> key;
                    };
                }

                @Override
                public String format(final String key, final Object... arguments) {
                    return text(key);
                }

                @Override
                public boolean contains(final String key) {
                    return key.equals("main-toolbar.settings-menu.label")
                        || key.equals("main-toolbar.plugins-menu.label");
                }
            };
        }

        @Override
        public PluginConfigRegistry config() {
            return null;
        }


        @Override
        public dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings() {
            return new dev.turboism.sdk.runtime.RuntimeSettingsService() {
                private dev.turboism.sdk.runtime.RuntimeSettings settings =
                    new dev.turboism.sdk.runtime.RuntimeSettings(false, "INFO", false, false, false);
                @Override public dev.turboism.sdk.runtime.RuntimeSettings read() { return settings; }
                @Override public dev.turboism.sdk.runtime.RuntimeSettings save(
                    final dev.turboism.sdk.runtime.RuntimeSettings value
                ) { settings = value; return settings; }
                @Override public DockCleanupResult cleanEmptyDocks() {
                    return new DockCleanupResult("Empty dock cleanup completed.");
                }
            };
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

    private static final class RecordingMenuRegistry implements MenuRegistry {
        private final List<MenuContribution> contributions = new ArrayList<>();

        List<MenuContribution> contributions() {
            return contributions;
        }

        @Override
        public Registration contribute(final MenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class RecordingMainToolbarRegistry implements MainToolbarRegistry {
        private final RecordingUiHost uiHost;
        private final List<MainToolbarButtonContribution> buttonContributions = new ArrayList<>();

        private RecordingMainToolbarRegistry(final RecordingUiHost uiHost) {
            this.uiHost = uiHost;
        }

        List<MainToolbarButtonContribution> buttonContributions() {
            return buttonContributions;
        }

        @Override
        public Registration contribute(final MainToolbarContribution contribution) {
            return uiHost.contributeMainToolbar(contribution);
        }

        @Override
        public Registration contributeButton(final MainToolbarButtonContribution contribution) {
            uiHost.requireMainToolbarPermission();
            buttonContributions.add(contribution);
            return () -> buttonContributions.remove(contribution);
        }
    }

    private static class RecordingUiHost implements UiHostCapabilityService {
        private final List<EmbeddedPanelContribution> panelContributions = new ArrayList<>();
        private final List<EmbeddedPanelId> activatedPanels = new ArrayList<>();
        private final List<StatusNotification> notifications = new ArrayList<>();

        void requireMainToolbarPermission() {
        }

        List<EmbeddedPanelContribution> panelContributions() {
            return panelContributions;
        }

        List<EmbeddedPanelId> activatedPanels() {
            return activatedPanels;
        }

        List<StatusNotification> notifications() {
            return notifications;
        }

        @Override
        public Registration contributeOverlay(OverlayContribution contribution) {
            throw new UnsupportedOperationException("overlay contributions are not used by this plugin test");
        }

        @Override
        public Registration contributeBoundingBoxOverlayButton(
            final dev.turboism.sdk.ui.BoundingBoxOverlayButton contribution
        ) {
            throw new UnsupportedOperationException("bounding-box buttons are not used by this plugin test");
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
            throw new UnsupportedOperationException("dialogs are not used by this plugin test");
        }

        @Override
        public boolean confirmDialog(DialogRequest request) {
            throw new UnsupportedOperationException("dialogs are not used by this plugin test");
        }

        @Override
        public Registration contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
            panelContributions.add(contribution);
            return () -> panelContributions.remove(contribution);
        }

        @Override
        public void activateEmbeddedPanel(final EmbeddedPanelId panelId) {
            activatedPanels.add(panelId);
        }

        @Override
        public Optional<String> requestFile(FileChooserRequest request) {
            throw new UnsupportedOperationException("file requests are not used by this plugin test");
        }

        @Override
        public Registration notifyStatus(StatusNotification notification) {
            notifications.add(notification);
            return () -> notifications.remove(notification);
        }

        @Override
        public Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution) {
            throw new UnsupportedOperationException("context menus are not used by this plugin test");
        }

        @Override
        public Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution) {
            requireMainToolbarPermission();
            return () -> { };
        }

        @Override
        public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            throw new UnsupportedOperationException("palette toolbar is not used by this plugin test");
        }
    }

    private static final class PermissionGatedUiHost extends RecordingUiHost {
        private final boolean allowMainToolbar;
        private final boolean allowStatusNotify;

        PermissionGatedUiHost(final boolean allowMainToolbar, final boolean allowStatusNotify) {
            this.allowMainToolbar = allowMainToolbar;
            this.allowStatusNotify = allowStatusNotify;
        }

        @Override
        void requireMainToolbarPermission() {
            if (!allowMainToolbar) {
                throw new CubismPermissionException(
                    "Missing required permission " + PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE
                        + " for ui.main-toolbar.contribute"
                );
            }
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

    private static final class ProjectReadCapabilityService implements CubismReadCapabilityService {
        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return Optional.of(new ProjectSnapshot(
                "project-1",
                "Demo Project",
                Optional.of(Path.of("project/demo")),
                List.of(new DocumentSnapshot("doc-1", "Model", "model.cmo3", Optional.empty(), Optional.empty()))
            ));
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
            return Optional.of(new WorkspaceSnapshot(
                "workspace-1",
                "Modeling",
                "layouts/workspace-1",
                List.of("project-1")
            ));
        }

        @Override
        public Optional<ThemeStatusSnapshot> themeStatus() {
            throw unsupported();
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by this plugin test");
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
