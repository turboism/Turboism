package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.NativeOptimizationConfig;
import dev.turboism.config.OptimizationSettingsFileService;
import dev.turboism.config.PerformanceSettingsConfig;
import dev.turboism.config.RuntimeConfigRepository;
import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.config.optimization.OptimizationPreset;
import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.internal.core.CubismJvmSettingsService;
import dev.turboism.internal.core.OptimizationSettingsService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.runtime.RuntimeLogReader;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsSnapshot;
import dev.turboism.sdk.ui.settings.SettingsTab;
import dev.turboism.test.concurrent.Await;
import dev.turboism.test.plugin.FakePluginContext;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.FocusEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclarativeSettingsRendererTest {

    @Test
    void fallbackUisPreserveTheActiveLookAndFeel() throws Exception {
        final javax.swing.LookAndFeel previous = javax.swing.UIManager.getLookAndFeel();
        try {
            javax.swing.UIManager.setLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    final Object expected = javax.swing.UIManager.get("TabbedPaneUI");
                    final Method ensure = CoreWindows.class.getDeclaredMethod("ensureSwingUis");
                    ensure.setAccessible(true);
                    ensure.invoke(null);
                    assertEquals(expected, javax.swing.UIManager.get("TabbedPaneUI"));
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                } finally {
                    javax.swing.UIManager.getDefaults().remove("TabbedPaneUI");
                }
            });
        } finally {
            javax.swing.UIManager.setLookAndFeel(previous);
        }
    }

    @Test
    void existingTabIsReusedAndMissingTabIsCreatedInStableOrder() throws Exception {
        final CoreWindows windows = new CoreWindows(
                localization(),
                settings(),
                () -> List.of(
                        tab(
                                "performance",
                                "Performance",
                                contribution(
                                        "later",
                                        "performance",
                                        "Performance",
                                        OptionalInt.of(200),
                                        OptionalInt.empty())),
                        tab(
                                "custom",
                                "A very long localized settings title",
                                contribution(
                                        "custom",
                                        "custom",
                                        "A very long localized settings title",
                                        OptionalInt.of(150),
                                        OptionalInt.empty()))),
                plugins(),
                RuntimeLogReader.unavailable(),
                dev.turboism.internal.core.KeybindingService.unavailable());
        final Map<String, CoreWindows.BuiltinTab> builtins = new LinkedHashMap<>();
        builtins.put("runtime", new CoreWindows.BuiltinTab("Runtime", 100, new JPanel()));
        builtins.put("performance", new CoreWindows.BuiltinTab("Performance", 200, new JPanel()));
        builtins.put("startup", new CoreWindows.BuiltinTab("Startup", 300, new JPanel()));
        builtins.put("maintenance", new CoreWindows.BuiltinTab("Maintenance", 400, new JPanel()));

        final Method render = CoreWindows.class.getDeclaredMethod("renderSettings", JDialog.class, Map.class);
        render.setAccessible(true);
        final CoreWindows.RenderedSettings rendered =
                (CoreWindows.RenderedSettings) render.invoke(windows, null, builtins);

        assertEquals(5, rendered.tabs().getTabCount());
        assertEquals(
                List.of("Runtime", "A very long localized settings title", "Performance", "Startup", "Maintenance"),
                java.util.stream.IntStream.range(0, rendered.tabs().getTabCount())
                        .mapToObj(rendered.tabs()::getTitleAt)
                        .toList());
        assertEquals(JTabbedPane.SCROLL_TAB_LAYOUT, rendered.tabs().getTabLayoutPolicy());
        for (int index = 0; index < rendered.tabs().getTabCount(); index++) {
            assertNull(rendered.tabs().getTabComponentAt(index));
        }
        final JPanel custom = formInTab(rendered.tabs(), 1);
        assertEquals(3, custom.getComponentCount());
        final GridBagLayout layout = (GridBagLayout) custom.getLayout();
        final GridBagConstraints filler = layout.getConstraints(custom.getComponent(custom.getComponentCount() - 1));
        assertTrue(filler.weighty > 0);
        assertEquals(GridBagConstraints.NORTHWEST, filler.anchor);
    }

    @Test
    void customPathAndGraalChoiceSaveTogetherWithoutPrematureInstallPrompt() throws Exception {
        final String[] path = {""};
        final CubismJvmSettingsService.CubismJvm[] jvm = {CubismJvmSettingsService.CubismJvm.BUNDLED};
        final CubismJvmSettingsService service = new CubismJvmSettingsService() {
            @Override
            public CubismJvmSettingsService.CubismJvm read() {
                return jvm[0];
            }

            @Override
            public CubismJvmSettingsService.CubismJvm save(final CubismJvmSettingsService.CubismJvm value) {
                return jvm[0] = value;
            }

            @Override
            public String graalVmPath() {
                return path[0];
            }

            @Override
            public String saveGraalVmPath(final String value) {
                return path[0] = value;
            }

            @Override
            public boolean graalVmPathCompatible(final String value) {
                return value == null || value.isBlank() || value.endsWith("\\bin");
            }

            @Override
            public boolean graalVmAvailable() {
                return path[0].endsWith("\\bin");
            }
        };
        final SettingsContribution pathContribution = CubismJvmSettingsContribution.createPath(localization(), service);
        final SettingsContribution jvmContribution = CubismJvmSettingsContribution.create(localization(), service);
        final CoreWindows windows = new CoreWindows(
                localization(),
                settings(),
                () -> List.of(new SettingsSnapshot.Tab(
                        "performance",
                        "Performance",
                        OptionalInt.of(200),
                        List.of(
                                new SettingsSnapshot.Entry("turboism.core", pathContribution),
                                new SettingsSnapshot.Entry("turboism.core", jvmContribution)))),
                plugins(),
                RuntimeLogReader.unavailable(),
                dev.turboism.internal.core.KeybindingService.unavailable());
        final Map<String, CoreWindows.BuiltinTab> builtins = new LinkedHashMap<>();
        builtins.put("performance", new CoreWindows.BuiltinTab("Performance", 200, new JPanel(new GridBagLayout())));
        final Method render = CoreWindows.class.getDeclaredMethod("renderSettings", JDialog.class, Map.class);
        render.setAccessible(true);
        final CoreWindows.RenderedSettings rendered =
                (CoreWindows.RenderedSettings) render.invoke(windows, null, builtins);
        final JPanel performance = formInTab(rendered.tabs(), 0);
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
    void memoryProfileChoiceRendersEveryTierWithRestartWordingAndPersistsOnSave() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<CubismJvmSettingsService.MemoryProfile> stored =
                new java.util.concurrent.atomic.AtomicReference<>(CubismJvmSettingsService.MemoryProfile.SYSTEM);
        final CubismJvmSettingsService service = new CubismJvmSettingsService() {
            @Override
            public CubismJvm read() {
                return CubismJvm.BUNDLED;
            }

            @Override
            public CubismJvm save(CubismJvm value) {
                return value;
            }

            @Override
            public CubismJvmSettingsService.MemoryProfile memoryProfile() {
                return stored.get();
            }

            @Override
            public CubismJvmSettingsService.MemoryProfile saveMemoryProfile(
                    final CubismJvmSettingsService.MemoryProfile value) {
                stored.set(value);
                return value;
            }
        };
        final CoreWindows windows = new CoreWindows(
                localization(),
                settings(),
                () -> List.of(new SettingsSnapshot.Tab(
                        "performance",
                        "Performance",
                        OptionalInt.of(200),
                        List.of(
                                new SettingsSnapshot.Entry(
                                        "turboism.core",
                                        CubismJvmSettingsContribution.createMemoryProfile(localization(), service)),
                                new SettingsSnapshot.Entry(
                                        "turboism.core",
                                        CubismJvmSettingsContribution.createMemoryProfileNote(localization()))))),
                plugins(),
                RuntimeLogReader.unavailable(),
                dev.turboism.internal.core.KeybindingService.unavailable());
        final Map<String, CoreWindows.BuiltinTab> builtins = new LinkedHashMap<>();
        builtins.put("performance", new CoreWindows.BuiltinTab("Performance", 200, new JPanel(new GridBagLayout())));
        final Method render = CoreWindows.class.getDeclaredMethod("renderSettings", JDialog.class, Map.class);
        render.setAccessible(true);
        final CoreWindows.RenderedSettings rendered =
                (CoreWindows.RenderedSettings) render.invoke(windows, null, builtins);
        final JPanel performance = formInTab(rendered.tabs(), 0);
        final JComboBox<?> combo = java.util.Arrays.stream(performance.getComponents())
                .filter(JComboBox.class::isInstance)
                .map(JComboBox.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(3, combo.getItemCount());
        assertEquals(
                List.of("system", "balanced4g", "balanced4gFastSoft"),
                java.util.stream.IntStream.range(0, combo.getItemCount())
                        .mapToObj(index -> ((SettingsControl.Option) combo.getItemAt(index)).value())
                        .toList());
        final javax.swing.JLabel label = java.util.Arrays.stream(performance.getComponents())
                .filter(javax.swing.JLabel.class::isInstance)
                .map(javax.swing.JLabel.class::cast)
                .filter(candidate -> "settings.cubism-jvm.memory-profile".equals(candidate.getText()))
                .findFirst()
                .orElseThrow();
        assertEquals("settings.cubism-jvm.memory-profile", label.getText());
        assertEquals("system", ((SettingsControl.Option) combo.getSelectedItem()).value());

        for (int index = 0; index < combo.getItemCount(); index++) {
            if (((SettingsControl.Option) combo.getItemAt(index)).value().equals("balanced4gFastSoft")) {
                combo.setSelectedIndex(index);
                break;
            }
        }
        assertTrue(rendered.save().getAsBoolean());
        assertEquals(CubismJvmSettingsService.MemoryProfile.BALANCED_4G_FAST_SOFT, stored.get());
    }

    @Test
    void customGraalVmPathUsesAVisibleHomeDirectoryPlaceholder() throws Exception {
        final CoreWindows windows = new CoreWindows(
                localization(),
                settings(),
                List::of,
                plugins(),
                RuntimeLogReader.unavailable(),
                dev.turboism.internal.core.KeybindingService.unavailable());
        final JPanel panel = new JPanel(new GridBagLayout());
        final SettingsControl.Text control = new SettingsControl.Text(
                CubismJvmSettingsContribution.PATH_CONTRIBUTION_ID,
                "Custom GraalVM path",
                36,
                SettingsBinding.of(() -> "", ignored -> {}));
        final Method render = CoreWindows.class.getDeclaredMethod(
                "renderControl", JDialog.class, JPanel.class, int.class, SettingsControl.class);
        render.setAccessible(true);

        render.invoke(windows, null, panel, 0, control);

        final JTextField field = java.util.Arrays.stream(panel.getComponents())
                .filter(JTextField.class::isInstance)
                .map(JTextField.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "settings.cubism-jvm.graalvm-path-placeholder", field.getClientProperty("JTextField.placeholderText"));
        assertEquals("settings.cubism-jvm.graalvm-path-placeholder", field.getToolTipText());
    }

    @Test
    void filePathSupportsBrowseCancelManualInputAndDeferredSave() throws Exception {
        final CoreWindows windows = new CoreWindows(
                localization(),
                settings(),
                List::of,
                plugins(),
                RuntimeLogReader.unavailable(),
                dev.turboism.internal.core.KeybindingService.unavailable());
        final JPanel panel = new JPanel(new GridBagLayout());
        final java.util.concurrent.atomic.AtomicReference<String> stored =
                new java.util.concurrent.atomic.AtomicReference<>("");
        final java.util.concurrent.atomic.AtomicReference<java.util.Optional<String>> chosen =
                new java.util.concurrent.atomic.AtomicReference<>(java.util.Optional.of("C:/Paint Tool/sai.exe"));
        final SettingsControl.FilePath control = new SettingsControl.FilePath(
                "editor", "Editor", 36, SettingsBinding.of(stored::get, stored::set), "Browse", chosen::get);
        final var saver = windows.renderControl(null, panel, 0, control);
        final JPanel input = (JPanel) panel.getComponent(1);
        final JTextField field = (JTextField) input.getComponent(0);
        final javax.swing.JButton browse = (javax.swing.JButton) input.getComponent(1);
        browse.doClick();
        assertEquals("C:/Paint Tool/sai.exe", field.getText());
        assertEquals("", stored.get());
        chosen.set(java.util.Optional.empty());
        browse.doClick();
        assertEquals("C:/Paint Tool/sai.exe", field.getText());
        field.setText("C:/Other Editor/editor.exe");
        assertTrue(saver.getAsBoolean());
        assertEquals("C:/Other Editor/editor.exe", stored.get());
        field.setText("");
        assertTrue(saver.getAsBoolean());
        assertEquals("", stored.get());
    }

    @Test
    void noteRendersAsDisabledCaptionWithNothingToSave() throws Exception {
        final CoreWindows windows = new CoreWindows(
                localization(),
                settings(),
                List::of,
                plugins(),
                RuntimeLogReader.unavailable(),
                dev.turboism.internal.core.KeybindingService.unavailable());
        final JPanel panel = new JPanel(new GridBagLayout());
        final SettingsControl.Note control = new SettingsControl.Note(
                "performance-restart-note", "All performance adjustments take effect after restarting the editor");
        final Method render = CoreWindows.class.getDeclaredMethod(
                "renderControl", JDialog.class, JPanel.class, int.class, SettingsControl.class);
        render.setAccessible(true);

        final java.util.function.BooleanSupplier saver =
                (java.util.function.BooleanSupplier) render.invoke(windows, null, panel, 0, control);

        final javax.swing.JLabel caption = java.util.Arrays.stream(panel.getComponents())
                .filter(javax.swing.JLabel.class::isInstance)
                .map(javax.swing.JLabel.class::cast)
                .filter(label -> control.label().equals(label.getText()))
                .findFirst()
                .orElseThrow();
        assertEquals(control.label(), caption.getText());
        assertTrue(!caption.isEnabled());
        assertTrue(caption.getFont().getSize2D()
                >= new javax.swing.JLabel().getFont().getSize2D());
        assertTrue(saver.getAsBoolean());
    }

    @Test
    void recommendationNoteCarriesTheRecommendedProfileTooltip() throws Exception {
        final CoreWindows windows = new CoreWindows(
                localization(),
                settings(),
                List::of,
                plugins(),
                RuntimeLogReader.unavailable(),
                dev.turboism.internal.core.KeybindingService.unavailable());
        final JPanel panel = new JPanel(new GridBagLayout());
        final SettingsControl.Note control =
                new SettingsControl.Note("performance-recommendation", "Recommended profile");
        final Method render = CoreWindows.class.getDeclaredMethod(
                "renderControl", JDialog.class, JPanel.class, int.class, SettingsControl.class);
        render.setAccessible(true);

        render.invoke(windows, null, panel, 0, control);

        final javax.swing.JLabel caption = java.util.Arrays.stream(panel.getComponents())
                .filter(javax.swing.JLabel.class::isInstance)
                .map(javax.swing.JLabel.class::cast)
                .filter(label -> control.label().equals(label.getText()))
                .findFirst()
                .orElseThrow();
        assertTrue(caption.isEnabled(), "the recommendation note must be hoverable");
        assertTrue(caption.getToolTipText() != null
                && caption.getToolTipText().contains("settings.performance.recommendation.tooltip"));
    }

    @Test
    void performanceSectionRendersAsCollapsibleTitledBoxWithTooltip() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final SettingsTab performanceTab = new SettingsTab("performance", "Performance", OptionalInt.of(200));
            final SettingsSnapshot.Tab tab = new SettingsSnapshot.Tab(
                    "performance",
                    "Performance",
                    OptionalInt.of(200),
                    List.of(
                            new SettingsSnapshot.Entry(
                                    "turboism.core",
                                    new SettingsContribution(
                                            "performance.section.model",
                                            performanceTab,
                                            OptionalInt.of(70),
                                            new SettingsControl.Note("performance.section.model", "Model updates"))),
                            new SettingsSnapshot.Entry(
                                    "turboism.core",
                                    new SettingsContribution(
                                            "model-toggle",
                                            performanceTab,
                                            OptionalInt.of(71),
                                            new SettingsControl.Toggle(
                                                    "model-toggle",
                                                    "Model update",
                                                    SettingsBinding.of(() -> false, ignored -> {}))))));
            try (CoreWindows windows = new CoreWindows(
                    localization(),
                    settings(),
                    () -> List.of(tab),
                    plugins(),
                    RuntimeLogReader.unavailable(),
                    dev.turboism.internal.core.KeybindingService.unavailable())) {
                final CoreWindows.RenderedSettings rendered = render(windows);
                final JScrollPane scroll = (JScrollPane) rendered.tabs().getComponentAt(0);
                final JPanel form = (JPanel) scroll.getViewport().getView();
                final JPanel section = findCollapsibleSection(form);
                assertTrue(section != null, "performance section must render as a collapsible box");
                assertTrue(section.getToolTipText() != null
                        && section.getToolTipText().contains("settings.performance.section.model.tooltip"));
                assertTrue(
                        findCheckBox(section, "Model update") != null, "the section content must contain its toggle");
                try {
                    final java.lang.reflect.Field delegate =
                            section.getBorder().getClass().getDeclaredField("delegate");
                    delegate.setAccessible(true);
                    assertEquals(
                            "Model updates",
                            ((javax.swing.border.TitledBorder) delegate.get(section.getBorder())).getTitle());
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            }
        });
    }

    /**
     * A long caption inside a section must wrap instead of collapsing the controls beside it.
     *
     * <p>The JVM section carries a multi-line memory-profile note. A plain grid bag refused to
     * shrink below the unwrapped caption width and squeezed every other row to zero size, which
     * left the section box empty on screen.</p>
     */
    @Test
    void longCaptionInsideASectionKeepsItsSiblingRowsVisible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final SettingsTab performanceTab = new SettingsTab("performance", "Performance", OptionalInt.of(200));
            final String caption = "Capped-heap profiles let the editor release its soft-reference caches under memory"
                    + " pressure instead of sizing the heap to all installed RAM, and the choice applies on"
                    + " the next Editor launch.";
            final SettingsSnapshot.Tab tab = new SettingsSnapshot.Tab(
                    "performance",
                    "Performance",
                    OptionalInt.of(200),
                    List.of(
                            new SettingsSnapshot.Entry(
                                    "turboism.core",
                                    new SettingsContribution(
                                            "performance.section.jvm",
                                            performanceTab,
                                            OptionalInt.of(110),
                                            new SettingsControl.Note("performance.section.jvm", "JVM and memory"))),
                            new SettingsSnapshot.Entry(
                                    "turboism.core",
                                    new SettingsContribution(
                                            "jvm-toggle",
                                            performanceTab,
                                            OptionalInt.of(111),
                                            new SettingsControl.Toggle(
                                                    "jvm-toggle",
                                                    "ZGC memory optimization",
                                                    SettingsBinding.of(() -> true, ignored -> {})))),
                            new SettingsSnapshot.Entry(
                                    "turboism.core",
                                    new SettingsContribution(
                                            "jvm-note",
                                            performanceTab,
                                            OptionalInt.of(116),
                                            new SettingsControl.Note("jvm-note", caption)))));
            try (CoreWindows windows = new CoreWindows(
                    localization(),
                    settings(),
                    () -> List.of(tab),
                    plugins(),
                    RuntimeLogReader.unavailable(),
                    dev.turboism.internal.core.KeybindingService.unavailable())) {
                final CoreWindows.RenderedSettings rendered = render(windows);
                final JTabbedPane tabs = rendered.tabs();
                tabs.setSize(780, 560);
                layoutTree(tabs);
                final JPanel section = findCollapsibleSection(tabs);
                assertTrue(section != null, "the JVM section must render as a collapsible box");
                final JCheckBox toggle = findCheckBox(section, "ZGC memory optimization");
                assertTrue(toggle != null, "the section must keep its toggle");
                assertTrue(
                        toggle.getWidth() > 0 && toggle.getHeight() > 0,
                        "the toggle must not collapse, bounds=" + toggle.getBounds());
                assertTrue(
                        toggle.getParent().getPreferredSize().width <= section.getWidth(),
                        "the section content must fit the dialog width, content="
                                + toggle.getParent().getPreferredSize()
                                + " section="
                                + section.getWidth());
            }
        });
    }

    private static JPanel findCollapsibleSection(final Component root) {
        if (root instanceof JPanel panel
                && panel.getBorder() != null
                && panel.getBorder().getClass().getName().contains("CollapsibleTitledBorder")) {
            return panel;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JPanel found = findCollapsibleSection(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JCheckBox findCheckBox(final Component root, final String text) {
        if (root instanceof JCheckBox box && text.equals(box.getText())) return box;
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JCheckBox found = findCheckBox(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void tallSettingsRemainReachableByWheelAndScrollBarAtLargeFonts() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (float size : new float[] {12f, 18f, 24f}) {
                try (CoreWindows windows = new CoreWindows(
                        localization(),
                        settings(),
                        () -> List.of(manySettings()),
                        plugins(),
                        RuntimeLogReader.unavailable(),
                        dev.turboism.internal.core.KeybindingService.unavailable())) {
                    CoreWindows.RenderedSettings rendered = render(windows);
                    JTabbedPane tabs = rendered.tabs();
                    tabs.setSize(620, 280);
                    JScrollPane scroll = assertInstanceOf(JScrollPane.class, tabs.getComponentAt(0));
                    setFontSize(scroll.getViewport().getView(), size);
                    layoutTree(tabs);
                    assertTrue(scroll.getVerticalScrollBar().isVisible(), "overflow must scroll at font " + size);
                    assertTrue(scroll.isWheelScrollingEnabled());
                    for (JCheckBox box : checkboxes(scroll)) {
                        assertTrue(box.getHeight() >= box.getMinimumSize().height, "rows must not collapse");
                    }
                    int before = scroll.getVerticalScrollBar().getValue();
                    scroll.dispatchEvent(new MouseWheelEvent(
                            scroll,
                            MouseWheelEvent.MOUSE_WHEEL,
                            System.currentTimeMillis(),
                            0,
                            20,
                            20,
                            0,
                            false,
                            MouseWheelEvent.WHEEL_UNIT_SCROLL,
                            3,
                            1));
                    assertTrue(scroll.getVerticalScrollBar().getValue() > before, "wheel must move content");
                    scroll.getVerticalScrollBar()
                            .setValue(scroll.getVerticalScrollBar().getMaximum());
                    JCheckBox last = checkboxes(scroll).get(29);
                    Rectangle bounds = SwingUtilities.convertRectangle(
                            last.getParent(),
                            last.getBounds(),
                            scroll.getViewport().getView());
                    assertTrue(scroll.getViewport().getViewRect().intersects(bounds), "last row must be reachable");
                    tabs.setSize(1000, 1800);
                    layoutTree(tabs);
                    assertFalse(scroll.getVerticalScrollBar().isVisible(), "scrollbar disappears when content fits");
                }
            }
        });
    }

    @Test
    void focusedSettingIsRevealedAndNarrowWindowDoesNotLoseLongLabels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (CoreWindows windows = new CoreWindows(
                    localization(),
                    settings(),
                    () -> List.of(manySettings()),
                    plugins(),
                    RuntimeLogReader.unavailable(),
                    dev.turboism.internal.core.KeybindingService.unavailable())) {
                CoreWindows.RenderedSettings rendered = render(windows);
                rendered.tabs().setSize(280, 240);
                JScrollPane scroll =
                        assertInstanceOf(JScrollPane.class, rendered.tabs().getComponentAt(0));
                layoutTree(rendered.tabs());
                assertFalse(
                        scroll.getHorizontalScrollBar().isVisible(), "settings must fit without horizontal scrolling");
                assertEquals(
                        scroll.getViewport().getExtentSize().width,
                        scroll.getViewport().getView().getWidth());
                JCheckBox first = checkboxes(scroll).get(0);
                assertTrue(
                        first.getHeight()
                                > first.getFontMetrics(first.getFont()).getHeight() * 2,
                        "long option labels must wrap instead of being clipped");
                JCheckBox last = checkboxes(scroll).get(29);
                for (var listener : last.getFocusListeners()) {
                    listener.focusGained(new FocusEvent(last, FocusEvent.FOCUS_GAINED));
                }
                Rectangle bounds = SwingUtilities.convertRectangle(
                        last.getParent(), last.getBounds(), scroll.getViewport().getView());
                assertTrue(scroll.getViewport().getViewRect().intersects(bounds), "keyboard focus must reveal row");
                Scrollable view =
                        assertInstanceOf(Scrollable.class, scroll.getViewport().getView());
                assertTrue(view.getScrollableUnitIncrement(new Rectangle(0, 0, 100, 100), 1, 1) > 0);
                assertTrue(view.getScrollableBlockIncrement(new Rectangle(0, 0, 1, 1), 1, 1) > 0);
            }
        });
    }

    @Test
    void pluginSettingsFitLongControlsInSingleAndMultipleTabs() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (CoreWindows windows = new CoreWindows(
                    localization(),
                    settings(),
                    List::of,
                    plugins(),
                    RuntimeLogReader.unavailable(),
                    dev.turboism.internal.core.KeybindingService.unavailable())) {
                final String longLabel =
                        "A long localized caption with <literal> & symbols that must stay readable at narrow widths ";
                final String path = "/very/long/directory/".repeat(12);
                final String[] stored = {path};
                final List<SettingsControl> controls = List.of(
                        new SettingsControl.Note("note", longLabel.repeat(3)),
                        new SettingsControl.Toggle("toggle", longLabel, SettingsBinding.of(() -> true, ignored -> {})),
                        new SettingsControl.Text("text", longLabel, 128, SettingsBinding.of(() -> path, ignored -> {})),
                        new SettingsControl.FilePath(
                                "path",
                                longLabel,
                                128,
                                SettingsBinding.of(() -> stored[0], value -> stored[0] = value),
                                "Browse…",
                                java.util.Optional::empty),
                        new SettingsControl.Choice(
                                "choice",
                                longLabel,
                                List.of(new SettingsControl.Option("one", longLabel)),
                                SettingsBinding.of(() -> "one", ignored -> {})));
                final SettingsTab tab = new SettingsTab("plugin", "Plugin");
                final SettingsSnapshot.Tab snapshot = new SettingsSnapshot.Tab(
                        "plugin",
                        "Plugin",
                        OptionalInt.empty(),
                        controls.stream()
                                .map(control -> new SettingsSnapshot.Entry(
                                        "plugin.test", new SettingsContribution(control.id(), tab, control)))
                                .toList());
                final PluginSettingsWindow pluginWindow =
                        new PluginSettingsWindow(localization(), null, plugins(), windows::renderControl);
                try {
                    final Method render = PluginSettingsWindow.class.getDeclaredMethod(
                            "renderPluginTabs", JDialog.class, List.class, List.class);
                    render.setAccessible(true);
                    for (List<SettingsSnapshot.Tab> tabs : List.of(List.of(snapshot), List.of(snapshot, snapshot))) {
                        final List<java.util.function.BooleanSupplier> saves = new java.util.ArrayList<>();
                        final JPanel panel = (JPanel) render.invoke(pluginWindow, null, tabs, saves);
                        final JScrollPane scroll = panel.getComponent(0) instanceof JTabbedPane tabbed
                                ? (JScrollPane) tabbed.getComponentAt(0)
                                : (JScrollPane) panel.getComponent(0);
                        for (int width : new int[] {340, 760, 340}) {
                            panel.setSize(width, 260);
                            layoutTree(panel);
                            assertFalse(scroll.getHorizontalScrollBar().isVisible());
                            assertEquals(
                                    scroll.getViewport().getExtentSize().width,
                                    scroll.getViewport().getView().getWidth());
                            final JPanel form =
                                    (JPanel) ((Container) scroll.getViewport().getView()).getComponent(0);
                            int previousBottom = 0;
                            for (Component child : form.getComponents()) {
                                if (child.getWidth() == 0) continue;
                                assertTrue(
                                        child.getX() >= 0 && child.getX() + child.getWidth() <= form.getWidth(),
                                        "every caption and input must fit the page");
                                assertTrue(
                                        child.getY() >= previousBottom || width >= 760,
                                        "stacked captions and inputs must not overlap");
                                previousBottom = child.getY() + child.getHeight();
                            }
                            final javax.swing.JLabel note = (javax.swing.JLabel) form.getComponent(0);
                            assertTrue(note.getHeight()
                                    > note.getFontMetrics(note.getFont()).getHeight());
                            assertEquals(longLabel.repeat(3), note.getText());
                            assertTrue(scroll.getVerticalScrollBar().isVisible() || width >= 760);
                            final JPanel pathInput = (JPanel) form.getComponent(7);
                            final JTextField field = (JTextField) pathInput.getComponent(0);
                            assertTrue(field.getWidth() > 0);
                            assertEquals(path, field.getText());
                        }
                        assertEquals(path, stored[0]);
                        for (var save : saves) assertTrue(save.getAsBoolean());
                        assertEquals(path, stored[0]);
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            }
        });
    }

    @Test
    void renderedOptimizationDefaultsAndExplicitOptOutSurviveScrollingAndSave() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean uniform = new java.util.concurrent.atomic.AtomicBoolean(true);
        final OptimizationSettingsService service = new OptimizationSettingsService() {
            @Override
            public boolean enabled(final String key) {
                return "uniformLocationCache".equals(key) && uniform.get();
            }

            @Override
            public void setEnabled(final String key, final boolean value) {
                if ("uniformLocationCache".equals(key)) uniform.set(value);
                else assertFalse(value, "the unrelated row stays off");
            }
        };
        String key = "turboism.optimization.uniformLocationCache";
        String previous = System.getProperty(key);
        String incrementalKey = "turboism.optimization.incrementalUpdate";
        String previousIncremental = System.getProperty(incrementalKey);
        try {
            SwingUtilities.invokeAndWait(() -> {
                List<SettingsSnapshot.Entry> entries = List.of(
                        new SettingsSnapshot.Entry(
                                "turboism.core",
                                OptimizationSettingsContribution.create(
                                        localization(), service, OptimizationCatalog.UNIFORM_LOCATION_CACHE)),
                        new SettingsSnapshot.Entry(
                                "turboism.core",
                                OptimizationSettingsContribution.create(
                                        localization(), service, OptimizationCatalog.INCREMENTAL_UPDATE)));
                try (CoreWindows windows = new CoreWindows(
                        localization(),
                        settings(),
                        () -> List.of(
                                new SettingsSnapshot.Tab("performance", "Performance", OptionalInt.of(200), entries)),
                        plugins(),
                        RuntimeLogReader.unavailable(),
                        dev.turboism.internal.core.KeybindingService.unavailable())) {
                    CoreWindows.RenderedSettings rendered = render(windows);
                    JScrollPane scroll =
                            assertInstanceOf(JScrollPane.class, rendered.tabs().getComponentAt(0));
                    List<JCheckBox> boxes = checkboxes(scroll);
                    assertTrue(boxes.get(0).isSelected(), "proven uniform cache is on by default");
                    assertFalse(boxes.get(1).isSelected(), "Slice B stays explicitly opt-in");
                    boxes.get(0).doClick();
                    assertTrue(uniform.get(), "click alone must not persist before Apply");
                    assertTrue(rendered.save().getAsBoolean());
                    assertFalse(uniform.get());
                    assertNull(System.getProperty(key), "Apply saves the next launch without mutating installed hooks");
                    assertFalse(checkboxes(render(windows).tabs()).get(0).isSelected(), "reopen preserves opt-out");
                }
            });
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
            if (previousIncremental == null) System.clearProperty(incrementalKey);
            else System.setProperty(incrementalKey, previousIncremental);
        }
    }

    @Test
    void displaySessionKeepsActionsVisibleAndRevealsRealKeyboardFocus() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "true".equals(System.getenv("TURBOISM_TEST_SETTINGS_DISPLAY")),
                "opt-in virtual-display test; not Cubism host evidence");
        assertFalse(java.awt.GraphicsEnvironment.isHeadless());
        final CoreWindows windows = new CoreWindows(
                localization(),
                settings(),
                () -> List.of(manySettings()),
                plugins(),
                RuntimeLogReader.unavailable(),
                dev.turboism.internal.core.KeybindingService.unavailable());
        final JDialog[] dialog = {null};
        final JScrollPane[] page = {null};
        final JCheckBox[] last = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Method create = CoreWindows.class.getDeclaredMethod("createSettingsDialog");
                    create.setAccessible(true);
                    dialog[0] = (JDialog) create.invoke(windows);
                    dialog[0].setSize(620, 340);
                    JTabbedPane tabs = (JTabbedPane)
                            ((java.awt.BorderLayout) dialog[0].getContentPane().getLayout())
                                    .getLayoutComponent(java.awt.BorderLayout.CENTER);
                    for (int index = 0; index < tabs.getTabCount(); index++) {
                        if (tabs.getTitleAt(index).equals("settings.tab.performance")) {
                            tabs.setSelectedIndex(index);
                            page[0] = (JScrollPane) tabs.getComponentAt(index);
                        }
                    }
                    assertTrue(page[0] != null);
                    setFontSize(page[0].getViewport().getView(), 24f);
                    dialog[0].setVisible(true);
                    dialog[0].validate();
                    assertTrue(page[0].getVerticalScrollBar().isVisible());
                    JPanel actions = (JPanel)
                            ((java.awt.BorderLayout) dialog[0].getContentPane().getLayout())
                                    .getLayoutComponent(java.awt.BorderLayout.SOUTH);
                    assertEquals(3, actions.getComponentCount());
                    assertFalse(SwingUtilities.isDescendingFrom(actions, page[0]));
                    assertTrue(new Rectangle(dialog[0].getContentPane().getSize()).contains(actions.getBounds()));
                    last[0] = checkboxes(page[0]).get(29);
                    dialog[0].toFront();
                    dialog[0].requestFocus();
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            });
            Await.condition(
                    "window activation must finish before testing control focus",
                    () -> {
                        final boolean[] focused = {false};
                        try {
                            SwingUtilities.invokeAndWait(() -> focused[0] = dialog[0].isFocused());
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                        return focused[0];
                    },
                    java.time.Duration.ofSeconds(3));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(dialog[0].isFocused(), "window activation must finish before testing control focus");
                assertTrue(last[0].requestFocusInWindow());
            });
            Await.condition(
                    "offscreen setting receives real focus",
                    () -> {
                        final boolean[] focused = {false};
                        try {
                            SwingUtilities.invokeAndWait(() -> focused[0] = last[0].isFocusOwner());
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                        return focused[0];
                    },
                    java.time.Duration.ofSeconds(3));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(last[0].isFocusOwner(), "offscreen setting receives real focus");
                Rectangle bounds = SwingUtilities.convertRectangle(
                        last[0].getParent(),
                        last[0].getBounds(),
                        page[0].getViewport().getView());
                assertTrue(page[0].getViewport().getViewRect().intersects(bounds));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialog[0] != null) dialog[0].dispose();
                windows.close();
            });
        }
    }

    @Test
    void separateExportDirectoryLivesInRuntimeAndStartupContributionsDoNotHideBuiltinCheckboxes() throws Exception {
        final RuntimeSettings[] stored = {new RuntimeSettings(false, "INFO", false, false, false)};
        final RuntimeSettingsService runtimeSettings = new RuntimeSettingsService() {
            @Override
            public RuntimeSettings read() {
                return stored[0];
            }

            @Override
            public RuntimeSettings save(final RuntimeSettings value) {
                return stored[0] = value;
            }

            @Override
            public DockCleanupResult cleanEmptyDocks() {
                return new DockCleanupResult("done");
            }
        };
        final SettingsTab startupTab = new SettingsTab("startup", "settings.tab.startup", OptionalInt.of(300));
        final dev.turboism.internal.core.CoreFrameworkUpdates.Phase[] activePhase = {
            dev.turboism.internal.core.CoreFrameworkUpdates.Phase.IDLE
        };
        final dev.turboism.internal.core.CoreUpdateService updates =
                new dev.turboism.internal.core.CoreUpdateService() {
                    private Preferences storedPrefs =
                            new Preferences(true, dev.turboism.internal.core.CoreUpdateService.CHANNEL_STABLE);

                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public Snapshot snapshot() {
                        return Snapshot.idle("0.70.0");
                    }

                    @Override
                    public Preferences preferences() {
                        return storedPrefs;
                    }

                    @Override
                    public void start() {}

                    @Override
                    public java.util.concurrent.CompletionStage<Snapshot> checkManual() {
                        return java.util.concurrent.CompletableFuture.completedFuture(snapshot());
                    }

                    @Override
                    public PreferenceSaveResult savePreferences(final Preferences preferences) {
                        storedPrefs = preferences;
                        return PreferenceSaveResult.success();
                    }

                    @Override
                    public dev.turboism.internal.core.CoreFrameworkUpdates frameworkUpdates() {
                        return new dev.turboism.internal.core.CoreFrameworkUpdates() {
                            @Override
                            public Snapshot snapshot() {
                                return new Snapshot(activePhase[0], "", 0, 0, "");
                            }

                            @Override
                            public java.util.concurrent.CompletionStage<Snapshot> download() {
                                return java.util.concurrent.CompletableFuture.completedFuture(snapshot());
                            }

                            @Override
                            public void cancel() {}

                            @Override
                            public java.util.concurrent.CompletionStage<Snapshot> installOnExit() {
                                return java.util.concurrent.CompletableFuture.completedFuture(snapshot());
                            }

                            @Override
                            public dev.turboism.sdk.plugin.Registration subscribe(
                                    final java.util.function.Consumer<Snapshot> listener) {
                                return () -> {};
                            }
                        };
                    }

                    @Override
                    public dev.turboism.sdk.plugin.Registration subscribe(
                            final java.util.function.Consumer<Snapshot> listener) {
                        return () -> {};
                    }

                    @Override
                    public void close() {}
                };
        final SettingsContribution automatic = CoreUpdateSettingsContribution.create(localization(), updates);
        final SettingsContribution channel = CoreUpdateSettingsContribution.createChannel(localization(), updates);
        final SettingsSnapshot.Tab contributedStartup = new SettingsSnapshot.Tab(
                "startup",
                "settings.tab.startup",
                OptionalInt.of(300),
                List.of(
                        new SettingsSnapshot.Entry("turboism.core", automatic),
                        new SettingsSnapshot.Entry("turboism.core", channel),
                        new SettingsSnapshot.Entry(
                                "turboism.core",
                                new SettingsContribution(
                                        CoreUpdateSettingsContribution.PLUGIN_UPDATES_CONTRIBUTION_ID,
                                        startupTab,
                                        OptionalInt.of(45),
                                        new SettingsControl.Toggle(
                                                CoreUpdateSettingsContribution.PLUGIN_UPDATES_CONTRIBUTION_ID,
                                                "settings.plugins.automatic-updates",
                                                SettingsBinding.of(() -> true, ignored -> {}))))));
        SwingUtilities.invokeAndWait(() -> {
            try (CoreWindows windows = new CoreWindows(
                    localization(),
                    runtimeSettings,
                    () -> List.of(contributedStartup),
                    plugins(),
                    RuntimeLogReader.unavailable(),
                    dev.turboism.internal.core.KeybindingService.unavailable())) {
                final CoreWindows.RenderedSettings rendered = windows.createSettingsContent(null);
                final JTabbedPane tabs = rendered.tabs();
                tabs.setSize(780, 560);
                layoutTree(tabs);

                final int runtimeIndex = tabs.indexOfTab("settings.tab.runtime");
                final int startupIndex = tabs.indexOfTab("settings.tab.startup");
                assertTrue(runtimeIndex >= 0);
                assertTrue(startupIndex >= 0);

                final JPanel runtimeForm = formInTab(tabs, runtimeIndex);
                final JPanel startupForm = formInTab(tabs, startupIndex);

                final JCheckBox separateExportBox =
                        findCheckBox(runtimeForm, "settings.separate-export-save-directory");
                assertTrue(separateExportBox != null, "separate export directory belongs in Runtime");
                assertTrue(separateExportBox.getWidth() > 0 && separateExportBox.getHeight() > 0);
                assertNull(findCheckBox(startupForm, "settings.separate-export-save-directory"));

                final List<String> expectedStartupLabels = List.of(
                        "settings.skip-update",
                        "settings.skip-splash",
                        "settings.skip-information",
                        "settings.updates.automatic",
                        "settings.plugins.automatic-updates");
                int previousBottom = -1;
                for (final String label : expectedStartupLabels) {
                    final JCheckBox box = findCheckBox(startupForm, label);
                    assertTrue(box != null, "missing startup checkbox: " + label);
                    assertTrue(
                            box.getWidth() > 0 && box.getHeight() > 0,
                            "startup checkbox collapsed: " + label + " bounds=" + box.getBounds());
                    assertTrue(
                            box.getY() >= previousBottom,
                            "startup checkbox overlapped: " + label + " y=" + box.getY() + " prevBottom="
                                    + previousBottom);
                    previousBottom = box.getY() + box.getHeight();
                }

                final JComboBox<?> channelCombo = findComboBox(startupForm);
                assertTrue(channelCombo != null, "missing update channel dropdown on Startup tab");
                assertTrue(channelCombo.getWidth() > 0 && channelCombo.getHeight() > 0);
                selectOption(channelCombo, dev.turboism.internal.core.CoreUpdateService.CHANNEL_BETA);

                separateExportBox.doClick();
                findCheckBox(startupForm, "settings.skip-update").doClick();
                assertTrue(rendered.save().getAsBoolean());
                assertTrue(stored[0].separateExportSaveDirectory());
                assertTrue(stored[0].skipStartupUpdateCheck());
                assertEquals(dev.turboism.internal.core.CoreUpdateService.CHANNEL_BETA, updates.updateChannel());

                activePhase[0] = dev.turboism.internal.core.CoreFrameworkUpdates.Phase.APPLY_PENDING;
                final SettingsControl.Choice choice = assertInstanceOf(SettingsControl.Choice.class, channel.control());
                assertFalse(choice.validator()
                        .validate(
                                dev.turboism.internal.core.CoreUpdateService.CHANNEL_BETA,
                                dev.turboism.internal.core.CoreUpdateService.CHANNEL_STABLE)
                        .accepted());
            }
        });
    }

    @Test
    void optimizationPresetSynchronizesLiveCheckboxesTwoWayAndPersistsOnSave() throws Exception {
        final Map<String, Boolean> stored = new LinkedHashMap<>();
        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            stored.put(optimization.key(), false);
        }
        final OptimizationSettingsService service = new OptimizationSettingsService() {
            @Override
            public boolean enabled(final String key) {
                return Boolean.TRUE.equals(stored.get(key));
            }

            @Override
            public void setEnabled(final String key, final boolean enabled) {
                stored.put(key, enabled);
            }
        };
        final List<SettingsSnapshot.Entry> entries = new java.util.ArrayList<>();
        entries.add(new SettingsSnapshot.Entry(
                "turboism.core", OptimizationSettingsContribution.createPreset(localization(), service)));
        for (final SettingsContribution contribution :
                OptimizationSettingsContribution.create(localization(), service)) {
            entries.add(new SettingsSnapshot.Entry("turboism.core", contribution));
        }

        SwingUtilities.invokeAndWait(() -> {
            try (CoreWindows windows = new CoreWindows(
                    localization(),
                    settings(),
                    () -> List.of(new SettingsSnapshot.Tab("performance", "Performance", OptionalInt.of(200), entries)),
                    plugins(),
                    RuntimeLogReader.unavailable(),
                    dev.turboism.internal.core.KeybindingService.unavailable())) {
                final CoreWindows.RenderedSettings rendered = render(windows);
                final JScrollPane scroll =
                        assertInstanceOf(JScrollPane.class, rendered.tabs().getComponentAt(0));
                final JComboBox<?> combo = findComboBox(scroll);
                assertTrue(combo != null, "preset dropdown must be rendered");
                assertTrue(
                        combo.getToolTipText() != null
                                && combo.getToolTipText().contains("settings.performance.preset.tooltip"),
                        "preset dropdown must resolve its tooltip");
                assertEquals("off", ((SettingsControl.Option) combo.getSelectedItem()).value());

                final List<JCheckBox> boxes = checkboxes(scroll);
                assertEquals(43, boxes.size());
                assertTrue(boxes.stream().noneMatch(JCheckBox::isSelected));

                // 1. Selecting BALANCED updates all checkboxes immediately without persisting yet.
                selectOption(combo, OptimizationPreset.BALANCED.configValue());
                for (final Optimization optimization : OptimizationCatalog.persisted()) {
                    final JCheckBox box = findCheckBoxByName(boxes, optimization.contributionId());
                    assertEquals(
                            OptimizationPreset.BALANCED.targetFlag(optimization).orElseThrow(),
                            box.isSelected(),
                            "live checkbox must sync to BALANCED for " + optimization.contributionId());
                }
                assertEquals(OptimizationPreset.OFF, OptimizationPreset.detect(stored));

                // 2. Flipping one trade-off checkbox switches preset to CUSTOM.
                findCheckBoxByName(boxes, OptimizationCatalog.INCREMENTAL_UPDATE.contributionId())
                        .doClick();
                assertEquals(
                        OptimizationPreset.CUSTOM.configValue(),
                        ((SettingsControl.Option) combo.getSelectedItem()).value());

                // 3. Flipping the remaining three trade-off checkboxes switches preset to HEAVY_EDITING.
                findCheckBoxByName(boxes, OptimizationCatalog.INCREMENTAL_UPDATE_AFFECTERS.contributionId())
                        .doClick();
                findCheckBoxByName(boxes, OptimizationCatalog.MESA_GL_THREAD.contributionId())
                        .doClick();
                findCheckBoxByName(boxes, OptimizationCatalog.ASYNC_READBACK.contributionId())
                        .doClick();
                assertEquals(
                        OptimizationPreset.HEAVY_EDITING.configValue(),
                        ((SettingsControl.Option) combo.getSelectedItem()).value());

                // 4. Selecting CUSTOM manually preserves all current checkbox states.
                selectOption(combo, OptimizationPreset.CUSTOM.configValue());
                assertTrue(boxes.stream().allMatch(JCheckBox::isSelected));

                // 5. Selecting BALANCED and clicking OK/Apply persists BALANCED cleanly (no unchecked overwrite).
                selectOption(combo, OptimizationPreset.BALANCED.configValue());
                assertTrue(rendered.save().getAsBoolean());
                assertEquals(OptimizationPreset.BALANCED, OptimizationPreset.detect(stored));

                // 6. Reopening reflects persisted BALANCED state; switching to OFF and saving persists all false.
                final CoreWindows.RenderedSettings reopened = render(windows);
                final JScrollPane reopenedScroll =
                        assertInstanceOf(JScrollPane.class, reopened.tabs().getComponentAt(0));
                final JComboBox<?> reopenedCombo = findComboBox(reopenedScroll);
                assertEquals(
                        OptimizationPreset.BALANCED.configValue(),
                        ((SettingsControl.Option) reopenedCombo.getSelectedItem()).value());
                selectOption(reopenedCombo, OptimizationPreset.OFF.configValue());
                assertTrue(checkboxes(reopenedScroll).stream().noneMatch(JCheckBox::isSelected));
                assertTrue(reopened.save().getAsBoolean());
                assertEquals(OptimizationPreset.OFF, OptimizationPreset.detect(stored));
            }
        });
    }

    @Test
    void hotpathSectionRendersAllNineTogglesReferenceLinkAndPersistsOnRestart(@TempDir final Path home)
            throws Exception {
        final List<Optimization> hotpath = List.of(
                OptimizationCatalog.EXTENDED_INTERPOLATION_SIBLING_ELISION,
                OptimizationCatalog.WARP_GRID_INTERPOLATION,
                OptimizationCatalog.WARP_NORMAL_POINT_ELISION,
                OptimizationCatalog.CAMERA_MATRIX_CACHE,
                OptimizationCatalog.RENDER_PASS_TRAVERSAL,
                OptimizationCatalog.MESH_HIT_TEST,
                OptimizationCatalog.SCENE_HANDLER_CACHE,
                OptimizationCatalog.CURSOR_VALIDATION_ELISION,
                OptimizationCatalog.PARAMETER_ROW_REPAINT);
        final URI expectedUri = OptimizationCatalog.EXTENDED_INTERPOLATION_SIBLING_REFERENCE_URI;
        final AtomicReference<URI> openedUri = new AtomicReference<>();
        CoreWindows.setTestSettingsLinkObserver(openedUri::set);
        try (OptimizationSettingsFileService service = new OptimizationSettingsFileService(home)) {
            final List<SettingsSnapshot.Entry> entries = new java.util.ArrayList<>();
            entries.add(new SettingsSnapshot.Entry(
                    "turboism.core", OptimizationSettingsContribution.createPreset(localization(), service)));
            entries.add(new SettingsSnapshot.Entry(
                    "turboism.core",
                    CubismJvmSettingsContribution.createPerformanceRecommendationNote(localization())));
            for (final SettingsContribution contribution :
                    OptimizationSettingsContribution.create(localization(), service)) {
                entries.add(new SettingsSnapshot.Entry("turboism.core", contribution));
            }
            entries.sort(java.util.Comparator.comparingInt(
                    entry -> entry.contribution().index().orElse(Integer.MAX_VALUE)));

            SwingUtilities.invokeAndWait(() -> {
                try (CoreWindows windows = new CoreWindows(
                        localization(),
                        settings(),
                        () -> List.of(
                                new SettingsSnapshot.Tab("performance", "Performance", OptionalInt.of(200), entries)),
                        plugins(),
                        RuntimeLogReader.unavailable(),
                        dev.turboism.internal.core.KeybindingService.unavailable())) {
                    final CoreWindows.RenderedSettings rendered = render(windows);
                    final JScrollPane scroll =
                            assertInstanceOf(JScrollPane.class, rendered.tabs().getComponentAt(0));
                    final JPanel form = (JPanel) scroll.getViewport().getView();
                    final JCheckBox atlasLoading =
                            findCheckBoxByName(checkboxes(form), OptimizationCatalog.ATLAS_LOADING.contributionId());
                    assertTrue(atlasLoading.isSelected(), "missing preference enables the atlas optimization");
                    atlasLoading.doClick();
                    final JCheckBox spatialIndex = findCheckBoxByName(
                            checkboxes(form), OptimizationCatalog.TRIANGULATION_SPATIAL_INDEX.contributionId());
                    assertTrue(
                            spatialIndex.isSelected(),
                            "missing preference enables the spatial triangulation optimization");
                    spatialIndex.doClick();
                    final JCheckBox modelOpen = findCheckBoxByName(
                            checkboxes(form), OptimizationCatalog.MODEL_OPEN_ACCELERATION.contributionId());
                    assertTrue(modelOpen.isSelected(), "missing preference enables model open acceleration");
                    modelOpen.doClick();
                    final JCheckBox runtimeExport = findCheckBoxByName(
                            checkboxes(form), OptimizationCatalog.RUNTIME_EXPORT_ACCELERATION.contributionId());
                    assertTrue(runtimeExport.isSelected(), "missing preference enables runtime export acceleration");
                    runtimeExport.doClick();
                    final JCheckBox meshEdit = findCheckBoxByName(
                            checkboxes(form), OptimizationCatalog.MESH_EDIT_ACCELERATION.contributionId());
                    assertTrue(meshEdit.isSelected(), "missing preference enables mesh edit acceleration");
                    meshEdit.doClick();
                    final JPanel topSection = findCollapsibleSection(form);
                    assertTrue(topSection != null, "top collapsible section must render");
                    assertTrue(
                            topSection.getToolTipText() != null
                                    && topSection
                                            .getToolTipText()
                                            .contains("settings.performance.section.hotpath.tooltip"),
                            "top section must carry the hotpath tooltip");

                    final List<JCheckBox> sectionBoxes = checkboxes(topSection);
                    assertEquals(
                            hotpath.stream().map(Optimization::contributionId).toList(),
                            sectionBoxes.stream().map(JCheckBox::getName).toList(),
                            "hotpath section must render all 9 promoted checkboxes in placement order");

                    final JButton referenceButton = findButtonByName(
                            topSection,
                            OptimizationCatalog.EXTENDED_INTERPOLATION_SIBLING_ELISION.contributionId() + ".reference");
                    assertTrue(referenceButton != null, "Comment_4556 toggle must render its reference link button");
                    assertEquals(expectedUri.toString(), referenceButton.getToolTipText());
                    rendered.tabs().setSize(780, 560);
                    layoutTree(rendered.tabs());
                    assertTrue(
                            sectionBoxes.get(0).getWidth() > referenceButton.getWidth(),
                            "checkbox caption must retain primary row width beside its reference link");
                    referenceButton.doClick();
                    assertEquals(expectedUri, openedUri.get(), "clicking reference link must open Comment_4556 URL");

                    for (final JCheckBox box : sectionBoxes) {
                        assertTrue(box.isSelected(), "promoted hotpath toggle must start enabled by default: " + box.getName());
                        box.doClick();
                    }
                    assertTrue(rendered.save().getAsBoolean());
                }
            });

            final var persistedRoot = new RuntimeConfigRepository(home, ignored -> {}).read();
            final PerformanceSettingsConfig reloaded = PerformanceSettingsConfig.load(home, ignored -> {});
            final NativeOptimizationConfig nativeReloaded = NativeOptimizationConfig.load(home, ignored -> {});
            final String atlasKey = OptimizationCatalog.ATLAS_LOADING.key();
            assertEquals(Boolean.FALSE, persistedRoot.path(atlasKey).booleanValue());
            assertTrue(persistedRoot.path(atlasKey).isBoolean());
            assertFalse(nativeReloaded.choice(OptimizationCatalog.ATLAS_LOADING).enabled());
            final String spatialKey = OptimizationCatalog.TRIANGULATION_SPATIAL_INDEX.key();
            assertEquals(Boolean.FALSE, persistedRoot.path(spatialKey).booleanValue());
            assertTrue(persistedRoot.path(spatialKey).isBoolean());
            assertFalse(nativeReloaded
                    .choice(OptimizationCatalog.TRIANGULATION_SPATIAL_INDEX)
                    .enabled());
            final String modelOpenKey = OptimizationCatalog.MODEL_OPEN_ACCELERATION.key();
            assertEquals(Boolean.FALSE, persistedRoot.path(modelOpenKey).booleanValue());
            assertTrue(persistedRoot.path(modelOpenKey).isBoolean());
            assertFalse(nativeReloaded
                    .choice(OptimizationCatalog.MODEL_OPEN_ACCELERATION)
                    .enabled());
            final String runtimeExportKey = OptimizationCatalog.RUNTIME_EXPORT_ACCELERATION.key();
            assertEquals(Boolean.FALSE, persistedRoot.path(runtimeExportKey).booleanValue());
            assertTrue(persistedRoot.path(runtimeExportKey).isBoolean());
            assertFalse(nativeReloaded
                    .choice(OptimizationCatalog.RUNTIME_EXPORT_ACCELERATION)
                    .enabled());
            final String meshEditKey = OptimizationCatalog.MESH_EDIT_ACCELERATION.key();
            assertEquals(Boolean.FALSE, persistedRoot.path(meshEditKey).booleanValue());
            assertTrue(persistedRoot.path(meshEditKey).isBoolean());
            assertFalse(nativeReloaded
                    .choice(OptimizationCatalog.MESH_EDIT_ACCELERATION)
                    .enabled());
            for (final Optimization optimization : hotpath) {
                assertEquals(
                        Boolean.FALSE,
                        persistedRoot.path("launcher").path(optimization.key()).booleanValue(),
                        "config.json launcher." + optimization.key() + " must persist false");
                final PerformanceSettingsConfig.Field field =
                        PerformanceSettingsConfig.Field.forKey(optimization.key());
                assertEquals("false", reloaded.value(field).value());
                assertEquals("global-config", reloaded.value(field).source());
                assertFalse(nativeReloaded.choice(optimization).enabled());
                assertEquals(
                        "global-config", nativeReloaded.choice(optimization).source());
            }
        } finally {
            CoreWindows.clearTestSettingsLinkObserver();
        }
    }

    private static JButton findButtonByName(final Component root, final String name) {
        if (root instanceof JButton button && name.equals(button.getName())) return button;
        if (root instanceof Container container) {
            for (final Component child : container.getComponents()) {
                final JButton found = findButtonByName(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JComboBox<?> findComboBox(final Component root) {
        if (root instanceof JComboBox<?> combo) return combo;
        if (root instanceof Container container) {
            for (final Component child : container.getComponents()) {
                final JComboBox<?> found = findComboBox(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JCheckBox findCheckBoxByName(final List<JCheckBox> boxes, final String name) {
        return boxes.stream()
                .filter(box -> name.equals(box.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing checkbox: " + name));
    }

    private static void selectOption(final JComboBox<?> combo, final String value) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            final SettingsControl.Option option = (SettingsControl.Option) combo.getItemAt(index);
            if (option.value().equals(value)) {
                combo.setSelectedIndex(index);
                return;
            }
        }
        throw new AssertionError("Missing option: " + value);
    }

    private static JPanel formInTab(JTabbedPane tabs, int index) {
        JScrollPane scroll = assertInstanceOf(JScrollPane.class, tabs.getComponentAt(index));
        Container view = (Container) scroll.getViewport().getView();
        return assertInstanceOf(JPanel.class, view.getComponent(0));
    }

    private static CoreWindows.RenderedSettings render(CoreWindows windows) {
        try {
            Method method = CoreWindows.class.getDeclaredMethod("renderSettings", JDialog.class, Map.class);
            method.setAccessible(true);
            return (CoreWindows.RenderedSettings) method.invoke(windows, null, Map.of());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static SettingsSnapshot.Tab manySettings() {
        return new SettingsSnapshot.Tab(
                "performance",
                "Performance",
                OptionalInt.of(200),
                java.util.stream.IntStream.range(0, 30)
                        .mapToObj(index -> new SettingsSnapshot.Entry(
                                "plugin.test",
                                new SettingsContribution(
                                        "setting-" + index,
                                        new SettingsTab("performance", "Performance", OptionalInt.of(200)),
                                        OptionalInt.of(index),
                                        new SettingsControl.Toggle(
                                                "setting-" + index,
                                                "Setting " + index + " with a long localized performance option label",
                                                SettingsBinding.of(() -> true, ignored -> {})))))
                        .toList());
    }

    private static List<JCheckBox> checkboxes(Container container) {
        List<JCheckBox> found = new java.util.ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JCheckBox box) found.add(box);
            else if (child instanceof Container nested) found.addAll(checkboxes(nested));
        }
        return found;
    }

    private static void setFontSize(Component component, float size) {
        if (component.getFont() != null) component.setFont(component.getFont().deriveFont(size));
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) setFontSize(child, size);
        }
    }

    private static void layoutTree(Container container) {
        for (int pass = 0; pass < 3; pass++) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                if (child instanceof Container nested) layoutTree(nested);
            }
        }
    }

    private static SettingsSnapshot.Tab tab(
            final String id, final String title, final SettingsContribution contribution) {
        return new SettingsSnapshot.Tab(
                id,
                title,
                contribution.tab().index(),
                List.of(new SettingsSnapshot.Entry("plugin.test", contribution)));
    }

    private static SettingsContribution contribution(
            final String id,
            final String tabId,
            final String tabTitle,
            final OptionalInt tabIndex,
            final OptionalInt index) {
        return new SettingsContribution(
                id,
                new SettingsTab(tabId, tabTitle, tabIndex),
                index,
                new SettingsControl.Toggle(id, id, SettingsBinding.of(() -> false, ignored -> {})));
    }

    private static PluginLocalization localization() {
        return FakePluginContext.passThroughLocalization(Locale.ENGLISH);
    }

    private static RuntimeSettingsService settings() {
        return new RuntimeSettingsService() {
            @Override
            public RuntimeSettings read() {
                return new RuntimeSettings(false, "INFO", false, false, false);
            }

            @Override
            public RuntimeSettings save(final RuntimeSettings value) {
                return value;
            }

            @Override
            public DockCleanupResult cleanEmptyDocks() {
                return new DockCleanupResult("done");
            }
        };
    }

    private static CorePluginManagement plugins() {
        return new CorePluginManagement() {
            @Override
            public List<PluginInfo> plugins() {
                return List.of();
            }

            @Override
            public OperationResult install() {
                return OperationResult.rejected("unavailable");
            }

            @Override
            public OperationResult uninstall(final String id) {
                return OperationResult.rejected("unavailable");
            }

            @Override
            public OperationResult setEnabled(final String id, final boolean enabled) {
                return OperationResult.rejected("unavailable");
            }
        };
    }
}
