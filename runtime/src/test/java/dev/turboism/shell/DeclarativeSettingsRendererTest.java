package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.atomic.AtomicInteger;
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
                                new SettingsSnapshot.Entry("turboism.core", jvmContribution),
                                new SettingsSnapshot.Entry("turboism.core", pathContribution)))),
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
                                            "performance.section.editing",
                                            performanceTab,
                                            OptionalInt.of(50),
                                            new SettingsControl.Note("performance.section.editing", "Editing"))),
                            new SettingsSnapshot.Entry(
                                    "turboism.core",
                                    new SettingsContribution(
                                            "model-toggle",
                                            performanceTab,
                                            OptionalInt.of(51),
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
                        && section.getToolTipText().contains("settings.performance.section.editing.tooltip"));
                assertTrue(
                        findCheckBox(section, "Model update") != null, "the section content must contain its toggle");
                try {
                    final java.lang.reflect.Field delegate =
                            section.getBorder().getClass().getDeclaredField("delegate");
                    delegate.setAccessible(true);
                    assertEquals(
                            "Editing",
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
                    assertEquals(2, actions.getComponentCount());
                    assertEquals(
                            "common.ok",
                            assertInstanceOf(JButton.class, actions.getComponent(0))
                                    .getText());
                    assertEquals(
                            "common.cancel",
                            assertInstanceOf(JButton.class, actions.getComponent(1))
                                    .getText());
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
            final AtomicInteger guideOpens = new AtomicInteger();
            try (CoreWindows windows = new CoreWindows(
                    localization(),
                    runtimeSettings,
                    () -> List.of(contributedStartup),
                    plugins(),
                    RuntimeLogReader.unavailable(),
                    dev.turboism.internal.core.KeybindingService.unavailable(),
                    guideOpens::incrementAndGet)) {
                final CoreWindows.RenderedSettings rendered = windows.createSettingsContent(null);
                final JTabbedPane tabs = rendered.tabs();
                tabs.setSize(780, 560);
                layoutTree(tabs);

                final int runtimeIndex = tabs.indexOfTab("settings.tab.runtime");
                final int startupIndex = tabs.indexOfTab("settings.tab.startup");
                final int maintenanceIndex = tabs.indexOfTab("settings.tab.maintenance");
                assertTrue(runtimeIndex >= 0);
                assertTrue(startupIndex >= 0);
                assertTrue(maintenanceIndex >= 0);

                final JButton guideButton =
                        findButtonByText(tabs.getComponentAt(maintenanceIndex), CoreWindows.NEW_USER_GUIDE_LABEL_KEY);
                assertNotNull(guideButton);
                guideButton.doClick();
                assertEquals(1, guideOpens.get());

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
                final JComboBox<?> channelCombo = findComboBox(startupForm);
                assertTrue(channelCombo != null, "missing update channel dropdown on Startup tab");
                assertTrue(channelCombo.getWidth() > 0 && channelCombo.getHeight() > 0);

                int previousBottom = channelCombo.getY() + channelCombo.getHeight();
                for (final String label : expectedStartupLabels) {
                    final JCheckBox box = findCheckBox(startupForm, label);
                    assertTrue(box != null, "missing startup checkbox: " + label);
                    final String tooltip = box.getToolTipText();
                    assertTrue(tooltip != null && tooltip.contains(label + ".tooltip"), "wrong tooltip for " + label);
                    assertTrue(
                            box.getWidth() > 0 && box.getHeight() > 0,
                            "startup checkbox collapsed: " + label + " bounds=" + box.getBounds());
                    assertTrue(
                            box.getY() >= previousBottom,
                            "startup checkbox overlapped: " + label + " y=" + box.getY() + " prevBottom="
                                    + previousBottom);
                    previousBottom = box.getY() + box.getHeight();
                }

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
        entries.sort(java.util.Comparator.comparingInt(
                entry -> entry.contribution().index().orElse(Integer.MAX_VALUE)));

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
                assertEquals(OptimizationCatalog.uiRows().size(), boxes.size());
                assertTrue(boxes.stream().noneMatch(JCheckBox::isSelected));

                final List<JPanel> sections = findCollapsibleSections(scroll);
                assertEquals(OptimizationCatalog.SECTIONS.size(), sections.size());
                final int jvmSectionIndex = OptimizationCatalog.SECTIONS.indexOf(OptimizationCatalog.SECTION_JVM);
                final int legacySectionIndex = OptimizationCatalog.SECTIONS.indexOf(OptimizationCatalog.SECTION_LEGACY);
                for (int index = 0; index < sections.size(); index++) {
                    final JCheckBox groupBox = findGroupToggleCheckBox(sections.get(index));
                    if (index == jvmSectionIndex) {
                        assertNull(groupBox, "section without boolean toggles must omit its header checkbox");
                    } else {
                        assertTrue(groupBox != null, "toggle section must expose a group checkbox");
                        assertFalse(groupBox.isSelected());
                        assertNull(groupBox.getClientProperty("JButton.selectedState"));
                    }
                }
                assertFalse(
                        sections.get(legacySectionIndex).getComponent(0).isVisible(),
                        "legacy section must be collapsed by default");

                // 1. Selecting BALANCED updates all checkboxes immediately without persisting yet.
                selectOption(combo, OptimizationPreset.BALANCED.configValue());
                for (final OptimizationCatalog.UiRow row : OptimizationCatalog.uiRows()) {
                    final JCheckBox box = findCheckBoxByName(boxes, row.contributionId());
                    final boolean expected = OptimizationPreset.BALANCED
                            .targetFlag(row.members().get(0))
                            .orElseThrow();
                    assertEquals(
                            expected,
                            box.isSelected(),
                            "live checkbox must sync to BALANCED for " + row.contributionId());
                }
                assertEquals(OptimizationPreset.OFF, OptimizationPreset.detect(stored));
                for (int index = 0; index < sections.size(); index++) {
                    if (index == jvmSectionIndex) continue;
                    final String sectionId = OptimizationCatalog.SECTIONS.get(index);
                    final List<Optimization> sectionOpts = OptimizationCatalog.uiRows().stream()
                            .filter(row -> row.section().equals(sectionId))
                            .flatMap(row -> row.members().stream())
                            .toList();
                    final boolean expectedAll = sectionOpts.stream()
                            .allMatch(opt ->
                                    OptimizationPreset.BALANCED.targetFlag(opt).orElseThrow());
                    final boolean expectedAny = sectionOpts.stream()
                            .anyMatch(opt ->
                                    OptimizationPreset.BALANCED.targetFlag(opt).orElseThrow());
                    final boolean expectedIndeterminate = !expectedAll && expectedAny;
                    assertGroupCheckBoxState(
                            findGroupToggleCheckBox(sections.get(index)), expectedAll, expectedIndeterminate);
                }

                // 2. Flipping one trade-off checkbox switches preset to CUSTOM.
                findCheckBoxByName(boxes, OptimizationCatalog.INCREMENTAL_UPDATE.contributionId())
                        .doClick();
                assertEquals(
                        OptimizationPreset.CUSTOM.configValue(),
                        ((SettingsControl.Option) combo.getSelectedItem()).value());

                // 3. Flipping the remaining unchecked checkboxes switches preset to HEAVY_EDITING.
                for (final JCheckBox box : boxes) {
                    if (!box.isSelected()) {
                        box.doClick();
                    }
                }
                assertEquals(
                        OptimizationPreset.HEAVY_EDITING.configValue(),
                        ((SettingsControl.Option) combo.getSelectedItem()).value());
                for (int index = 0; index < sections.size(); index++) {
                    if (index == jvmSectionIndex) continue;
                    assertGroupCheckBoxState(findGroupToggleCheckBox(sections.get(index)), true, false);
                }

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
                final List<JPanel> reopenedSections = findCollapsibleSections(reopenedScroll);
                for (int index = 0; index < reopenedSections.size(); index++) {
                    if (index == jvmSectionIndex) continue;
                    assertGroupCheckBoxState(findGroupToggleCheckBox(reopenedSections.get(index)), false, false);
                }
                assertTrue(reopened.save().getAsBoolean());
                assertEquals(OptimizationPreset.OFF, OptimizationPreset.detect(stored));
            }
        });
    }

    @Test
    void consolidatedEditingSectionRendersBundlesReferenceLinkAndMixedMemberTriState(@TempDir final Path home)
            throws Exception {
        final OptimizationCatalog.UiRow warpRow = OptimizationCatalog.uiRowByContributionId(
                        "editing-warp-interpolation")
                .orElseThrow();
        final OptimizationCatalog.UiRow atlasRow =
                OptimizationCatalog.uiRowByContributionId("assets-atlas").orElseThrow();
        final OptimizationCatalog.UiRow meshRow =
                OptimizationCatalog.uiRowByContributionId("assets-mesh").orElseThrow();
        final OptimizationCatalog.UiRow modelIoRow =
                OptimizationCatalog.uiRowByContributionId("assets-model-io").orElseThrow();
        final URI expectedUri = OptimizationCatalog.EXTENDED_INTERPOLATION_SIBLING_REFERENCE_URI;
        final AtomicReference<URI> openedUri = new AtomicReference<>();
        CoreWindows.setTestSettingsLinkObserver(openedUri::set);
        try (OptimizationSettingsFileService service = new OptimizationSettingsFileService(home)) {
            // Seed a hand-edited mixed bundle state inside editing-model-update.
            service.setEnabled(OptimizationCatalog.MODEL_UPDATE_SKIP.key(), true);
            service.setEnabled(OptimizationCatalog.MODEL_INSTANCE_COALESCE.key(), false);

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
                    final List<JPanel> sections = findCollapsibleSections(scroll);
                    final JPanel editingSection = sections.get(0);
                    final JPanel legacySection =
                            sections.get(OptimizationCatalog.SECTIONS.indexOf(OptimizationCatalog.SECTION_LEGACY));
                    assertTrue(
                            editingSection.getToolTipText() != null
                                    && editingSection
                                            .getToolTipText()
                                            .contains("settings.performance.section.editing.tooltip"),
                            "top section must carry the editing tooltip");
                    assertFalse(
                            legacySection.getComponent(0).isVisible(),
                            "legacy section must remain collapsed on default open");

                    final List<JCheckBox> editingBoxes = checkboxes(editingSection);
                    assertEquals(
                            List.of("editing-model-update", "editing-warp-interpolation", "editing-canvas-input"),
                            editingBoxes.stream().map(JCheckBox::getName).toList());
                    final JCheckBox modelUpdateBox = editingBoxes.get(0);
                    assertFalse(modelUpdateBox.isSelected());
                    assertEquals("indeterminate", modelUpdateBox.getClientProperty("JButton.selectedState"));
                    assertGroupCheckBoxState(findGroupToggleCheckBox(editingSection), false, true);

                    // Saving without touching the indeterminate bundle preserves mixed config.json values.
                    assertTrue(rendered.save().getAsBoolean());
                    assertTrue(service.enabled(OptimizationCatalog.MODEL_UPDATE_SKIP.key()));
                    assertFalse(service.enabled(OptimizationCatalog.MODEL_INSTANCE_COALESCE.key()));

                    // Clicking an indeterminate bundle checkbox checks all compatible members and clears indeterminate.
                    modelUpdateBox.doClick();
                    assertTrue(modelUpdateBox.isSelected());
                    assertNull(modelUpdateBox.getClientProperty("JButton.selectedState"));
                    assertGroupCheckBoxState(findGroupToggleCheckBox(editingSection), true, false);

                    final JButton referenceButton =
                            findButtonByName(editingSection, warpRow.contributionId() + ".reference");
                    assertTrue(
                            referenceButton != null, "warp bundle toggle must render the Comment_4556 reference link");
                    assertEquals(expectedUri.toString(), referenceButton.getToolTipText());
                    rendered.tabs().setSize(780, 560);
                    layoutTree(rendered.tabs());
                    assertTrue(
                            editingBoxes.get(1).getWidth() > referenceButton.getWidth(),
                            "checkbox caption must retain primary row width beside its reference link");
                    referenceButton.doClick();
                    assertEquals(expectedUri, openedUri.get(), "clicking reference link must open Comment_4556 URL");

                    // Turning off editing and assets bundles persists false for every underlying member key.
                    for (final JCheckBox box : editingBoxes) {
                        box.doClick();
                    }
                    findCheckBoxByName(checkboxes(form), atlasRow.contributionId())
                            .doClick();
                    findCheckBoxByName(checkboxes(form), meshRow.contributionId())
                            .doClick();
                    findCheckBoxByName(checkboxes(form), modelIoRow.contributionId())
                            .doClick();
                    assertTrue(rendered.save().getAsBoolean());
                }
            });

            final var persistedRoot = new RuntimeConfigRepository(home, ignored -> {}).read();
            final PerformanceSettingsConfig reloaded = PerformanceSettingsConfig.load(home, ignored -> {});
            final NativeOptimizationConfig nativeReloaded = NativeOptimizationConfig.load(home, ignored -> {});
            final List<Optimization> expectedDisabled = new java.util.ArrayList<>();
            for (final OptimizationCatalog.UiRow row : OptimizationCatalog.uiRows()) {
                if (OptimizationCatalog.SECTION_EDITING.equals(row.section())
                        || List.of("assets-atlas", "assets-mesh", "assets-model-io")
                                .contains(row.contributionId())) {
                    expectedDisabled.addAll(row.members());
                }
            }
            for (final Optimization optimization : expectedDisabled) {
                final boolean rootScoped = optimization.access() == Optimization.Access.PERSISTED_ROOT;
                final var jsonNode = rootScoped
                        ? persistedRoot.path(optimization.key())
                        : persistedRoot.path("launcher").path(optimization.key());
                assertEquals(
                        Boolean.FALSE,
                        jsonNode.booleanValue(),
                        "config.json " + optimization.key() + " must persist false");
                final PerformanceSettingsConfig.Field field =
                        PerformanceSettingsConfig.Field.forKey(optimization.key());
                assertEquals("false", reloaded.value(field).value());
                assertFalse(nativeReloaded.choice(optimization).enabled());
            }
        } finally {
            CoreWindows.clearTestSettingsLinkObserver();
        }
    }

    @Test
    void collapsibleGroupToggleSupportsAllOnAllOffIndeterminateAndDoesNotCollapse() throws Exception {
        final boolean[] zgc = {true};
        final boolean[] disableBackup = {false};
        final CubismJvmSettingsService.CubismJvm[] jvmChoice = {CubismJvmSettingsService.CubismJvm.BUNDLED};
        final String[] graalPath = {""};
        final CubismJvmSettingsService.MemoryProfile[] memProfile = {CubismJvmSettingsService.MemoryProfile.SYSTEM};
        final CubismJvmSettingsService jvmService = new CubismJvmSettingsService() {
            @Override
            public CubismJvm read() {
                return jvmChoice[0];
            }

            @Override
            public CubismJvm save(final CubismJvm value) {
                return jvmChoice[0] = value;
            }

            @Override
            public String graalVmPath() {
                return graalPath[0];
            }

            @Override
            public String saveGraalVmPath(final String value) {
                return graalPath[0] = value;
            }

            @Override
            public boolean reduceAutoBackup() {
                return disableBackup[0];
            }

            @Override
            public boolean saveReduceAutoBackup(final boolean value) {
                return disableBackup[0] = value;
            }

            @Override
            public boolean zgc() {
                return zgc[0];
            }

            @Override
            public boolean saveZgc(final boolean value) {
                return zgc[0] = value;
            }

            @Override
            public MemoryProfile memoryProfile() {
                return memProfile[0];
            }

            @Override
            public MemoryProfile saveMemoryProfile(final MemoryProfile value) {
                return memProfile[0] = value;
            }
        };
        final SettingsTab tab = new SettingsTab("performance", "Performance", OptionalInt.of(200));
        final List<SettingsSnapshot.Entry> entries = List.of(
                new SettingsSnapshot.Entry(
                        "turboism.core",
                        new SettingsContribution(
                                "performance.section.empty",
                                tab,
                                OptionalInt.of(40),
                                new SettingsControl.Note("performance.section.empty", "Notes only"))),
                new SettingsSnapshot.Entry(
                        "turboism.core",
                        new SettingsContribution(
                                "empty-note",
                                tab,
                                OptionalInt.of(41),
                                new SettingsControl.Note("empty-note", "No toggles inside"))),
                new SettingsSnapshot.Entry(
                        "turboism.core",
                        new SettingsContribution(
                                "performance.section.jvm",
                                tab,
                                OptionalInt.of(110),
                                new SettingsControl.Note("performance.section.jvm", "JVM and memory"))),
                new SettingsSnapshot.Entry(
                        "turboism.core", CubismJvmSettingsContribution.createZgcToggle(localization(), jvmService)),
                new SettingsSnapshot.Entry(
                        "turboism.core", CubismJvmSettingsContribution.createBackupDisable(localization(), jvmService)),
                new SettingsSnapshot.Entry(
                        "turboism.core", CubismJvmSettingsContribution.create(localization(), jvmService)),
                new SettingsSnapshot.Entry(
                        "turboism.core", CubismJvmSettingsContribution.createPath(localization(), jvmService)),
                new SettingsSnapshot.Entry(
                        "turboism.core", CubismJvmSettingsContribution.createMemoryProfile(localization(), jvmService)),
                new SettingsSnapshot.Entry(
                        "turboism.core", CubismJvmSettingsContribution.createMemoryProfileNote(localization())));

        SwingUtilities.invokeAndWait(() -> {
            try (CoreWindows windows = new CoreWindows(
                    localization(),
                    settings(),
                    () -> List.of(new SettingsSnapshot.Tab("performance", "Performance", OptionalInt.of(200), entries)),
                    plugins(),
                    RuntimeLogReader.unavailable(),
                    dev.turboism.internal.core.KeybindingService.unavailable())) {
                final CoreWindows.RenderedSettings rendered = render(windows);
                final JTabbedPane tabs = rendered.tabs();
                tabs.setSize(780, 560);
                layoutTree(tabs);
                final JScrollPane scroll = assertInstanceOf(JScrollPane.class, tabs.getComponentAt(0));
                final List<JPanel> sections = findCollapsibleSections(scroll);
                assertEquals(2, sections.size());

                final JPanel emptySection = sections.get(0);
                assertNull(
                        findGroupToggleCheckBox(emptySection),
                        "a section with zero boolean checkboxes must omit its header checkbox");

                final JPanel jvmSection = sections.get(1);
                final JCheckBox headerBox = findGroupToggleCheckBox(jvmSection);
                assertTrue(headerBox != null, "mixed JVM section must expose a group toggle checkbox");
                assertTrue(headerBox.getX() >= 0 && headerBox.getY() >= 0);
                assertTrue(headerBox.getWidth() > 0 && headerBox.getHeight() > 0);
                assertTrue(headerBox.getY() + headerBox.getHeight() <= jvmSection.getInsets().top);
                assertGroupCheckBoxState(headerBox, false, true);

                final JCheckBox zgcBox = findCheckBoxByName(checkboxes(jvmSection), "cubism-zgc");
                final JCheckBox backupBox = findCheckBoxByName(checkboxes(jvmSection), "cubism-disable-auto-backup");
                final JComboBox<?> jvmCombo = findComboBox(jvmSection);
                assertTrue(jvmCombo != null);
                assertEquals("bundled", ((SettingsControl.Option) jvmCombo.getSelectedItem()).value());

                final JPanel content = (JPanel) jvmSection.getComponent(0);
                assertTrue(content.isVisible());

                // Indeterminate -> click turns all boolean toggles ON without collapsing or touching combo/text.
                headerBox.doClick();
                assertTrue(zgcBox.isSelected());
                assertTrue(backupBox.isSelected());
                assertGroupCheckBoxState(headerBox, true, false);
                assertTrue(content.isVisible(), "clicking group checkbox must not collapse section");
                assertEquals("bundled", ((SettingsControl.Option) jvmCombo.getSelectedItem()).value());

                // All ON -> click turns all boolean toggles OFF.
                headerBox.doClick();
                assertFalse(zgcBox.isSelected());
                assertFalse(backupBox.isSelected());
                assertGroupCheckBoxState(headerBox, false, false);
                assertTrue(content.isVisible());

                // Clicking one child checkbox puts header back into indeterminate state.
                zgcBox.doClick();
                assertTrue(zgcBox.isSelected());
                assertFalse(backupBox.isSelected());
                assertGroupCheckBoxState(headerBox, false, true);

                // Clicking the title collapses and expands cleanly while header checkbox remains visible.
                final java.awt.image.BufferedImage paintBuffer =
                        new java.awt.image.BufferedImage(800, 600, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                final java.awt.Graphics2D g = paintBuffer.createGraphics();
                try {
                    jvmSection.paint(g);
                } finally {
                    g.dispose();
                }
                final var border = jvmSection.getBorder();
                final java.lang.reflect.Field titleBoundsField =
                        border.getClass().getDeclaredField("titleBounds");
                titleBoundsField.setAccessible(true);
                final Rectangle titleBounds = (Rectangle) titleBoundsField.get(border);
                final java.awt.Point titlePoint = new java.awt.Point(
                        titleBounds.x + titleBounds.width / 2, titleBounds.y + titleBounds.height / 2);
                jvmSection.dispatchEvent(new java.awt.event.MouseEvent(
                        jvmSection,
                        java.awt.event.MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(),
                        0,
                        titlePoint.x,
                        titlePoint.y,
                        1,
                        false,
                        java.awt.event.MouseEvent.BUTTON1));
                assertFalse(content.isVisible(), "clicking section title must toggle collapse");

                // Group toggle still works while collapsed without expanding the section.
                headerBox.doClick();
                assertTrue(zgcBox.isSelected() && backupBox.isSelected());
                assertFalse(content.isVisible(), "group checkbox must not expand a collapsed section");

                assertTrue(rendered.save().getAsBoolean());
                assertTrue(zgc[0]);
                assertTrue(disableBackup[0]);
                assertEquals(CubismJvmSettingsService.CubismJvm.BUNDLED, jvmChoice[0]);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    @Test
    void groupToggleEnforcesCatalogMutualExclusionBetweenUploadElisionAndUniformLocationCache() throws Exception {
        final Map<String, Boolean> stored = new LinkedHashMap<>();
        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            stored.put(optimization.key(), false);
        }
        stored.put(OptimizationCatalog.UPLOAD_ELISION.key(), true);
        stored.put(OptimizationCatalog.MESH_SETUP_ELISION.key(), true);
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
        entries.sort(java.util.Comparator.comparingInt(
                entry -> entry.contribution().index().orElse(Integer.MAX_VALUE)));

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
                final List<JPanel> sections = findCollapsibleSections(scroll);
                final JPanel renderingSection =
                        sections.get(OptimizationCatalog.SECTIONS.indexOf(OptimizationCatalog.SECTION_RENDERING));
                final JPanel legacySection =
                        sections.get(OptimizationCatalog.SECTIONS.indexOf(OptimizationCatalog.SECTION_LEGACY));
                final JCheckBox renderingGroup = findGroupToggleCheckBox(renderingSection);
                final JCheckBox legacyGroup = findGroupToggleCheckBox(legacySection);
                final JCheckBox uniformBox = findCheckBoxByName(
                        checkboxes(legacySection), OptimizationCatalog.UNIFORM_LOCATION_CACHE.contributionId());
                final JCheckBox pipelineBox =
                        findCheckBoxByName(checkboxes(renderingSection), "rendering-pipeline-cache");
                final JCheckBox uploadElisionBox =
                        findCheckBoxByName(checkboxes(renderingSection), "rendering-upload-elision");
                final JCheckBox inputElisionBox = findCheckBoxByName(
                        checkboxes(legacySection), OptimizationCatalog.INPUT_PATH_ELISION.contributionId());

                // 1. With uploadElision ON, toggling legacy ON must skip conflicting uniformLocationCache.
                assertTrue(uploadElisionBox.isSelected());
                assertFalse(uniformBox.isSelected());
                legacyGroup.doClick();
                assertTrue(inputElisionBox.isSelected(), "non-conflicting legacy options turn ON");
                assertFalse(uniformBox.isSelected(), "conflicting uniformLocationCache stays OFF");
                assertGroupCheckBoxState(legacyGroup, false, true);

                // Clicking legacy again when only conflicting opt-in remains OFF turns compatible items OFF.
                legacyGroup.doClick();
                assertFalse(inputElisionBox.isSelected());
                assertGroupCheckBoxState(legacyGroup, false, false);

                // 2. Turn uploadElision OFF, turn legacy ALL ON (including uniformLocationCache), then toggle
                // rendering ON.
                uploadElisionBox.doClick();
                assertFalse(uploadElisionBox.isSelected());
                legacyGroup.doClick();
                assertTrue(uniformBox.isSelected());
                assertGroupCheckBoxState(legacyGroup, true, false);

                renderingGroup.doClick();
                assertTrue(pipelineBox.isSelected(), "non-conflicting rendering options turn ON");
                assertFalse(uploadElisionBox.isSelected(), "conflicting uploadElision bundle stays OFF");
                assertGroupCheckBoxState(renderingGroup, false, true);
            }
        });

        // 3. Same-group mutual exclusion prefers default-ON uploadElision over opt-in uniformLocationCache.
        final SettingsTab tab = new SettingsTab("performance", "Performance", OptionalInt.of(200));
        final Map<String, Boolean> sameGroupState = new LinkedHashMap<>();
        final OptimizationSettingsService sameGroupService = new OptimizationSettingsService() {
            @Override
            public boolean enabled(final String key) {
                return Boolean.TRUE.equals(sameGroupState.get(key));
            }

            @Override
            public void setEnabled(final String key, final boolean enabled) {
                sameGroupState.put(key, enabled);
            }
        };
        final List<SettingsSnapshot.Entry> sameGroupEntries = List.of(
                new SettingsSnapshot.Entry(
                        "turboism.core",
                        new SettingsContribution(
                                "performance.section.rendering",
                                tab,
                                OptionalInt.of(80),
                                new SettingsControl.Note("performance.section.rendering", "Rendering"))),
                new SettingsSnapshot.Entry(
                        "turboism.core",
                        OptimizationSettingsContribution.create(
                                localization(), sameGroupService, OptimizationCatalog.UNIFORM_LOCATION_CACHE)),
                new SettingsSnapshot.Entry(
                        "turboism.core",
                        OptimizationSettingsContribution.create(
                                localization(), sameGroupService, OptimizationCatalog.UPLOAD_ELISION)));
        SwingUtilities.invokeAndWait(() -> {
            try (CoreWindows windows = new CoreWindows(
                    localization(),
                    settings(),
                    () -> List.of(new SettingsSnapshot.Tab(
                            "performance", "Performance", OptionalInt.of(200), sameGroupEntries)),
                    plugins(),
                    RuntimeLogReader.unavailable(),
                    dev.turboism.internal.core.KeybindingService.unavailable())) {
                final CoreWindows.RenderedSettings rendered = render(windows);
                final JScrollPane scroll =
                        assertInstanceOf(JScrollPane.class, rendered.tabs().getComponentAt(0));
                final JPanel section = findCollapsibleSection(scroll);
                final JCheckBox groupBox = findGroupToggleCheckBox(section);
                groupBox.doClick();
                final JCheckBox uploadBox =
                        findCheckBoxByName(checkboxes(section), OptimizationCatalog.UPLOAD_ELISION.contributionId());
                final JCheckBox uniformBox = findCheckBoxByName(
                        checkboxes(section), OptimizationCatalog.UNIFORM_LOCATION_CACHE.contributionId());
                assertTrue(uploadBox.isSelected(), "default-ON uploadElision wins within same group");
                assertFalse(uniformBox.isSelected(), "conflicting opt-in uniformLocationCache stays OFF");
                assertGroupCheckBoxState(groupBox, false, true);

                groupBox.doClick();
                assertFalse(uploadBox.isSelected());
                assertFalse(uniformBox.isSelected());
                assertGroupCheckBoxState(groupBox, false, false);
            }
        });
    }

    @Test
    void performanceTabOmitsOptimizationStatusButton() throws Exception {
        final OptimizationSettingsService service = new OptimizationSettingsService() {
            @Override
            public boolean enabled(final String key) {
                return false;
            }

            @Override
            public void setEnabled(final String key, final boolean enabled) {}
        };
        final List<SettingsSnapshot.Entry> entries = new java.util.ArrayList<>();
        entries.add(new SettingsSnapshot.Entry(
                "turboism.core", OptimizationSettingsContribution.createPreset(localization(), service)));
        entries.add(new SettingsSnapshot.Entry(
                "turboism.core", CubismJvmSettingsContribution.createPerformanceRecommendationNote(localization())));
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
                    () -> List.of(new SettingsSnapshot.Tab("performance", "Performance", OptionalInt.of(200), entries)),
                    plugins(),
                    RuntimeLogReader.unavailable(),
                    dev.turboism.internal.core.KeybindingService.unavailable())) {
                final CoreWindows.RenderedSettings rendered = render(windows);
                final JScrollPane scroll =
                        assertInstanceOf(JScrollPane.class, rendered.tabs().getComponentAt(0));
                assertNull(
                        findButtonByText(scroll, "settings.performance.status"),
                        "optimization status button must be removed from the Performance tab");
            }
        });
    }

    private static JButton findButtonByText(final Component root, final String text) {
        if (root instanceof JButton button && text.equals(button.getText())) return button;
        if (root instanceof Container container) {
            for (final Component child : container.getComponents()) {
                final JButton found = findButtonByText(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<JPanel> findCollapsibleSections(final Component root) {
        final List<JPanel> found = new java.util.ArrayList<>();
        collectCollapsibleSections(root, found);
        return found;
    }

    private static void collectCollapsibleSections(final Component root, final List<JPanel> found) {
        if (root instanceof JPanel panel
                && panel.getBorder() != null
                && panel.getBorder().getClass().getName().contains("CollapsibleTitledBorder")) {
            found.add(panel);
            return;
        }
        if (root instanceof Container container) {
            for (final Component child : container.getComponents()) {
                collectCollapsibleSections(child, found);
            }
        }
    }

    private static JCheckBox findGroupToggleCheckBox(final JPanel section) {
        for (final Component child : section.getComponents()) {
            if (child instanceof JCheckBox box
                    && box.getName() != null
                    && box.getName().startsWith("performance.section.")
                    && box.getName().endsWith(".toggle")) {
                return box;
            }
        }
        return null;
    }

    private static void assertGroupCheckBoxState(
            final JCheckBox box, final boolean expectedSelected, final boolean expectedIndeterminate) {
        assertEquals(expectedSelected, box.isSelected(), "group checkbox selection state");
        assertEquals(
                expectedIndeterminate ? "indeterminate" : null,
                box.getClientProperty("JButton.selectedState"),
                "group checkbox indeterminate client property");
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
            if (child instanceof JCheckBox box) {
                final String name = box.getName();
                if (name == null || !(name.startsWith("performance.section.") && name.endsWith(".toggle"))) {
                    found.add(box);
                }
            } else if (child instanceof Container nested) {
                found.addAll(checkboxes(nested));
            }
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
