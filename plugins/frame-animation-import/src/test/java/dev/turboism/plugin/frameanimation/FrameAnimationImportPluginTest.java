package dev.turboism.plugin.frameanimation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.PluginServiceDirectory;
import dev.turboism.sdk.plugin.PluginServices;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class FrameAnimationImportPluginTest {

    @Test
    void pluginLifecycleEnrollsActionAndContextMenus() {
        final FrameAnimationImportPlugin plugin = new FrameAnimationImportPlugin();
        final Fixture fixture = new Fixture();

        plugin.init(fixture.context);
        plugin.enable();

        assertEquals(1, fixture.actions.actions.size());
        assertEquals(
                FrameAnimationImportPlugin.ACTION_ID,
                fixture.actions.actions.get(0).id());

        // Context menu items registered in service registry
        assertEquals(2, fixture.contextMenus.contributions.size());

        plugin.disable();
        plugin.shutdown();
    }

    private static final class Fixture {
        final RecordingActions actions = new RecordingActions();
        final RecordingContextMenus contextMenus = new RecordingContextMenus();
        final RecordingUiHost uiHost = new RecordingUiHost();
        final DisposableScope scope = new DisposableScope();
        final PluginContext context = new TestContext(actions, contextMenus, uiHost, scope);
    }

    private static final class TestContext implements PluginContext {
        final RecordingActions actions;
        final RecordingContextMenus contextMenus;
        final RecordingUiHost uiHost;
        final DisposableScope scope;

        TestContext(
                final RecordingActions actions,
                final RecordingContextMenus contextMenus,
                final RecordingUiHost uiHost,
                final DisposableScope scope) {
            this.actions = actions;
            this.contextMenus = contextMenus;
            this.uiHost = uiHost;
            this.scope = scope;
        }

        @Override
        public PluginDescriptor descriptor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginPaths paths() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.turboism.sdk.config.PluginConfigRegistry config() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<dev.turboism.sdk.permission.PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public dev.turboism.sdk.event.EventBus eventBus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.turboism.sdk.menu.MenuRegistry menus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.turboism.sdk.ui.UiScheduler uiScheduler() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginLogger logger() {
            return new PluginLogger() {
                @Override
                public void debug(final String message) {}

                @Override
                public void info(final String message) {}

                @Override
                public void warn(final String message) {}

                @Override
                public void error(final String message) {}

                @Override
                public void error(final String message, final Throwable throwable) {}
            };
        }

        @Override
        public PluginLocalization localization() {
            return new PluginLocalization() {
                private final ResourceBundle bundle =
                        ResourceBundle.getBundle("META-INF.turboism.i18n.messages", Locale.ROOT);

                @Override
                public Locale locale() {
                    return Locale.ROOT;
                }

                @Override
                public String text(final String key) {
                    return bundle.containsKey(key) ? bundle.getString(key) : key;
                }

                @Override
                public String format(final String key, final Object... arguments) {
                    return java.text.MessageFormat.format(text(key), arguments);
                }

                @Override
                public boolean contains(final String key) {
                    return bundle.containsKey(key);
                }
            };
        }

        @Override
        public ActionRegistry actions() {
            return actions;
        }

        @Override
        public CubismFacade cubism() {
            return null;
        }

        @Override
        public PluginServiceDirectory services() {
            return PluginServices.builder()
                    .supply(ContextMenuRegistry.class, () -> contextMenus)
                    .supply(UiHostCapabilityService.class, () -> uiHost)
                    .fallback(PluginServices.of(this))
                    .build();
        }

        @Override
        public DisposableScope disposableScope() {
            return scope;
        }
    }

    private static final class RecordingActions implements ActionRegistry {
        final List<Action> actions = new ArrayList<>();

        @Override
        public Registration register(final String actionId, final Action action) {
            actions.add(action);
            return () -> {};
        }

        public List<Action> all() {
            return actions;
        }
    }

    private static final class RecordingContextMenus implements ContextMenuRegistry {
        final List<ContextMenuContribution> contributions = new ArrayList<>();

        @Override
        public Registration contribute(final ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> {};
        }

        public List<ContextMenuContribution> contributionsFor(final Location location) {
            return contributions.stream().filter(c -> c.location() == location).toList();
        }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        final List<StatusNotification> notifications = new ArrayList<>();

        @Override
        public Registration notifyStatus(final StatusNotification notification) {
            notifications.add(notification);
            return () -> {};
        }

        @Override
        public Registration contributeOverlay(OverlayContribution contribution) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration contributeBoundingBoxOverlayButton(BoundingBoxOverlayButton contribution) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContextSourceSnapshot contextSource() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ViewportSnapshot viewport() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration openDialog(DialogRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean confirmDialog(DialogRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> requestFile(FileChooserRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            throw new UnsupportedOperationException();
        }
    }
}
