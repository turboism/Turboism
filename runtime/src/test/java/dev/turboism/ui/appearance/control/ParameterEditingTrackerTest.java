package dev.turboism.ui.appearance.control;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ParameterEditingTrackerTest {
    @Test
    void disablesEditorsAndInterceptsPointerButKeepsTheLockClickableAndRestores() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tracker = new ParameterEditingTracker();
            var row = new JPanel(null);
            row.setSize(180, 24);
            var label = new JLabel("lock");
            label.setBounds(0, 0, 30, 24);
            var drag = drag();
            var field = new JTextField("0");
            field.setBounds(130, 0, 50, 24);
            row.add(label);
            row.add(drag);
            row.add(field);
            var controls = new ParameterEditorControls(row, drag, field);
            tracker.apply(label, controls, true);
            assertFalse(field.isEnabled());
            assertFalse(drag.isEnabled());
            assertTrue(label.isEnabled());
            assertSame(label, SwingUtilities.getDeepestComponentAt(row, 5, 10));
            Component shield = SwingUtilities.getDeepestComponentAt(row, 60, 10);
            assertNotSame(drag, shield);
            assertTrue(shield.contains(60, 10));
            var image = new BufferedImage(180, 24, BufferedImage.TYPE_INT_RGB);
            Graphics graphics = image.getGraphics();
            row.paint(graphics);
            graphics.dispose();
            var color = new Color(image.getRGB(60, 10));
            assertEquals(color.getRed(), color.getGreen());
            assertEquals(color.getGreen(), color.getBlue());
            tracker.apply(label, controls, false);
            assertTrue(field.isEnabled());
            assertTrue(drag.isEnabled());
            assertSame(drag, SwingUtilities.getDeepestComponentAt(row, 60, 10));
            assertEquals(3, row.getComponentCount());
            assertNull(field.getClientProperty(ParameterEditingTracker.MARKER));
        });
    }

    @Test
    void dualAxisLocksShareDragButKeepOtherValueEditableAndPreserveNativeDisabledState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tracker = new ParameterEditingTracker();
            var row = new JPanel(null);
            row.setSize(240, 24);
            var drag = drag();
            var x = new JTextField();
            var y = new JTextField();
            x.setBounds(130, 0, 40, 24);
            y.setBounds(180, 0, 40, 24);
            row.add(drag);
            row.add(x);
            row.add(y);
            var ownerX = new JLabel();
            var ownerY = new JLabel();
            var first = new ParameterEditorControls(row, drag, x);
            var second = new ParameterEditorControls(row, drag, y);
            tracker.apply(ownerX, first, true);
            assertFalse(x.isEnabled());
            assertTrue(y.isEnabled());
            tracker.apply(ownerY, second, true);
            tracker.apply(ownerX, first, false);
            assertTrue(x.isEnabled());
            assertFalse(y.isEnabled());
            assertFalse(drag.isEnabled());
            tracker.restoreAll();
            assertTrue(y.isEnabled());
            assertTrue(drag.isEnabled());
            x.setEnabled(false);
            tracker.apply(ownerX, first, true);
            tracker.apply(ownerX, first, true);
            tracker.restoreAll();
            tracker.restoreAll();
            assertFalse(x.isEnabled());
            assertEquals(3, row.getComponentCount());
        });
    }

    private static JComponent drag() {
        var drag = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Color.RED);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        drag.setBounds(30, 0, 100, 24);
        return drag;
    }
}
