package dev.turboism.adapter.cubism.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.physics.PhysicsEditorContribution;
import java.awt.BorderLayout;
import java.util.ResourceBundle;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class PhysicsGifControlsTest {
    @Test
    void previewControlsRequireContributionAndRestoreExistingHostToolbarOnWithdrawal() throws Exception {
        final PhysicsEditorCoordinator coordinator = new PhysicsEditorCoordinator();
        final Preview preview = new Preview();
        coordinator.onPreviewConstructed(preview);
        flush();
        assertNull(preview.surface.getClientProperty("turboism.physics.gif"));
        final var registration = coordinator.contribute(new PhysicsEditorContribution(true, true));
        coordinator.onPreviewConstructed(preview);
        flush();
        final Object recorder = preview.surface.getClientProperty("turboism.physics.gif");
        assertNotNull(recorder);
        assertNotSame(preview.original, preview.layout.getLayoutComponent(BorderLayout.NORTH));
        coordinator.onPreviewConstructed(preview);
        flush();
        assertSame(recorder, preview.surface.getClientProperty("turboism.physics.gif"));
        registration.close();
        flush();
        assertSame(preview.original, preview.layout.getLayoutComponent(BorderLayout.NORTH));
        assertNull(preview.surface.getClientProperty("turboism.physics.gif"));
        coordinator.close();
    }

    @Test
    void percentageAndLockedPixelsStayProportionalAndUnlockAllowsIndependentDimensions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final var dialog = new PhysicsGifExportDialog(
                    ResourceBundle.getBundle("dev.turboism.adapter.cubism.physics.messages", java.util.Locale.ENGLISH),
                    800,
                    600);
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

    @Test
    void stalePluginScopeCannotDispatchRecordingCommands() {
        final var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        final var received = new java.util.ArrayList<dev.turboism.sdk.cubism.physics.PhysicsEditorService.GifCommand>();
        final var backend = new dev.turboism.sdk.cubism.physics.PhysicsEditorService() {
            @Override
            public dev.turboism.sdk.plugin.Registration contribute(final PhysicsEditorContribution value) {
                return () -> {};
            }

            @Override
            public void gifCommand(final GifCommand command) {
                received.add(command);
            }
        };
        final var scoped = new PluginScopedPhysicsEditorService(
                backend, new dev.turboism.sdk.plugin.DisposableScope(), active::get);
        scoped.gifCommand(dev.turboism.sdk.cubism.physics.PhysicsEditorService.GifCommand.PAUSE);
        active.set(false);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> scoped.gifCommand(dev.turboism.sdk.cubism.physics.PhysicsEditorService.GifCommand.STOP));
        assertEquals(
                java.util.List.of(dev.turboism.sdk.cubism.physics.PhysicsEditorService.GifCommand.PAUSE), received);
    }

    private static JSpinner spinner(final JPanel panel, final String label) {
        for (var component : panel.getComponents()) {
            if (component instanceof JLabel field && field.getText().equals(label))
                return (JSpinner) field.getLabelFor();
        }
        throw new AssertionError("Missing field " + label);
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
    }

    static final class Preview {
        final BorderLayout layout = new BorderLayout();
        final JPanel surface = new JPanel(layout);
        final JLabel original = new JLabel("Native preview controls");

        Preview() {
            surface.add(original, BorderLayout.NORTH);
        }

        public JPanel getJComponent() {
            return surface;
        }
    }
}
