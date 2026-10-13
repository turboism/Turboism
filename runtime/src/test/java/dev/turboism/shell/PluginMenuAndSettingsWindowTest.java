package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsTab;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.shell.service.MainToolbarHomeEntryService;
import dev.turboism.ui.menu.TopMenuText;
import dev.turboism.ui.settings.SettingsContributionStore;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Tests for Requirement B:
 * - Top-level menu branded "Turboism" across all runtime locales;
 * - Top-level menu item structure and ordering;
 * - Dedicated Plugin Settings window routing and control isolation.
 */
class PluginMenuAndSettingsWindowTest {

    @Test
    void topLevelMenuIsBrandedTurboismInAllLocales() {
        assertEquals("Turboism", TopMenuText.sharedRootLabel(Locale.ENGLISH));
        assertEquals("Turboism", TopMenuText.sharedRootLabel(Locale.SIMPLIFIED_CHINESE));
        assertEquals("Turboism", TopMenuText.sharedRootLabel(Locale.TRADITIONAL_CHINESE));
        assertEquals("Turboism", TopMenuText.sharedRootLabel(Locale.JAPANESE));
        assertEquals("Turboism", TopMenuText.sharedRootLabel(Locale.KOREAN));
    }

    @Test
    void menuItemsStructureAndOrderingMatchRedesign() {
        final List<String> registered = new ArrayList<>();
        final MenuRegistry menus = contribution -> {
            registered.add(contribution.menuPath() + ":" + contribution.actionId() + ":" + contribution.order());
            return () -> {};
        };

        final PluginLocalization i18n = stubLocalization();
        final UiHostCapabilityService uiHost = (UiHostCapabilityService) Proxy.newProxyInstance(
                UiHostCapabilityService.class.getClassLoader(),
                new Class<?>[] {UiHostCapabilityService.class},
                (proxy, method, args) -> null);
        final MainToolbarRegistry mainToolbar = (MainToolbarRegistry) Proxy.newProxyInstance(
                MainToolbarRegistry.class.getClassLoader(),
                new Class<?>[] {MainToolbarRegistry.class},
                (proxy, method, args) -> null);

        final MainToolbarHomeEntryService service = new MainToolbarHomeEntryService(uiHost, mainToolbar, menus, i18n);

        service.registerPluginMarketMenu();
        service.registerPluginManagementMenu();
        service.registerPluginSettingsMenu();
        service.registerSettingsMenu();
        service.registerKeybindingsMenu();
        service.registerLogsMenu();
        service.registerAboutMenu();

        assertEquals(
                List.of(
                        "Turboism/Plugin Market…:turboism.core.market.open:10",
                        "Turboism/Manage Plugins…:turboism.core.plugins.open:11",
                        "Turboism/Plugin Settings…:turboism.core.plugin-settings.open:12",
                        "Turboism/Settings:turboism.core.settings.open:21",
                        "Turboism/Keybindings:turboism.core.keybindings.open:22",
                        "Turboism/Logs:turboism.core.logs.open:23",
                        "Turboism/About:turboism.core.about.open:90"),
                registered);
    }

    @Test
    void dedicatedPluginSettingsWindowRendersOnlyExternalPluginSettings() {
        final SettingsContributionStore store = new SettingsContributionStore();

        // Framework contribution
        store.register(
                CorePluginManagement.CORE_PLUGIN_ID,
                new SettingsContribution(
                        "core.jvm",
                        new SettingsTab("startup", "Startup"),
                        new SettingsControl.Toggle(
                                "core.toggle", "Enable Core Feature", SettingsBinding.of(() -> true, v -> {}))));

        // External plugin contribution
        final AtomicBoolean pluginSettingSaved = new AtomicBoolean(false);
        store.register(
                "dev.turboism.plugin.mcp",
                new SettingsContribution(
                        "mcp.port",
                        new SettingsTab("mcp", "MCP Configuration"),
                        new SettingsControl.Toggle(
                                "mcp.enabled",
                                "Enable MCP Server",
                                SettingsBinding.of(() -> false, pluginSettingSaved::set))));

        // Contributing plugin ids must exclude framework core
        assertEquals(List.of("dev.turboism.plugin.mcp"), store.contributingPluginIds());

        // Snapshot for external plugin must contain only that plugin's tab
        final var mcpTabs = store.snapshotForPlugin("dev.turboism.plugin.mcp");
        assertEquals(1, mcpTabs.size());
        assertEquals("mcp", mcpTabs.get(0).id());

        // Snapshot for framework must contain only framework tab
        final var frameworkTabs = store.snapshotFramework();
        assertEquals(1, frameworkTabs.size());
        assertEquals("startup", frameworkTabs.get(0).id());
    }

    private static PluginLocalization stubLocalization() {
        return new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public String text(String key) {
                return switch (key) {
                    case "main-toolbar.menu-root.label" -> "Turboism";
                    case "main-toolbar.market-menu.label" -> "Plugin Market…";
                    case "main-toolbar.plugins-menu.label" -> "Manage Plugins…";
                    case "main-toolbar.plugin-settings-menu.label" -> "Plugin Settings…";
                    case "main-toolbar.settings-menu.label" -> "Settings";
                    case "main-toolbar.logs-menu.label" -> "Logs";
                    case "main-toolbar.keybindings-menu.label" -> "Keybindings";
                    case "main-toolbar.about-menu.label" -> "About";
                    default -> key;
                };
            }

            @Override
            public String format(String key, Object... arguments) {
                return text(key);
            }

            @Override
            public boolean contains(String key) {
                return true;
            }
        };
    }
}
