package dev.turboism.shell;

import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.internal.core.CubismJvmSettingsService;
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
import javax.swing.JCheckBox;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.FocusEvent;
import java.awt.event.MouseWheelEvent;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        final JPanel custom = formInTab(rendered.tabs(), 1);
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

    @Test
    void noteRendersAsDisabledSmallCaptionWithNothingToSave() throws Exception {
        final CoreWindows windows = new CoreWindows(
            localization(), settings(), List::of, plugins(), RuntimeLogReader.unavailable()
        );
        final JPanel panel = new JPanel(new GridBagLayout());
        final SettingsControl.Note control = new SettingsControl.Note(
            "performance-restart-note",
            "All performance adjustments take effect after restarting the editor"
        );
        final Method render = CoreWindows.class.getDeclaredMethod(
            "renderControl", JDialog.class, JPanel.class, int.class, SettingsControl.class
        );
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
        assertTrue(caption.getFont().getSize2D() < new javax.swing.JLabel().getFont().getSize2D());
        assertTrue(saver.getAsBoolean());
    }

    @Test
    void tallSettingsRemainReachableByWheelAndScrollBarAtLargeFonts() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (float size : new float[]{12f, 18f, 24f}) {
                try (CoreWindows windows = new CoreWindows(localization(), settings(),
                        () -> List.of(manySettings()), plugins(), RuntimeLogReader.unavailable())) {
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
                    scroll.dispatchEvent(new MouseWheelEvent(scroll, MouseWheelEvent.MOUSE_WHEEL,
                        System.currentTimeMillis(), 0, 20, 20, 0, false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1));
                    assertTrue(scroll.getVerticalScrollBar().getValue() > before, "wheel must move content");
                    scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                    JCheckBox last = checkboxes(scroll).get(29);
                    Rectangle bounds = SwingUtilities.convertRectangle(last.getParent(), last.getBounds(),
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
            try (CoreWindows windows = new CoreWindows(localization(), settings(),
                    () -> List.of(manySettings()), plugins(), RuntimeLogReader.unavailable())) {
                CoreWindows.RenderedSettings rendered = render(windows);
                rendered.tabs().setSize(280, 240);
                JScrollPane scroll = assertInstanceOf(JScrollPane.class, rendered.tabs().getComponentAt(0));
                layoutTree(rendered.tabs());
                assertTrue(scroll.getHorizontalScrollBar().isVisible(), "long labels must not be silently clipped");
                JCheckBox last = checkboxes(scroll).get(29);
                for (var listener : last.getFocusListeners()) {
                    listener.focusGained(new FocusEvent(last, FocusEvent.FOCUS_GAINED));
                }
                Rectangle bounds = SwingUtilities.convertRectangle(last.getParent(), last.getBounds(),
                    scroll.getViewport().getView());
                assertTrue(scroll.getViewport().getViewRect().intersects(bounds), "keyboard focus must reveal row");
                Scrollable view = assertInstanceOf(Scrollable.class, scroll.getViewport().getView());
                assertTrue(view.getScrollableUnitIncrement(new Rectangle(0, 0, 100, 100), 1, 1) > 0);
                assertTrue(view.getScrollableBlockIncrement(new Rectangle(0, 0, 1, 1), 1, 1) > 0);
            }
        });
    }

    @Test
    void renderedOptimizationDefaultsAndExplicitOptOutSurviveScrollingAndSave() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean uniform = new java.util.concurrent.atomic.AtomicBoolean(true);
        CubismJvmSettingsService service = new CubismJvmSettingsService() {
            @Override public CubismJvm read() { return CubismJvm.BUNDLED; }
            @Override public CubismJvm save(CubismJvm value) { return value; }
            @Override public boolean uniformLocationCache() { return uniform.get(); }
            @Override public boolean saveUniformLocationCache(boolean value) { uniform.set(value); return value; }
            @Override public boolean saveIncrementalUpdate(boolean value) { assertFalse(value); return false; }
        };
        String key = "turboism.optimization.uniformLocationCache";
        String previous = System.getProperty(key);
        String incrementalKey = "turboism.optimization.incrementalUpdate";
        String previousIncremental = System.getProperty(incrementalKey);
        try {
            SwingUtilities.invokeAndWait(() -> {
                List<SettingsSnapshot.Entry> entries = List.of(
                    new SettingsSnapshot.Entry("turboism.core", CubismJvmSettingsContribution.createUniformLocationCacheToggle(localization(), service)),
                    new SettingsSnapshot.Entry("turboism.core", CubismJvmSettingsContribution.createIncrementalUpdateToggle(localization(), service)));
                try (CoreWindows windows = new CoreWindows(localization(), settings(),
                        () -> List.of(new SettingsSnapshot.Tab("performance", "Performance", OptionalInt.of(200), entries)),
                        plugins(), RuntimeLogReader.unavailable())) {
                    CoreWindows.RenderedSettings rendered = render(windows);
                    JScrollPane scroll = assertInstanceOf(JScrollPane.class, rendered.tabs().getComponentAt(0));
                    List<JCheckBox> boxes = checkboxes(scroll);
                    assertTrue(boxes.get(0).isSelected(), "proven uniform cache is on by default");
                    assertFalse(boxes.get(1).isSelected(), "Slice B stays explicitly opt-in");
                    boxes.get(0).doClick();
                    assertTrue(uniform.get(), "click alone must not persist before Apply");
                    assertTrue(rendered.save().getAsBoolean());
                    assertFalse(uniform.get());
                    assertEquals("false", System.getProperty(key));
                    assertFalse(checkboxes(render(windows).tabs()).get(0).isSelected(), "reopen preserves opt-out");
                }
            });
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
            if (previousIncremental == null) System.clearProperty(incrementalKey); else System.setProperty(incrementalKey, previousIncremental);
        }
    }

    @Test
    void displaySessionKeepsActionsVisibleAndRevealsRealKeyboardFocus() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("TURBOISM_TEST_SETTINGS_DISPLAY")),
            "opt-in virtual-display test; not Cubism host evidence");
        assertFalse(java.awt.GraphicsEnvironment.isHeadless());
        final CoreWindows windows = new CoreWindows(localization(), settings(),
            () -> List.of(manySettings()), plugins(), RuntimeLogReader.unavailable());
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
                    JTabbedPane tabs = (JTabbedPane) ((java.awt.BorderLayout) dialog[0].getContentPane().getLayout())
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
                    JPanel actions = (JPanel) ((java.awt.BorderLayout) dialog[0].getContentPane().getLayout())
                        .getLayoutComponent(java.awt.BorderLayout.SOUTH);
                    assertEquals(3, actions.getComponentCount());
                    assertFalse(SwingUtilities.isDescendingFrom(actions, page[0]));
                    assertTrue(new Rectangle(dialog[0].getContentPane().getSize()).contains(actions.getBounds()));
                    last[0] = checkboxes(page[0]).get(29);
                    dialog[0].toFront();
                    dialog[0].requestFocus();
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            final long windowDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            final boolean[] windowFocused = {false};
            while (!windowFocused[0] && System.nanoTime() < windowDeadline) {
                SwingUtilities.invokeAndWait(() -> windowFocused[0] = dialog[0].isFocused());
                if (!windowFocused[0]) Thread.sleep(20L);
            }
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(dialog[0].isFocused(), "window activation must finish before testing control focus");
                assertTrue(last[0].requestFocusInWindow());
            });
            final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            final boolean[] focused = {false};
            while (!focused[0] && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> focused[0] = last[0].isFocusOwner());
                if (!focused[0]) Thread.sleep(20L);
            }
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(last[0].isFocusOwner(), "offscreen setting receives real focus");
                Rectangle bounds = SwingUtilities.convertRectangle(last[0].getParent(), last[0].getBounds(), page[0].getViewport().getView());
                assertTrue(page[0].getViewport().getViewRect().intersects(bounds));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (dialog[0] != null) dialog[0].dispose(); windows.close(); });
        }
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
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static SettingsSnapshot.Tab manySettings() {
        return new SettingsSnapshot.Tab("performance", "Performance", OptionalInt.of(200),
            java.util.stream.IntStream.range(0, 30).mapToObj(index ->
                new SettingsSnapshot.Entry("plugin.test", new SettingsContribution("setting-" + index,
                    new SettingsTab("performance", "Performance", OptionalInt.of(200)), OptionalInt.of(index),
                    new SettingsControl.Toggle("setting-" + index,
                        "Setting " + index + " with a long localized performance option label",
                        SettingsBinding.of(() -> true, ignored -> { })))))
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
