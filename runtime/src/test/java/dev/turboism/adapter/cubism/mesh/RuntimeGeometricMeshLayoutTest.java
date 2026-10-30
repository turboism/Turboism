package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.model.Point2;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class RuntimeGeometricMeshLayoutTest {
    @Test
    void generatesConcentricRingsCenterVertexAndRadialSpokesWithEqualSpacing() {
        // 1. Single triangle without center (concentricRings = 0)
        final GeometricMeshShape.LayoutPlan triangle =
                GeometricMeshShape.generate(100.0f, 200.0f, 50.0f, 3, 0, false, false);
        assertEquals(3, triangle.vertexCount());
        assertEquals(3, triangle.edgeCount());
        assertEquals(100.0f, triangle.verticesXY()[0], 0.001f);
        assertEquals(150.0f, triangle.verticesXY()[1], 0.001f);

        // 2. Single perimeter loop (concentricRings = 0) with center point and spokes to center
        final GeometricMeshShape.LayoutPlan spokedWheel =
                GeometricMeshShape.generate(50.0f, 60.0f, 40.0f, 8, 0, true, true);
        // 1 center vertex + 8 outer perimeter vertices = 9 vertices
        assertEquals(9, spokedWheel.vertexCount());
        // 8 outer perimeter edges + 8 spoke edges to center = 16 edges
        assertEquals(16, spokedWheel.edgeCount());

        // 3. Hexagon with 2 inner concentric rings (total 3 rings), center point and spokes to center
        final GeometricMeshShape.LayoutPlan concentricHex =
                GeometricMeshShape.generate(0.0f, 0.0f, 90.0f, 6, 2, true, true);
        // 1 center vertex + 3 total rings * 6 vertices = 19 vertices
        assertEquals(19, concentricHex.vertexCount());
        // 3 rings * 6 perimeter edges + 6 rays * 3 radial segments = 36 edges
        assertEquals(36, concentricHex.edgeCount());
        // Center vertex at (0, 0)
        assertEquals(0.0f, concentricHex.verticesXY()[0], 0.001f);
        assertEquals(0.0f, concentricHex.verticesXY()[1], 0.001f);
        // Ring 1 radius = 30, Ring 2 radius = 60, Ring 3 radius = 90 (equally spaced)
        final double r1 =
                Math.hypot(concentricHex.verticesXY()[2], concentricHex.verticesXY()[3]);
        final double r2 =
                Math.hypot(concentricHex.verticesXY()[14], concentricHex.verticesXY()[15]);
        final double r3 =
                Math.hypot(concentricHex.verticesXY()[26], concentricHex.verticesXY()[27]);
        assertEquals(30.0, r1, 0.001);
        assertEquals(60.0, r2, 0.001);
        assertEquals(90.0, r3, 0.001);
    }

    @Test
    void presetDropdownSlidersAndCenterCheckboxesGenerateExpectedPointsAndEdges() throws Exception {
        final List<float[]> committedVertices = new ArrayList<>();
        final List<int[]> committedEdges = new ArrayList<>();
        final List<List<Integer>> selectedVertices = new ArrayList<>();
        final AtomicBoolean deactivated = new AtomicBoolean();

        SwingUtilities.invokeAndWait(() -> {
            final JPanel canvas = new JPanel(null);
            canvas.setSize(600, 500);
            final RuntimeGeometricMeshLayout layout =
                    new RuntimeGeometricMeshLayout(new RuntimeGeometricMeshLayout.Host() {
                        @Override
                        public javax.swing.JComponent component() {
                            return canvas;
                        }

                        @Override
                        public Point2 defaultAnchor() {
                            return new Point2(300.0f, 250.0f);
                        }

                        @Override
                        public Point2 unprojectToMesh(final float componentX, final float componentY) {
                            return new Point2(componentX * 2.0f, componentY * 2.0f);
                        }

                        @Override
                        public List<Point2> projectPositions(final float[] meshPositions) {
                            final List<Point2> result = new ArrayList<>(meshPositions.length / 2);
                            for (int i = 0; i < meshPositions.length; i += 2) {
                                result.add(new Point2(meshPositions[i] * 0.5f, meshPositions[i + 1] * 0.5f));
                            }
                            return result;
                        }

                        @Override
                        public List<Integer> commitPlan(final float[] meshPositions, final int[] edgePairs) {
                            committedVertices.add(meshPositions.clone());
                            committedEdges.add(edgePairs.clone());
                            final List<Integer> ids = new ArrayList<>(meshPositions.length / 2);
                            for (int i = 0; i < meshPositions.length / 2; i++) {
                                ids.add(10 + i);
                            }
                            return ids;
                        }

                        @Override
                        public void selectVertices(final List<Integer> indices) {
                            selectedVertices.add(indices);
                        }

                        @Override
                        public boolean revalidate() {
                            return true;
                        }

                        @Override
                        public void deactivate() {
                            deactivated.set(true);
                        }
                    });
            try {
                layout.install();
                final GeometricMeshOverlayPanel overlay = (GeometricMeshOverlayPanel) layout.overlayForTests();
                final JPanel dialogPanel = layout.dialogPanelForTests();
                assertNotNull(dialogPanel);
                assertTrue(overlay.previewVisible());

                // Click on canvas at (120, 160) -> unprojects to (240, 320)
                overlay.dispatchEvent(new MouseEvent(
                        overlay,
                        MouseEvent.MOUSE_PRESSED,
                        1L,
                        InputEvent.BUTTON1_DOWN_MASK,
                        120,
                        160,
                        1,
                        false,
                        MouseEvent.BUTTON1));
                overlay.dispatchEvent(new MouseEvent(
                        overlay, MouseEvent.MOUSE_RELEASED, 2L, 0, 120, 160, 1, false, MouseEvent.BUTTON1));
                assertEquals(new Point2(240.0f, 320.0f), layout.anchorForTests());

                @SuppressWarnings("unchecked")
                final JComboBox<GeometricMeshDialog.PresetItem> presetCombo =
                        (JComboBox<GeometricMeshDialog.PresetItem>)
                                findByName(dialogPanel, "geometric-mesh-layout.preset");
                final JSpinner radiusSpinner = (JSpinner) findByName(dialogPanel, "geometric-mesh-layout.radius");
                final JSpinner sidesSpinner = (JSpinner) findByName(dialogPanel, "geometric-mesh-layout.sides");
                final JSlider ringsSlider = (JSlider) findByName(dialogPanel, "geometric-mesh-layout.rings.slider");
                final JCheckBox createCenterCheck =
                        (JCheckBox) findByName(dialogPanel, "geometric-mesh-layout.create-center");
                final JCheckBox connectCenterCheck =
                        (JCheckBox) findByName(dialogPanel, "geometric-mesh-layout.connect-center");
                final AbstractButton confirmButton =
                        (AbstractButton) findByName(dialogPanel, "geometric-mesh-layout.confirm");

                // Default concentric rings slider is 0, and connectCenterCheck is disabled until createCenterCheck is
                // checked
                assertEquals(0, ringsSlider.getMinimum());
                assertEquals(0, ringsSlider.getValue());
                assertFalse(createCenterCheck.isSelected());
                assertFalse(connectCenterCheck.isEnabled());
                assertFalse(connectCenterCheck.isSelected());

                // Check createCenterCheck -> connectCenterCheck becomes enabled
                createCenterCheck.doClick();
                assertTrue(createCenterCheck.isSelected());
                assertTrue(connectCenterCheck.isEnabled());
                connectCenterCheck.doClick();
                assertTrue(connectCenterCheck.isSelected());

                // Set concentric rings slider to 1 (1 inner ring + 1 outer ring = 2 total rings)
                presetCombo.setSelectedIndex(0); // Circle (16)
                assertEquals(16, ((Number) sidesSpinner.getValue()).intValue());
                ringsSlider.setValue(1);
                radiusSpinner.setValue(60.0);

                // 1 center + 2 total rings * 16 sides = 33 vertices; 2*16 ring edges + 2*16 spoke edges = 64 edges
                assertEquals(33, overlay.verticesForTests().size());
                assertEquals(128, overlay.edgePairsForTests().length);

                confirmButton.doClick();
                assertEquals(1, committedVertices.size());
                assertEquals(66, committedVertices.get(0).length);
                assertEquals(128, committedEdges.get(0).length);

                // Unchecking createCenterCheck automatically unchecks and disables connectCenterCheck
                createCenterCheck.doClick();
                assertFalse(createCenterCheck.isSelected());
                assertFalse(connectCenterCheck.isSelected());
                assertFalse(connectCenterCheck.isEnabled());

                // 2. Click canvas at (200, 100) -> (400, 200), select Hexagon preset (index 3 -> 6 sides), rings = 0
                overlay.dispatchEvent(new MouseEvent(
                        overlay,
                        MouseEvent.MOUSE_PRESSED,
                        3L,
                        InputEvent.BUTTON1_DOWN_MASK,
                        200,
                        100,
                        1,
                        false,
                        MouseEvent.BUTTON1));
                overlay.dispatchEvent(new MouseEvent(
                        overlay, MouseEvent.MOUSE_RELEASED, 4L, 0, 200, 100, 1, false, MouseEvent.BUTTON1));

                presetCombo.setSelectedIndex(3); // Hexagon (6)
                assertEquals(6, ((Number) sidesSpinner.getValue()).intValue());
                ringsSlider.setValue(0);
                assertEquals(6, overlay.verticesForTests().size());
                assertEquals(12, overlay.edgePairsForTests().length);

                confirmButton.doClick();
                assertEquals(2, committedVertices.size());
                assertEquals(12, committedVertices.get(1).length);
                assertEquals(12, committedEdges.get(1).length);
            } finally {
                layout.close();
            }
        });
    }

    @Test
    void localizesDialogStringsAcrossAllSupportedLocales() {
        final GeometricMeshDialog.Texts en = GeometricMeshDialog.textsForLocale(Locale.ENGLISH);
        assertEquals("Geometric Mesh Layout", en.title());
        assertEquals("Shape Preset:", en.presetLabel());
        assertEquals("Concentric Rings:", en.concentricLabel());
        assertEquals("Create Center Point", en.createCenterLabel());
        assertEquals("Connect Perimeter to Center", en.connectToCenterLabel());

        final GeometricMeshDialog.Texts zhHans = GeometricMeshDialog.textsForLocale(Locale.forLanguageTag("zh-Hans"));
        assertEquals("几何网格布点", zhHans.title());
        assertEquals("形状预设：", zhHans.presetLabel());
        assertEquals("同心周数：", zhHans.concentricLabel());
        assertEquals("创建圆心", zhHans.createCenterLabel());
        assertEquals("生成周边点到圆心的连线", zhHans.connectToCenterLabel());

        final GeometricMeshDialog.Texts zhHant = GeometricMeshDialog.textsForLocale(Locale.forLanguageTag("zh-Hant"));
        assertEquals("幾何網格布點", zhHant.title());
        assertEquals("形狀預設：", zhHant.presetLabel());
        assertEquals("建立圓心", zhHant.createCenterLabel());

        final GeometricMeshDialog.Texts ja = GeometricMeshDialog.textsForLocale(Locale.JAPANESE);
        assertEquals("幾何メッシュ配置", ja.title());
        assertEquals("同心円の数：", ja.concentricLabel());

        final GeometricMeshDialog.Texts ko = GeometricMeshDialog.textsForLocale(Locale.KOREAN);
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
}
