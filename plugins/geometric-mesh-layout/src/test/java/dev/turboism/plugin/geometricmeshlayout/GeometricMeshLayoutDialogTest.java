package dev.turboism.plugin.geometricmeshlayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.model.Point2;
import java.awt.Component;
import java.awt.Container;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class GeometricMeshLayoutDialogTest {
    @Test
    void dialogControlsProduceTheExpectedConfiguration() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingListener listener = new RecordingListener();
            final GeometricMeshLayoutDialog dialog = new GeometricMeshLayoutDialog(
                    GeometricMeshConfig.defaults(), new Point2(300.0f, 250.0f), listener, PluginCatalogs.english());
            try {
                final JPanel panel = dialog.panel();
                assertNotNull(findByName(panel, "geometric-mesh-layout.anchor"));
                final JComboBox<?> presetCombo = (JComboBox<?>) findByName(panel, "geometric-mesh-layout.preset");
                final JSpinner radiusSpinner = (JSpinner) findByName(panel, "geometric-mesh-layout.radius");
                final JSpinner sidesSpinner = (JSpinner) findByName(panel, "geometric-mesh-layout.sides");
                final JSlider ringsSlider = (JSlider) findByName(panel, "geometric-mesh-layout.rings.slider");
                final JCheckBox createCenterCheck =
                        (JCheckBox) findByName(panel, "geometric-mesh-layout.create-center");
                final JCheckBox connectCenterCheck =
                        (JCheckBox) findByName(panel, "geometric-mesh-layout.connect-center");
                assertNotNull(findByName(panel, "geometric-mesh-layout.confirm"));
                assertNotNull(findByName(panel, "geometric-mesh-layout.cancel"));

                // Default state: one perimeter loop, no center vertex, spokes disabled.
                assertEquals(0, ringsSlider.getMinimum());
                assertEquals(0, ringsSlider.getValue());
                assertFalse(createCenterCheck.isSelected());
                assertFalse(connectCenterCheck.isEnabled());
                assertFalse(connectCenterCheck.isSelected());

                // The center checkbox owns the spoke checkbox.
                createCenterCheck.doClick();
                assertTrue(createCenterCheck.isSelected());
                assertTrue(connectCenterCheck.isEnabled());
                connectCenterCheck.doClick();
                assertTrue(connectCenterCheck.isSelected());

                // The circle preset drives the side count; one inner ring makes two loops.
                presetCombo.setSelectedIndex(0);
                assertEquals(16, ((Number) sidesSpinner.getValue()).intValue());
                ringsSlider.setValue(1);
                radiusSpinner.setValue(60.0);

                final GeometricMeshConfig configured = dialog.currentConfig();
                assertEquals(GeometricMeshShape.Preset.CIRCLE, configured.preset());
                assertEquals(16, configured.sides());
                assertEquals(1, configured.concentricRings());
                assertEquals(60.0f, configured.radius(), 0.001f);
                assertTrue(configured.createCenter());
                assertTrue(configured.connectToCenter());
                assertEquals(configured, listener.lastChanged);

                // 1 center + 2 loops * 16 sides = 33 vertices; 32 loop edges + 32 spokes.
                final MeshPointLayout layout = configured.toLayoutPlan(new Point2(0.0f, 0.0f));
                assertEquals(33, layout.vertexCount());
                assertEquals(64, layout.edgeCount());

                // Unchecking the center checkbox clears and disables the spokes.
                createCenterCheck.doClick();
                assertFalse(createCenterCheck.isSelected());
                assertFalse(connectCenterCheck.isSelected());
                assertFalse(connectCenterCheck.isEnabled());

                // The hexagon preset narrows the layout to six sides.
                presetCombo.setSelectedIndex(3);
                assertEquals(6, ((Number) sidesSpinner.getValue()).intValue());
                ringsSlider.setValue(0);
                assertEquals(
                        6,
                        dialog.currentConfig()
                                .toLayoutPlan(new Point2(0.0f, 0.0f))
                                .vertexCount());
            } finally {
                dialog.dispose();
            }
        });
    }

    @Test
    void confirmAndCancelReportThroughTheListener() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingListener listener = new RecordingListener();
            final GeometricMeshLayoutDialog dialog = new GeometricMeshLayoutDialog(
                    GeometricMeshConfig.defaults(), new Point2(0.0f, 0.0f), listener, PluginCatalogs.english());
            try {
                final JPanel panel = dialog.panel();
                ((AbstractButton) findByName(panel, "geometric-mesh-layout.confirm")).doClick();
                assertNotNull(listener.confirmed);
                assertFalse(listener.cancelled);

                ((AbstractButton) findByName(panel, "geometric-mesh-layout.cancel")).doClick();
                assertTrue(listener.cancelled);
            } finally {
                dialog.dispose();
            }
        });
    }

    @Test
    void anchorLabelTracksTheRuntimeAnchor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final GeometricMeshLayoutDialog dialog = new GeometricMeshLayoutDialog(
                    GeometricMeshConfig.defaults(),
                    new Point2(1.5f, -2.5f),
                    new RecordingListener(),
                    PluginCatalogs.english());
            try {
                final Component label = findByName(dialog.panel(), "geometric-mesh-layout.anchor");
                assertTrue(((javax.swing.JLabel) label).getText().contains("(1.5, -2.5)"));
                dialog.updateAnchor(new Point2(10.0f, 20.0f));
                assertTrue(((javax.swing.JLabel) label).getText().contains("(10.0, 20.0)"));
            } finally {
                dialog.dispose();
            }
        });
    }

    @Test
    void localizesEveryDialogStringFromTheShippedCatalogs() {
        final GeometricMeshLayoutDialog.Texts en =
                GeometricMeshLayoutDialog.Texts.of(PluginCatalogs.locale("messages_en.properties"));
        assertEquals("Geometric Mesh Layout", en.title());
        assertEquals("Shape Preset:", en.presetLabel());
        assertEquals("Concentric Rings:", en.concentricLabel());
        assertEquals("Create Center Point", en.createCenterLabel());
        assertEquals("Connect Perimeter to Center", en.connectToCenterLabel());

        final GeometricMeshLayoutDialog.Texts zhHans =
                GeometricMeshLayoutDialog.Texts.of(PluginCatalogs.locale("messages_zh_Hans.properties"));
        assertEquals("几何网格布点", zhHans.title());
        assertEquals("形状预设：", zhHans.presetLabel());
        assertEquals("同心周数：", zhHans.concentricLabel());
        assertEquals("创建圆心", zhHans.createCenterLabel());
        assertEquals("生成周边点到圆心的连线", zhHans.connectToCenterLabel());

        final GeometricMeshLayoutDialog.Texts zhHant =
                GeometricMeshLayoutDialog.Texts.of(PluginCatalogs.locale("messages_zh_Hant.properties"));
        assertEquals("幾何網格布點", zhHant.title());
        assertEquals("形狀預設：", zhHant.presetLabel());
        assertEquals("建立圓心", zhHant.createCenterLabel());

        final GeometricMeshLayoutDialog.Texts ja =
                GeometricMeshLayoutDialog.Texts.of(PluginCatalogs.locale("messages_ja.properties"));
        assertEquals("幾何メッシュ配置", ja.title());
        assertEquals("同心円の数：", ja.concentricLabel());

        final GeometricMeshLayoutDialog.Texts ko =
                GeometricMeshLayoutDialog.Texts.of(PluginCatalogs.locale("messages_ko.properties"));
        assertEquals("기하학 메시 배치", ko.title());
        assertEquals("동심원 수:", ko.concentricLabel());
    }

    private static Component findByName(final Container root, final String name) {
        if (name.equals(root.getName())) return root;
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                final Component found = findByName(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class RecordingListener implements GeometricMeshLayoutDialog.Listener {
        GeometricMeshConfig lastChanged;
        GeometricMeshConfig confirmed;
        boolean cancelled;

        @Override
        public void onConfigChanged(final GeometricMeshConfig config) {
            lastChanged = config;
        }

        @Override
        public void onConfirm(final GeometricMeshConfig config) {
            confirmed = config;
        }

        @Override
        public void onCancel() {
            cancelled = true;
        }
    }
}
