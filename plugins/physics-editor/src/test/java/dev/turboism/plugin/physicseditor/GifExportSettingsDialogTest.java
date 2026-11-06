package dev.turboism.plugin.physicseditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * The export scale/pixel/lock model is plugin-owned policy, so it is pinned here rather than in a
 * runtime adapter test.
 */
class GifExportSettingsDialogTest {

    @Test
    void percentageAndLockedPixelsStayProportionalAndUnlockAllowsIndependentDimensions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final var dialog = new GifExportSettingsDialog(TestLocalization.ENGLISH, null, 800, 600);
            final JPanel controls = dialog.createSettingsPanel();
            final JSpinner percent = spinner(controls, "Scale (%)");
            final JSpinner width = spinner(controls, "Width (px)");
            final JSpinner height = spinner(controls, "Height (px)");
            final JSpinner limit = spinner(controls, "Target size (MB)");
            final JCheckBox lock = (JCheckBox) controls.getComponent(6);
            final JCheckBox optimize = (JCheckBox) controls.getComponent(8);
            assertTrue(lock.isSelected());
            assertTrue(optimize.isSelected());
            assertEquals(20.0, limit.getValue());
            percent.setValue(50.0);
            assertEquals(400, width.getValue());
            assertEquals(300, height.getValue());
            width.setValue(640);
            assertEquals(480, height.getValue());
            assertEquals(80.0, percent.getValue());
            height.setValue(150);
            assertEquals(200, width.getValue());
            lock.doClick();
            width.setValue(360);
            assertEquals(150, height.getValue());
            assertFalse(percent.isEnabled());
            try {
                dialog.commitSettingsEdits();
            } catch (java.text.ParseException failure) {
                throw new AssertionError(failure);
            }
            assertEquals(360, width.getValue(), "confirming export must retain unlocked pixel dimensions");
            assertEquals(150, height.getValue());
            optimize.doClick();
            assertFalse(limit.isEnabled());
        });
    }

    private static JSpinner spinner(final JPanel panel, final String label) {
        for (var component : panel.getComponents()) {
            if (component instanceof JLabel field && field.getText().equals(label))
                return (JSpinner) field.getLabelFor();
        }
        throw new AssertionError("Missing field " + label);
    }
}
