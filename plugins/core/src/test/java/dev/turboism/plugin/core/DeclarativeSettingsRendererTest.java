package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.runtime.RuntimeLogReader;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsSnapshot;
import dev.turboism.sdk.ui.settings.SettingsTab;
import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JPanel;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeclarativeSettingsRendererTest {

    @Test
    void existingTabIsReusedAndMissingTabIsCreatedInStableOrder() throws Exception {
        final CoreWindows windows = new CoreWindows(
            localization(),
            settings(),
            () -> List.of(
                tab("performance", "Performance", contribution(
                    "later", "performance", "Performance", OptionalInt.of(200), OptionalInt.empty()
                )),
                tab("custom", "Custom", contribution(
                    "custom", "custom", "Custom", OptionalInt.of(150), OptionalInt.empty()
                ))
            ),
            plugins(),
            RuntimeLogReader.unavailable()
        );
        final Map<String, CoreWindows.BuiltinTab> builtins = new LinkedHashMap<>();
        builtins.put("runtime", new CoreWindows.BuiltinTab("Runtime", 100, new JPanel()));
        builtins.put("performance", new CoreWindows.BuiltinTab("Performance", 200, new JPanel()));
        builtins.put("startup", new CoreWindows.BuiltinTab("Startup", 300, new JPanel()));
        builtins.put("maintenance", new CoreWindows.BuiltinTab("Maintenance", 400, new JPanel()));

        final Method render = CoreWindows.class.getDeclaredMethod(
            "renderSettings", JDialog.class, Map.class
        );
        render.setAccessible(true);
        final CoreWindows.RenderedSettings rendered =
            (CoreWindows.RenderedSettings) render.invoke(windows, null, builtins);

        assertEquals(5, rendered.tabs().getTabCount());
        assertEquals(
            List.of("Runtime", "Custom", "Performance", "Startup", "Maintenance"),
            java.util.stream.IntStream.range(0, rendered.tabs().getTabCount())
                .mapToObj(rendered.tabs()::getTitleAt)
                .toList()
        );
        assertEquals(2, ((JPanel) rendered.tabs().getComponentAt(1)).getComponentCount());
    }

    private static SettingsSnapshot.Tab tab(
        final String id,
        final String title,
        final SettingsContribution contribution
    ) {
        return new SettingsSnapshot.Tab(
            id,
            title,
            contribution.tab().index(),
            List.of(new SettingsSnapshot.Entry("plugin.test", contribution))
        );
    }

    private static SettingsContribution contribution(
        final String id,
        final String tabId,
        final String tabTitle,
        final OptionalInt tabIndex,
        final OptionalInt index
    ) {
        return new SettingsContribution(
            id,
            new SettingsTab(tabId, tabTitle, tabIndex),
            index,
            new SettingsControl.Toggle(
                id,
                id,
                SettingsBinding.of(() -> false, ignored -> { })
            )
        );
    }

    private static PluginLocalization localization() {
        return new PluginLocalization() {
            @Override public Locale locale() { return Locale.ENGLISH; }
            @Override public String text(final String key) { return key; }
            @Override public String format(final String key, final Object... arguments) { return key; }
            @Override public boolean contains(final String key) { return true; }
        };
    }

    private static RuntimeSettingsService settings() {
        return new RuntimeSettingsService() {
            @Override public RuntimeSettings read() {
                return new RuntimeSettings(false, "INFO", false, false, false);
            }
            @Override public RuntimeSettings save(final RuntimeSettings value) { return value; }
            @Override public DockCleanupResult cleanEmptyDocks() {
                return new DockCleanupResult("done");
            }
        };
    }

    private static CorePluginManagement plugins() {
        return new CorePluginManagement() {
            @Override public List<PluginInfo> plugins() { return List.of(); }
            @Override public OperationResult install() { return OperationResult.rejected("unavailable"); }
            @Override public OperationResult uninstall(final String id) { return OperationResult.rejected("unavailable"); }
            @Override public OperationResult setEnabled(final String id, final boolean enabled) {
                return OperationResult.rejected("unavailable");
            }
        };
    }
}
