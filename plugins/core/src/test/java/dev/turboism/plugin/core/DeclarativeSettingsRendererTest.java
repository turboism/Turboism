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

import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                tab("custom", "A very long localized settings title", contribution(
                    "custom", "custom", "A very long localized settings title",
                    OptionalInt.of(150), OptionalInt.empty()
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
            List.of(
                "Runtime",
                "A very long localized settings title",
                "Performance",
                "Startup",
                "Maintenance"
            ),
            java.util.stream.IntStream.range(0, rendered.tabs().getTabCount())
                .mapToObj(rendered.tabs()::getTitleAt)
                .toList()
        );
        assertEquals(JTabbedPane.SCROLL_TAB_LAYOUT, rendered.tabs().getTabLayoutPolicy());
        for (int index = 0; index < rendered.tabs().getTabCount(); index++) {
            assertNull(rendered.tabs().getTabComponentAt(index));
        }
        final JPanel custom = (JPanel) rendered.tabs().getComponentAt(1);
        assertEquals(3, custom.getComponentCount());
        final GridBagLayout layout = (GridBagLayout) custom.getLayout();
        final GridBagConstraints filler = layout.getConstraints(
            custom.getComponent(custom.getComponentCount() - 1)
        );
        assertTrue(filler.weighty > 0);
        assertEquals(GridBagConstraints.NORTHWEST, filler.anchor);
    }

    @Test
    void customPathAndGraalChoiceSaveTogetherWithoutPrematureInstallPrompt() throws Exception {
        final String[] path = {""};
        final CubismJvmSettingsService.CubismJvm[] jvm = {
            CubismJvmSettingsService.CubismJvm.BUNDLED
        };
        final CubismJvmSettingsService service = new CubismJvmSettingsService() {
            @Override public CubismJvmSettingsService.CubismJvm read() { return jvm[0]; }
            @Override public CubismJvmSettingsService.CubismJvm save(
                final CubismJvmSettingsService.CubismJvm value
            ) { return jvm[0] = value; }
            @Override public String graalVmPath() { return path[0]; }
            @Override public String saveGraalVmPath(final String value) { return path[0] = value; }
            @Override public boolean graalVmPathCompatible(final String value) {
                return value == null || value.isBlank() || value.endsWith("\\bin");
            }
            @Override public boolean graalVmAvailable() { return path[0].endsWith("\\bin"); }
        };
        final SettingsContribution pathContribution =
            CubismJvmSettingsContribution.createPath(localization(), service);
        final SettingsContribution jvmContribution =
            CubismJvmSettingsContribution.create(localization(), service);
        final CoreWindows windows = new CoreWindows(
            localization(),
            settings(),
            () -> List.of(new SettingsSnapshot.Tab(
                "performance",
                "Performance",
                OptionalInt.of(200),
                List.of(
                    new SettingsSnapshot.Entry("turboism.core", pathContribution),
                    new SettingsSnapshot.Entry("turboism.core", jvmContribution)
                )
            )),
            plugins(),
            RuntimeLogReader.unavailable()
        );
        final Map<String, CoreWindows.BuiltinTab> builtins = new LinkedHashMap<>();
        builtins.put(
            "performance",
            new CoreWindows.BuiltinTab("Performance", 200, new JPanel(new GridBagLayout()))
        );
        final Method render = CoreWindows.class.getDeclaredMethod(
            "renderSettings", JDialog.class, Map.class
        );
        render.setAccessible(true);
        final CoreWindows.RenderedSettings rendered =
            (CoreWindows.RenderedSettings) render.invoke(windows, null, builtins);
        final JPanel performance = (JPanel) rendered.tabs().getComponentAt(0);
        final JTextField field = java.util.Arrays.stream(performance.getComponents())
            .filter(JTextField.class::isInstance)
            .map(JTextField.class::cast)
            .findFirst()
            .orElseThrow();
        final JComboBox<?> combo = java.util.Arrays.stream(performance.getComponents())
            .filter(JComboBox.class::isInstance)
            .map(JComboBox.class::cast)
            .findFirst()
            .orElseThrow();

        field.setText("D:\\graalvm-jdk-25.0.4+7.1\\bin");
        for (int index = 0; index < combo.getItemCount(); index++) {
            final SettingsControl.Option option = (SettingsControl.Option) combo.getItemAt(index);
            if (option.value().equals("graalvm")) {
                combo.setSelectedIndex(index);
                break;
            }
        }

        assertTrue(rendered.save().getAsBoolean());
        assertEquals("D:\\graalvm-jdk-25.0.4+7.1\\bin", path[0]);
        assertEquals(CubismJvmSettingsService.CubismJvm.GRAALVM, jvm[0]);
    }

    @Test
    void customGraalVmPathUsesAVisibleHomeDirectoryPlaceholder() throws Exception {
        final CoreWindows windows = new CoreWindows(
            localization(), settings(), List::of, plugins(), RuntimeLogReader.unavailable()
        );
        final JPanel panel = new JPanel(new GridBagLayout());
        final SettingsControl.Text control = new SettingsControl.Text(
            CubismJvmSettingsContribution.PATH_CONTRIBUTION_ID,
            "Custom GraalVM path",
            36,
            SettingsBinding.of(() -> "", ignored -> { })
        );
        final Method render = CoreWindows.class.getDeclaredMethod(
            "renderControl", JDialog.class, JPanel.class, int.class, SettingsControl.class
        );
        render.setAccessible(true);

        render.invoke(windows, null, panel, 0, control);

        final JTextField field = java.util.Arrays.stream(panel.getComponents())
            .filter(JTextField.class::isInstance)
            .map(JTextField.class::cast)
            .findFirst()
            .orElseThrow();
        assertEquals(
            "settings.cubism-jvm.graalvm-path-placeholder",
            field.getClientProperty("JTextField.placeholderText")
        );
        assertEquals(
            "settings.cubism-jvm.graalvm-path-placeholder",
            field.getToolTipText()
        );
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
