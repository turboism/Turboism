package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.plugin.CancellationToken;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless inline-group tests for {@link AutoMeshSelectorRow} using compiled host widget stubs.
 *
 * <p>Covers preset switching from hand-edited values, delete/restore preset transitions, decimal
 * precision on float sliders, and companion {@code auto*} enablement without needing the
 * proprietary Editor JAR.</p>
 */
class AutoMeshSelectorRowInlinePresetTest {

    private static final String ALGORITHM_ID = "plugin.region";

    @TempDir
    Path tempDir;

    @Test
    void switchingPresetFromCustomUpdatesComboAndParameterControls() throws Exception {
        try (HostHarness harness = HostHarness.compile(tempDir)) {
            final AutoMeshAlgorithmCatalog catalog = new AutoMeshAlgorithmCatalog();
            final AutoMeshSelectionState selection = new AutoMeshSelectionState(catalog);
            catalog.register(algorithm(), "plugin", 1L, () -> true);
            selection.selectAlgorithm(ALGORITHM_ID);
            final AtomicInteger placements = new AtomicInteger();
            final AutoMeshSelectorController controller = controller(catalog, selection, placements);
            final AutoMeshSelectorRow row = new AutoMeshSelectorRow(controller, Locale.ENGLISH, msg -> {});

            final Object panel = harness.buildInlinePanel();
            SwingUtilities.invokeAndWait(() -> row.attach(panel));
            SwingUtilities.invokeAndWait(() -> {});

            final List<?> groups = harness.centreChildren(panel);
            final Object presetCombo = harness.firstWidget(groups.get(2), "com.live2d.ui.control.CComboBox");
            assertNotNull(presetCombo);

            // Simulate user editing a parameter first so selection enters Custom (index 0).
            final Object settingsGroup = groups.get(4);
            final Object spacingSlider = harness.controlAtRow(settingsGroup, 0);
            final Object autoCheck = harness.controlAtRow(settingsGroup, 1);
            final Object levelSlider = harness.controlAtRow(settingsGroup, 2);

            assertFalse(harness.isEnabled(spacingSlider), "autoSpacing=true must disable spacing control initially");

            SwingUtilities.invokeAndWait(() -> harness.toggleCheckBox(autoCheck, false));
            assertTrue(harness.isEnabled(spacingSlider), "autoSpacing=false must enable spacing control");

            SwingUtilities.invokeAndWait(() -> harness.changeIntSlider(levelSlider, 3));
            assertEquals(Optional.empty(), selection.selectedPresetName(ALGORITHM_ID));
            assertEquals(0, harness.selectedIndex(presetCombo));

            // Switch to "Detailed" (index 2) — must stay on index 2 instead of snapping back to Custom.
            SwingUtilities.invokeAndWait(() -> harness.selectCombo(presetCombo, 2));
            assertEquals(Optional.of("Detailed"), selection.selectedPresetName(ALGORITHM_ID));
            assertEquals(2, harness.selectedIndex(presetCombo));

            final Object updatedSpacing = harness.controlAtRow(settingsGroup, 0);
            final Object updatedLevel = harness.controlAtRow(settingsGroup, 2);
            assertTrue(harness.isEnabled(updatedSpacing), "Detailed sets autoSpacing=false");
            assertEquals(24.0f, harness.floatValue(updatedSpacing), 0.01f);
            assertEquals(3, harness.intValue(updatedLevel));
            assertTrue(placements.get() >= 1, "preset selection must trigger placement");
        }
    }

    @Test
    void deletingAndRestoringPresetsUpdatesComboAndSelection() throws Exception {
        try (HostHarness harness = HostHarness.compile(tempDir)) {
            final AutoMeshAlgorithmCatalog catalog = new AutoMeshAlgorithmCatalog();
            final AutoMeshSelectionState selection = new AutoMeshSelectionState(catalog);
            catalog.register(algorithm(), "plugin", 1L, () -> true);
            selection.selectAlgorithm(ALGORITHM_ID);
            selection.selectPreset(algorithm(), "Detailed");
            final AtomicInteger placements = new AtomicInteger();
            final AutoMeshSelectorController controller = controller(catalog, selection, placements);
            final AutoMeshSelectorRow row = new AutoMeshSelectorRow(controller, Locale.ENGLISH, msg -> {});

            final Object panel = harness.buildInlinePanel();
            SwingUtilities.invokeAndWait(() -> row.attach(panel));
            SwingUtilities.invokeAndWait(() -> {});

            final List<?> groups = harness.centreChildren(panel);
            final Object presetGroup = groups.get(2);
            final Object presetCombo = harness.firstWidget(presetGroup, "com.live2d.ui.control.CComboBox");
            final Object deleteButton = harness.buttonAt(presetGroup, "com.live2d.ui.control.CButton", 1);
            assertNotNull(deleteButton);

            SwingUtilities.invokeAndWait(() -> harness.clickButton(deleteButton));
            assertEquals(Optional.of("Sparse"), selection.selectedPresetName(ALGORITHM_ID));
            assertEquals(1, harness.selectedIndex(presetCombo));
            assertEquals(List.of("Custom", "Sparse"), harness.comboItems(presetCombo));

            final Object menu = harness.firstWidget(presetGroup, "com.live2d.ui.menu.CButtonMenu");
            assertNotNull(menu);
            final Object restoreItem = harness.menuItemAt(menu, 1);
            SwingUtilities.invokeAndWait(() -> harness.clickButton(restoreItem));
            assertEquals(Optional.of("Sparse"), selection.selectedPresetName(ALGORITHM_ID));
            assertEquals(1, harness.selectedIndex(presetCombo));
            assertEquals(List.of("Custom", "Sparse", "Detailed"), harness.comboItems(presetCombo));
        }
    }

    private static AutoMeshSelectorController controller(
            final AutoMeshAlgorithmCatalog catalog,
            final AutoMeshSelectionState selection,
            final AtomicInteger placements) {
        return new AutoMeshSelectorController(
                new AutoMeshSelectorSource() {
                    @Override
                    public List<AutoMeshAlgorithm> algorithms() {
                        return catalog.algorithms();
                    }

                    @Override
                    public AutoMeshSelectionState selection() {
                        return selection;
                    }

                    @Override
                    public void placeCurrentSelection(final Object container, final Consumer<String> diagnostics) {
                        placements.incrementAndGet();
                    }
                },
                Locale.ENGLISH);
    }

    private static AutoMeshAlgorithm algorithm() {
        return new AutoMeshAlgorithm(
                ALGORITHM_ID,
                "Region",
                "Texture regions",
                List.of(
                        new AlgorithmParameterDescriptor.DoubleParameter(
                                "spacing", "Spacing", "", 2.0, 200.0, 0.5, 3, 40.0),
                        new AlgorithmParameterDescriptor.BoolParameter("autoSpacing", "Auto spacing", "", true),
                        new AlgorithmParameterDescriptor.IntParameter("level", "Level", "", 1, 3, 1, 2)),
                List.of(
                        new AlgorithmPreset("Sparse", Map.of("autoSpacing", "true", "level", "1")),
                        new AlgorithmPreset("Detailed", Map.of("autoSpacing", "false", "spacing", "24", "level", "3"))),
                false,
                (AutoMeshRequest request, CancellationToken cancellation) -> AutoMeshResult.Cancelled.INSTANCE);
    }

    /** Compiles minimal host widget stubs in a temp directory for reflection testing. */
    private static final class HostHarness implements AutoCloseable {

        private final URLClassLoader loader;

        private HostHarness(final URLClassLoader loader) {
            this.loader = loader;
        }

        static HostHarness compile(final Path root) throws IOException {
            final Path src = root.resolve("src");
            final Path out = root.resolve("out");
            Files.createDirectories(out);
            final List<Path> sources = writeSources(src);
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler, "JDK compiler is required for headless host stub compilation");
            final List<String> args = new ArrayList<>();
            args.add("-cp");
            args.add(System.getProperty("java.class.path"));
            args.add("-d");
            args.add(out.toString());
            for (final Path source : sources) {
                args.add(source.toString());
            }
            final int exit = compiler.run(null, null, null, args.toArray(String[]::new));
            assertEquals(0, exit, "host stub compilation failed");
            return new HostHarness(new URLClassLoader(
                    new URL[] {out.toUri().toURL()}, AutoMeshSelectorRowInlinePresetTest.class.getClassLoader()));
        }

        Object buildInlinePanel() throws Exception {
            final Class<?> paneType = type("com.live2d.ui.container.CSimplePane");
            final Class<?> vboxType = type("com.live2d.ui.container.CVBox");
            final Class<?> hboxType = type("com.live2d.ui.container.CHBox");
            final Class<?> widgetType = type("com.live2d.ui.CWidget");
            final Class<?> borderType = type("com.live2d.ui.border.CBorder");
            final Class<?> titledType = type("com.live2d.ui.border.CTitledBorder");
            final Class<?> markerType = type("kotlin.jvm.internal.DefaultConstructorMarker");
            final Object panel = paneType.getConstructor().newInstance();
            final Object centre = vboxType.getConstructor().newInstance();
            final Object nativePreset = vboxType.getConstructor().newInstance();
            final Object nativeBorder = titledType
                    .getConstructor(String.class, borderType, int.class, markerType)
                    .newInstance("Native Preset", null, 2, null);
            widgetType.getMethod("setBorder", borderType).invoke(nativePreset, nativeBorder);
            final Object row = hboxType.getConstructor().newInstance();
            final Object combo = type("com.live2d.ui.control.CComboBox")
                    .getConstructor(List.class)
                    .newInstance(List.of("Native"));
            final Object save =
                    type("com.live2d.ui.control.CDlgButton").getConstructor().newInstance();
            type("com.live2d.ui.control.CAbstractButton")
                    .getMethod("setText", String.class)
                    .invoke(save, "Save");
            final Object add =
                    type("com.live2d.ui.control.CButton").getConstructor().newInstance();
            type("com.live2d.ui.control.CAbstractButton")
                    .getMethod("setText", String.class)
                    .invoke(add, "Add");
            final Object del =
                    type("com.live2d.ui.control.CButton").getConstructor().newInstance();
            type("com.live2d.ui.control.CAbstractButton")
                    .getMethod("setText", String.class)
                    .invoke(del, "Delete");
            type("com.live2d.ui.container.CContainer")
                    .getMethod("unaryPlus", widgetType)
                    .invoke(row, combo);
            type("com.live2d.ui.container.CContainer")
                    .getMethod("unaryPlus", widgetType)
                    .invoke(row, save);
            type("com.live2d.ui.container.CContainer")
                    .getMethod("unaryPlus", widgetType)
                    .invoke(row, add);
            type("com.live2d.ui.container.CContainer")
                    .getMethod("unaryPlus", widgetType)
                    .invoke(row, del);
            type("com.live2d.ui.container.CContainer")
                    .getMethod("unaryPlus", widgetType)
                    .invoke(nativePreset, row);
            final Object nativeSettings = vboxType.getConstructor().newInstance();
            final Object settingsBorder = titledType
                    .getConstructor(String.class, borderType, int.class, markerType)
                    .newInstance("Native Settings", null, 2, null);
            widgetType.getMethod("setBorder", borderType).invoke(nativeSettings, settingsBorder);
            type("com.live2d.ui.container.CContainer")
                    .getMethod("unaryPlus", widgetType)
                    .invoke(centre, nativePreset);
            type("com.live2d.ui.container.CContainer")
                    .getMethod("unaryPlus", widgetType)
                    .invoke(centre, nativeSettings);
            paneType.getMethod("setCenter", widgetType).invoke(panel, centre);
            return panel;
        }

        List<?> centreChildren(final Object panel) throws Exception {
            final Object centre = panel.getClass().getMethod("getCenter").invoke(panel);
            return (List<?>) centre.getClass().getMethod("getChildren").invoke(centre);
        }

        Object firstWidget(final Object root, final String className) throws Exception {
            final Class<?> target = type(className);
            if (target.isInstance(root)) {
                return root;
            }
            if (type("com.live2d.ui.container.CContainer").isInstance(root)) {
                for (final Object child :
                        (List<?>) root.getClass().getMethod("getChildren").invoke(root)) {
                    final Object hit = firstWidget(child, className);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
            return null;
        }

        Object buttonAt(final Object root, final String className, final int index) throws Exception {
            final List<Object> found = new ArrayList<>();
            collect(root, type(className), found);
            return found.get(index);
        }

        private void collect(final Object root, final Class<?> target, final List<Object> out) throws Exception {
            if (target.isInstance(root)) {
                out.add(root);
            }
            if (type("com.live2d.ui.container.CContainer").isInstance(root)) {
                for (final Object child :
                        (List<?>) root.getClass().getMethod("getChildren").invoke(root)) {
                    collect(child, target, out);
                }
            }
        }

        Object menuItemAt(final Object menu, final int index) throws Exception {
            final Object popup = menu.getClass().getMethod("getPopupMenu").invoke(menu);
            final List<?> items = (List<?>) popup.getClass().getMethod("d").invoke(popup);
            return items.get(index);
        }

        Object controlAtRow(final Object settingsGroup, final int rowIndex) throws Exception {
            final List<?> rows =
                    (List<?>) settingsGroup.getClass().getMethod("getChildren").invoke(settingsGroup);
            final Object row = rows.get(rowIndex);
            final List<?> columns =
                    (List<?>) row.getClass().getMethod("getChildren").invoke(row);
            return columns.get(1);
        }

        boolean isEnabled(final Object widget) throws Exception {
            return (Boolean) widget.getClass().getMethod("isEnabled").invoke(widget);
        }

        int selectedIndex(final Object combo) throws Exception {
            return ((Number) combo.getClass().getMethod("getSelectedIndex").invoke(combo)).intValue();
        }

        List<?> comboItems(final Object combo) throws Exception {
            final Object model = combo.getClass().getMethod("getModel").invoke(combo);
            return (List<?>) model.getClass().getField("items").get(model);
        }

        float floatValue(final Object slider) throws Exception {
            return ((Number) slider.getClass().getMethod("getValue").invoke(slider)).floatValue();
        }

        int intValue(final Object slider) throws Exception {
            return ((Number) slider.getClass().getMethod("getValue").invoke(slider)).intValue();
        }

        void selectCombo(final Object combo, final int index) {
            try {
                combo.getClass().getMethod("selectByUser", int.class).invoke(combo, index);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void changeIntSlider(final Object slider, final int value) {
            try {
                slider.getClass().getMethod("changeByUser", int.class).invoke(slider, value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void toggleCheckBox(final Object checkBox, final boolean checked) {
            try {
                checkBox.getClass().getMethod("toggleByUser", boolean.class).invoke(checkBox, checked);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void clickButton(final Object button) {
            try {
                button.getClass().getMethod("clickByUser").invoke(button);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private Class<?> type(final String name) throws ClassNotFoundException {
            return Class.forName(name, false, loader);
        }

        @Override
        public void close() throws IOException {
            loader.close();
        }

        private static List<Path> writeSources(final Path src) throws IOException {
            final Map<String, String> files = Map.ofEntries(
                    Map.entry(
                            "com/live2d/type/CInsets.java",
                            "package com.live2d.type; public class CInsets { public CInsets(int t,int r,int b,int l){} }"),
                    Map.entry(
                            "com/live2d/ui/a/a.java",
                            "package com.live2d.ui.a; public class a { public static final a f = new a(); }"),
                    Map.entry(
                            "com/live2d/ui/container/a/c.java",
                            "package com.live2d.ui.container.a; public class c { public static final c a = new c(); }"),
                    Map.entry(
                            "com/live2d/ui/border/CBorder.java",
                            "package com.live2d.ui.border; public class CBorder {}"),
                    Map.entry(
                            "com/live2d/ui/border/CTitledBorder.java",
                            "package com.live2d.ui.border; import kotlin.jvm.internal.DefaultConstructorMarker;"
                                    + " public class CTitledBorder extends CBorder {"
                                    + " private final String title;"
                                    + " public CTitledBorder(String t, CBorder b, int m, DefaultConstructorMarker k) { this.title = t; }"
                                    + " public String getTitle() { return title; } }"),
                    Map.entry(
                            "com/live2d/ui/CWidget.java",
                            "package com.live2d.ui; import com.live2d.type.CInsets; import com.live2d.ui.border.CBorder;"
                                    + " public class CWidget {"
                                    + " private boolean enabled = true; private boolean visible = true; private CBorder border;"
                                    + " public void setToolTipText(String s) {}"
                                    + " public void setEnabled(boolean e) { enabled = e; }"
                                    + " public boolean isEnabled() { return enabled; }"
                                    + " public void setVisible(boolean v) { visible = v; }"
                                    + " public boolean isVisible() { return visible; }"
                                    + " public void revalidate() {} public void repaint() {}"
                                    + " public void setBorder(CBorder b) { border = b; }"
                                    + " public CBorder getBorder() { return border; }"
                                    + " public void setMarginInt(int m) {}"
                                    + " public void setMargin(CInsets i) {}"
                                    + " public void setPrefWidth(int w) {}"
                                    + " public javax.swing.JComponent getJComponent() { return null; } }"),
                    Map.entry(
                            "com/live2d/ui/container/CContainer.java",
                            "package com.live2d.ui.container; import com.live2d.ui.CWidget; import java.util.*;"
                                    + " public class CContainer extends CWidget {"
                                    + " private final List<CWidget> children = new ArrayList<>();"
                                    + " public void unaryPlus(CWidget w) { children.add(w); }"
                                    + " public void add(CWidget w, int i) { if (i < 0 || i >= children.size()) children.add(w); else children.add(i, w); }"
                                    + " public List<CWidget> getChildren() { return children; }"
                                    + " public void removeAll() { children.clear(); } }"),
                    Map.entry(
                            "com/live2d/ui/container/CSimplePane.java",
                            "package com.live2d.ui.container; import com.live2d.ui.CWidget;"
                                    + " public class CSimplePane extends CContainer {"
                                    + " private CWidget center;"
                                    + " public void setCenter(CWidget c) { center = c; }"
                                    + " public CWidget getCenter() { return center; } }"),
                    Map.entry(
                            "com/live2d/ui/container/CVBox.java",
                            "package com.live2d.ui.container; public class CVBox extends CContainer {}"),
                    Map.entry(
                            "com/live2d/ui/container/CHBox.java",
                            "package com.live2d.ui.container; import com.live2d.ui.CWidget; import com.live2d.ui.container.a.c;"
                                    + " public class CHBox extends CContainer { public void setHgrow(CWidget w, c g) {} }"),
                    Map.entry(
                            "com/live2d/ui/container/CSpacer.java",
                            "package com.live2d.ui.container; import com.live2d.ui.CWidget;"
                                    + " public class CSpacer extends CWidget { public CSpacer() {} public CSpacer(int w, int h) {} }"),
                    Map.entry(
                            "com/live2d/ui/control/CLabel.java",
                            "package com.live2d.ui.control; import com.live2d.ui.CWidget; import com.live2d.ui.a.a;"
                                    + " public class CLabel extends CWidget {"
                                    + " public CLabel(String t) {}"
                                    + " public int getEnoughWidth() { return 80; }"
                                    + " public void setHorizontalAlignment(a align) {} }"),
                    Map.entry(
                            "com/live2d/ui/control/CComboBox.java",
                            "package com.live2d.ui.control; import com.live2d.ui.CWidget; import java.util.*; import kotlin.jvm.functions.Function1;"
                                    + " public class CComboBox extends CWidget {"
                                    + " public static class a { public final List<String> items; public a(List<String> items) { this.items = new ArrayList<>(items); } }"
                                    + " private a model; private int selected; private Function1 onSelected;"
                                    + " public CComboBox(List<String> items) { this.model = new a(items); }"
                                    + " public a getModel() { return model; }"
                                    + " public void setModel(a m) { this.model = m; }"
                                    + " public void updatePopupWidth() {}"
                                    + " public void setSelectedIndex(int i) { this.selected = i; }"
                                    + " public int getSelectedIndex() { return selected; }"
                                    + " public void setOnSelected(Function1 f) { this.onSelected = f; }"
                                    + " public void selectByUser(int i) { this.selected = i; if (onSelected != null) onSelected.invoke(null); } }"),
                    Map.entry(
                            "com/live2d/ui/control/CAbstractButton.java",
                            "package com.live2d.ui.control; import com.live2d.ui.CWidget; import java.util.*; import kotlin.jvm.functions.Function1;"
                                    + " public class CAbstractButton extends CWidget {"
                                    + " private String text; private final List<Function1> actions = new ArrayList<>();"
                                    + " public void setText(String t) { text = t; }"
                                    + " public String getText() { return text; }"
                                    + " public void addOnAction(Function1 f) { actions.add(f); }"
                                    + " public void clickByUser() { for (Function1 f : new ArrayList<>(actions)) f.invoke(null); } }"),
                    Map.entry(
                            "com/live2d/ui/control/CButton.java",
                            "package com.live2d.ui.control; public class CButton extends CAbstractButton {}"),
                    Map.entry(
                            "com/live2d/ui/control/CDlgButton.java",
                            "package com.live2d.ui.control; public class CDlgButton extends CAbstractButton {}"),
                    Map.entry(
                            "com/live2d/ui/control/CCheckBox.java",
                            "package com.live2d.ui.control; public class CCheckBox extends CAbstractButton {"
                                    + " private boolean checked;"
                                    + " public void setChecked(Boolean b) { checked = Boolean.TRUE.equals(b); }"
                                    + " public boolean isChecked() { return checked; }"
                                    + " public void toggleByUser(boolean b) { checked = b; clickByUser(); } }"),
                    Map.entry(
                            "com/live2d/ui/control/CSlidableInt.java",
                            "package com.live2d.ui.control; import com.live2d.ui.CWidget; import kotlin.jvm.functions.Function1;"
                                    + " public class CSlidableInt extends CWidget {"
                                    + " private int value; private Function1 onChanged;"
                                    + " public void setMin(int v) {} public void setMax(int v) {} public void setSlideWeight(float w) {}"
                                    + " public void setValue(int v) { value = v; }"
                                    + " public int getValue() { return value; }"
                                    + " public void setOnChanged(Function1 f) { onChanged = f; }"
                                    + " public void changeByUser(int v) { value = v; if (onChanged != null) onChanged.invoke(null); } }"),
                    Map.entry(
                            "com/live2d/ui/control/CSlidableFloat.java",
                            "package com.live2d.ui.control; import com.live2d.ui.CWidget; import kotlin.jvm.functions.Function1;"
                                    + " public class CSlidableFloat extends CWidget {"
                                    + " private float value; private Function1 onChanged;"
                                    + " public void setMin(float v) {} public void setMax(float v) {} public void setKeta(int k) {} public void setSlideWeight(float w) {}"
                                    + " public void setValue(float v) { value = v; }"
                                    + " public float getValue() { return value; }"
                                    + " public void setOnChanged(Function1 f) { onChanged = f; }"
                                    + " public void changeByUser(float v) { value = v; if (onChanged != null) onChanged.invoke(null); } }"),
                    Map.entry(
                            "com/live2d/ui/control/CTextField.java",
                            "package com.live2d.ui.control; import com.live2d.ui.CWidget; import kotlin.jvm.functions.Function1;"
                                    + " public class CTextField extends CWidget {"
                                    + " private String text; private Function1 onAction;"
                                    + " public CTextField(String t) { text = t; }"
                                    + " public void setText(String t) { text = t; }"
                                    + " public String getText() { return text; }"
                                    + " public void setOnAction(Function1 f) { onAction = f; } }"),
                    Map.entry(
                            "com/live2d/ui/menu/CMenuItem.java",
                            "package com.live2d.ui.menu; import com.live2d.ui.control.CAbstractButton;"
                                    + " public class CMenuItem extends CAbstractButton {}"),
                    Map.entry(
                            "com/live2d/ui/menu/k.java",
                            "package com.live2d.ui.menu; import java.util.*;"
                                    + " public class k { public final List<CMenuItem> items = new ArrayList<>();"
                                    + " public List<CMenuItem> d() { return items; } }"),
                    Map.entry(
                            "com/live2d/ui/menu/CButtonMenu.java",
                            "package com.live2d.ui.menu; import com.live2d.ui.CWidget;"
                                    + " public class CButtonMenu extends CWidget {"
                                    + " private final k popup = new k();"
                                    + " public CButtonMenu(String t) {}"
                                    + " public void add(CMenuItem item) { popup.items.add(item); }"
                                    + " public k getPopupMenu() { return popup; } }"));
            final List<Path> paths = new ArrayList<>(files.size());
            for (final Map.Entry<String, String> entry : files.entrySet()) {
                final Path file = src.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
                paths.add(file);
            }
            return paths;
        }
    }
}
