package dev.turboism.plugin.clipmaskviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import dev.turboism.plugin.clipmaskviewer.domain.ClipMaskViewerState;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.AbstractButton;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ClipMaskViewerWindowTest {
    @Test
    void filterRemainsVisibleAndFiltersBothTablesAcrossModeChangesAndRefresh() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            PluginLocalization texts = new PluginLocalization() {
                public Locale locale() {
                    return Locale.ENGLISH;
                }

                public String text(String key) {
                    return key;
                }

                public String format(String key, Object... args) {
                    return key;
                }

                public boolean contains(String key) {
                    return true;
                }
            };
            PluginLogger logger = (PluginLogger) Proxy.newProxyInstance(
                    PluginLogger.class.getClassLoader(), new Class<?>[] {PluginLogger.class}, (p, m, a) -> null);
            PluginContext context = (PluginContext) Proxy.newProxyInstance(
                    PluginContext.class.getClassLoader(),
                    new Class<?>[] {PluginContext.class},
                    (p, m, a) -> m.getName().equals("logger") ? logger : null);
            ClipMaskViewerWindow window = new ClipMaskViewerWindow(texts, context, () -> {}, () -> {});
            try {
                var snapshot = ClipMaskViewerState.analyze(List.of(
                        record("m1", "Mask One"), record("m2", "Mask Two"),
                        record("u1", "Alpha", "m1"), record("u2", "Beta [x]", "m2")));
                window.showSnapshot(snapshot);
                window.setVisible(true);
                JTextField filter = components(window, JTextField.class).get(0);
                List<JTable> tables = components(window, JTable.class);
                filter.setText("beta [x]");
                assertTrue(tables.stream().allMatch(table -> table.getRowCount() == 1));
                for (String mode : List.of("mode.table.mask", "mode.table.user")) {
                    button(window, mode).doClick();
                    assertTrue(filter.isShowing());
                    assertFalse(components(window, JSlider.class).get(0).isShowing());
                    window.showSnapshot(snapshot);
                    assertTrue(tables.stream().allMatch(table -> table.getRowCount() == 1));
                }
                filter.setText("no match");
                assertTrue(tables.stream().allMatch(table -> table.getRowCount() == 0));
                filter.setText("");
                assertTrue(tables.stream().allMatch(table -> table.getRowCount() == 2));
                button(window, "mode.graph").doClick();
                assertTrue(filter.isShowing());
                assertTrue(components(window, JSlider.class).get(0).isShowing());
                assertEquals(
                        0,
                        components(window, AbstractButton.class).stream()
                                .filter(button -> "button.close".equals(button.getText()))
                                .count());
            } finally {
                window.dispose();
            }
        });
    }

    private static ClipMaskRecord record(String guid, String name, String... masks) {
        return new ClipMaskRecord(guid, guid, name, false, List.of(masks));
    }

    private static AbstractButton button(Container root, String text) {
        return components(root, AbstractButton.class).stream()
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) result.add(type.cast(component));
            if (component instanceof Container container) result.addAll(components(container, type));
        }
        return result;
    }
}
