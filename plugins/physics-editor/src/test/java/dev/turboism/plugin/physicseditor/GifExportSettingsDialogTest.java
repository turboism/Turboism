package dev.turboism.plugin.physicseditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
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

    /**
     * The native rejection: the operator unchecked Optimize, cleared the Gifsicle path and pressed
     * OK, and the dialog still refused the export. An unoptimized export never spawns Gifsicle, so
     * it must accept any path; an optimized export must still refuse a blank one.
     */
    @Test
    void unoptimizedExportAcceptsABlankExecutableWhileOptimizedExportStillRequiresOne() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final var dialog = new GifExportSettingsDialog(TestLocalization.ENGLISH, null, 800, 600);
            final JPanel controls = dialog.createSettingsPanel();
            final JCheckBox optimize = checkBox(controls, TestLocalization.ENGLISH.raw("gif.optimize"));
            final JTextField executable = field(controls, TestLocalization.ENGLISH.raw("gif.executable"));

            optimize.doClick();
            assertFalse(optimize.isSelected(), "the operator turned optimization off");
            executable.setText("   ");
            try {
                dialog.validateForExport();
            } catch (java.text.ParseException failure) {
                throw new AssertionError(failure);
            }
            final var unoptimized = dialog.exportSettings(Path.of("recording.gif"));
            assertEquals(Long.MAX_VALUE, unoptimized.maxBytes(), "an unoptimized export has no size ceiling");
            assertEquals("", unoptimized.executable(), "a cleared path is carried as empty and never spawned");

            optimize.doClick();
            assertTrue(optimize.isSelected(), "optimization is back on");
            assertThrows(
                    IllegalArgumentException.class,
                    dialog::validateForExport,
                    "an optimized export still needs an executable to spawn");
            executable.setText("D:/tools/gifsicle.exe");
            try {
                dialog.validateForExport();
            } catch (java.text.ParseException failure) {
                throw new AssertionError(failure);
            }
            assertEquals(
                    20_000_000L, dialog.exportSettings(Path.of("recording.gif")).maxBytes());
        });
    }

    /**
     * The native chooser failed before it displayed: {@code UIDefaults.getUI} resolves the installed
     * FileChooserUI through the chooser class loader, and a plugin-defined subclass carries the
     * isolated plugin loader, which cannot see the host look-and-feel class. The save chooser must
     * therefore stay the bootstrap {@link JFileChooser} class, whose null loader makes the lookup use
     * the host system loader.
     */
    @Test
    void saveChooserStaysTheStandardBootstrapClassSoTheHostLookAndFeelResolves() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JFileChooser chooser = GifExportSettingsDialog.createChooser();
            assertSame(
                    JFileChooser.class, chooser.getClass(), "a plugin subclass would select the plugin class loader");
            assertNull(chooser.getClass().getClassLoader(), "the bootstrap loader keeps the host UI lookup working");
            assertNotNull(chooser.getUI(), "the installed look and feel must provide a chooser UI");
        });
    }

    private static JCheckBox checkBox(final JPanel panel, final String text) {
        for (var component : panel.getComponents()) {
            if (component instanceof JCheckBox box && box.getText().equals(text)) return box;
        }
        throw new AssertionError("Missing checkbox " + text);
    }

    /**
     * The native dialog left its prompt behind: a plugins-owned modal message stayed over the Editor
     * after the plugin closed. A closing export flow must dismiss a pending prompt and release the
     * event thread, because the recorder only polls its cancellation flag from a waiting thread.
     */
    @Test
    void closingTheExportFlowDismissesAPendingPrompt() throws Exception {
        final var dialog = new GifExportSettingsDialog(TestLocalization.ENGLISH, null, 800, 600);
        final var answer = new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
        SwingUtilities.invokeLater(() -> answer.set(dialog.showPrompt(
                null, "message", javax.swing.JOptionPane.ERROR_MESSAGE, javax.swing.JOptionPane.DEFAULT_OPTION)));
        final javax.swing.JDialog prompt = awaitPromptWindow(dialog);

        dialog.close();

        awaitCondition(() -> !prompt.isDisplayable(), "the pending prompt is dismissed");
        awaitCondition(() -> answer.get() != Integer.MIN_VALUE, "the blocked prompt returns");
        assertEquals(javax.swing.JOptionPane.CLOSED_OPTION, answer.get(), "a dismissed prompt reports no choice");
    }

    private static javax.swing.JDialog awaitPromptWindow(final GifExportSettingsDialog dialog)
            throws InterruptedException {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            final javax.swing.JDialog window = dialog.activePromptWindow();
            if (window != null && window.isShowing()) return window;
            Thread.sleep(10);
        }
        throw new AssertionError("the prompt never showed");
    }

    private static void awaitCondition(final java.util.function.BooleanSupplier condition, final String message)
            throws InterruptedException {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        throw new AssertionError(message);
    }

    private static JTextField field(final JPanel panel, final String label) {
        for (var component : panel.getComponents()) {
            if (component instanceof JLabel caption && caption.getText().equals(label))
                return (JTextField) caption.getLabelFor();
        }
        throw new AssertionError("Missing field " + label);
    }

    private static JSpinner spinner(final JPanel panel, final String label) {
        for (var component : panel.getComponents()) {
            if (component instanceof JLabel field && field.getText().equals(label))
                return (JSpinner) field.getLabelFor();
        }
        throw new AssertionError("Missing field " + label);
    }
}
