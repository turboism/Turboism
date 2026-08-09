package dev.turboism.plugin.logfilter;

import dev.turboism.plugin.logfilter.b1.application.DefaultPluginConfigRegistry;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
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

class LogFilterPluginTest {

    @Test
    void enableRegistersToggleActionAndPaletteToolbarContribution() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        LogFilterPlugin plugin = new LogFilterPlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(List.of("log-filter.toggle-level"), context.actions().actions().stream().map(ActionRegistry.Action::id).toList());
        assertEquals(
            List.of(new PaletteToolbarRegistry.PaletteToolbarContribution(
                "log-filter.toggle-level",
                "log-filter.toggle-level",
                "log-filter.toggle-level.label",
                "icons/log-filter-toggle.svg",
                "LOG",
                "end",
                100
            )),
            context.uiHost().paletteToolbarContributions()
        );
    }

    @Test
    void toggleActionUsesUiHostStatusCapability_whenInvoked() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        LogFilterPlugin plugin = new LogFilterPlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("log-filter.toggle-level");

        assertEquals(
            List.of(new StatusNotification(
                "log-filter.level.changed",
                "INFO",
                "Log filter level changed to WARNING"
            )),
            context.uiHost().notifications()
        );
    }

    @Test
    void disposableScopeClosesActionAndPaletteToolbarContribution() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        LogFilterPlugin plugin = new LogFilterPlugin();

        plugin.init(context);
        plugin.enable();
        context.disposableScope().close();

        assertTrue(context.actions().actions().isEmpty());
        assertTrue(context.uiHost().paletteToolbarContributions().isEmpty());
    }

    @Test
    void enableAllowsPaletteToolbar_whenPermissionGranted() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new PermissionGatedUiHost(true, true));
        LogFilterPlugin plugin = new LogFilterPlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(1, context.uiHost().paletteToolbarContributions().size());
    }

    @Test
    void enableDeniesPaletteToolbar_whenPermissionMissing() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new PermissionGatedUiHost(false, true));
        LogFilterPlugin plugin = new LogFilterPlugin();

        plugin.init(context);
        CubismPermissionException denied = assertThrows(
            CubismPermissionException.class,
            plugin::enable
        );
        assertTrue(denied.getMessage().contains(PermissionIds.TURBOISM_UI_TOOLBAR_PALETTE_CONTRIBUTE));
        assertTrue(context.uiHost().paletteToolbarContributions().isEmpty());
    }

    @Test
    void toggleActionDenies_whenStatusNotifyPermissionMissing() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new PermissionGatedUiHost(true, false));
        LogFilterPlugin plugin = new LogFilterPlugin();

        plugin.init(context);
        plugin.enable();

        CubismPermissionException denied = assertThrows(
            CubismPermissionException.class,
            () -> context.actions().execute("log-filter.toggle-level")
        );
        assertTrue(denied.getMessage().contains(PermissionIds.TURBOISM_UI_STATUS_NOTIFY));
        assertEquals(List.of(), context.uiHost().notifications());
    }

    private static final class RecordingPluginContext implements PluginContext {
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingUiHost uiHost;
        private final DisposableScope disposableScope = new DisposableScope();
        private final PluginLogger logger = new NoopPluginLogger();

        RecordingPluginContext() {
            this(new RecordingUiHost());
        }

        RecordingPluginContext(final RecordingUiHost uiHost) {
            this.uiHost = uiHost;
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
        public RecordingUiHost uiHost() {
            return uiHost;
        }

        @Override
        public PluginConfigRegistry config() {
            return new DefaultPluginConfigRegistry();
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

    private static class RecordingUiHost implements UiHostCapabilityService {
        private final List<PaletteToolbarRegistry.PaletteToolbarContribution> paletteToolbarContributions = new ArrayList<>();
        private final List<StatusNotification> notifications = new ArrayList<>();

        List<PaletteToolbarRegistry.PaletteToolbarContribution> paletteToolbarContributions() {
            return paletteToolbarContributions;
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
            dev.turboism.sdk.ui.BoundingBoxOverlayButton contribution
        ) {
            throw new UnsupportedOperationException("bounding-box overlay is not used by this plugin test");
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
        public Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution) {
            throw new UnsupportedOperationException("embedded panels are not used by this plugin test");
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
            throw new UnsupportedOperationException("context menus are not used through uiHost here");
        }

        @Override
        public Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution) {
            throw new UnsupportedOperationException("main toolbar is not used by this plugin test");
        }

        @Override
        public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            paletteToolbarContributions.add(contribution);
            return () -> paletteToolbarContributions.remove(contribution);
        }
    }

    private static final class PermissionGatedUiHost extends RecordingUiHost {
        private final boolean allowPaletteToolbar;
        private final boolean allowStatusNotify;

        PermissionGatedUiHost(final boolean allowPaletteToolbar, final boolean allowStatusNotify) {
            this.allowPaletteToolbar = allowPaletteToolbar;
            this.allowStatusNotify = allowStatusNotify;
        }

        @Override
        public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            if (!allowPaletteToolbar) {
                throw new CubismPermissionException(
                    "Missing required permission " + PermissionIds.TURBOISM_UI_TOOLBAR_PALETTE_CONTRIBUTE
                        + " for ui.palette-toolbar.contribute"
                );
            }
            return super.contributePaletteToolbar(contribution);
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
