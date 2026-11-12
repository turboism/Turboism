package dev.turboism.sdk.ui.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class TurboismProgressDialogTest {
    private static final int EDITOR_WIDTH = 800;
    private static final int EDITOR_HEIGHT = 600;
    private static final int EDITOR_INSET_X = 90;
    private static final int EDITOR_INSET_Y = 80;
    private static final int CENTER_TOLERANCE_PIXELS = 1;
    private static final long PLACEMENT_SETTLE_TIMEOUT_MILLIS = 2_000L;
    private static final long PLACEMENT_POLL_MILLIS = 10L;

    @Test
    void rejectsInvalidProgress() {
        try (var progress = new TurboismProgressDialog("Progress", "Preparing")) {
            assertThrows(IllegalArgumentException.class, () -> progress.setProgress(0, 0));
            assertThrows(IllegalArgumentException.class, () -> progress.setProgress(-1, 10));
            assertThrows(IllegalArgumentException.class, () -> progress.setProgress(11, 10));
        }
    }

    @Test
    void flushIsSafeBeforeShowAndAfterClose() {
        var progress = new TurboismProgressDialog("Shared progress flush test", "Preparing");
        progress.flush();
        progress.close();
        progress.flush();
    }

    @Test
    void flushPaintsARealizedWindowWithoutQueuingWork() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var progress = new TurboismProgressDialog("Shared progress flush test", "Preparing");
        try {
            progress.show();
            // The caller keeps the event thread busy here, exactly like a synchronous authoring
            // transaction. flush must paint inside this call instead of waiting for the loop.
            SwingUtilities.invokeAndWait(() -> {
                JDialog dialog = visibleProgress("Shared progress flush test");
                progress.setMessage("Applying");
                progress.setProgress(2, 4);
                progress.flush();
                assertEquals(2, bar(dialog).getValue());
                assertEquals(4, bar(dialog).getMaximum());
                assertFalse(bar(dialog).isIndeterminate());
                assertEquals("Applying", ((JLabel) dialog.getContentPane().getComponent(0)).getText());
            });
        } finally {
            progress.close();
        }
    }

    @Test
    void centersOnEditorAndSupportsWorkerUpdatesAndTerminalClose() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        final Rectangle display = displayBounds();
        final Rectangle editorBounds = editorBounds(display);
        JFrame[] editor = new JFrame[1];
        var progress = new TurboismProgressDialog("Shared progress test", "Preparing");
        try {
            SwingUtilities.invokeAndWait(() -> {
                editor[0] = new JFrame("Live2D Cubism Editor - progress test");
                editor[0].setBounds(editorBounds);
                editor[0].setVisible(true);
            });
            progress.show();
            final Rectangle dialogBounds = progressDialogBounds();
            assumeTrue(
                    display.contains(editorBounds) && display.contains(dialogBounds),
                    "exact centering requires both windows inside the display " + display);
            awaitCenteredOnEditor(editor[0]);
            SwingUtilities.invokeAndWait(() -> {
                JDialog dialog = visibleProgress();
                assertSame(editor[0], dialog.getOwner());
                assertEquals(centerX(editor[0]), centerX(dialog), CENTER_TOLERANCE_PIXELS);
                assertEquals(centerY(editor[0]), centerY(dialog), CENTER_TOLERANCE_PIXELS);
                assertTrue(bar(dialog).isIndeterminate());
            });
            progress.setMessage("Applying");
            progress.setProgress(3, 5);
            progress.show();
            progress.focus();
            SwingUtilities.invokeAndWait(() -> {
                JDialog dialog = visibleProgress();
                assertEquals(3, bar(dialog).getValue());
                assertEquals(5, bar(dialog).getMaximum());
                assertFalse(bar(dialog).isIndeterminate());
                JLabel label = (JLabel) dialog.getContentPane().getComponent(0);
                assertEquals("Applying", label.getText());
                assertEquals(
                        1,
                        Arrays.stream(Window.getWindows())
                                .filter(w -> w instanceof JDialog d
                                        && d.isShowing()
                                        && d.getTitle().equals("Shared progress test"))
                                .count());
            });
            progress.close();
            progress.show();
            SwingUtilities.invokeAndWait(() -> assertTrue(Arrays.stream(Window.getWindows())
                    .noneMatch(w -> w instanceof JDialog d
                            && d.isDisplayable()
                            && d.getTitle().equals("Shared progress test"))));
        } finally {
            progress.close();
            SwingUtilities.invokeAndWait(() -> {
                if (editor[0] != null) editor[0].dispose();
            });
        }
    }

    private static Rectangle displayBounds() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    }

    /**
     * Fits the simulated editor inside the display. AWT clamps a centered top-level
     * location to the display, so an oversized editor hides the real centering result.
     */
    private static Rectangle editorBounds(final Rectangle display) {
        return new Rectangle(
                display.x + EDITOR_INSET_X,
                display.y + EDITOR_INSET_Y,
                Math.min(EDITOR_WIDTH, Math.max(0, display.width - 2 * EDITOR_INSET_X)),
                Math.min(EDITOR_HEIGHT, Math.max(0, display.height - 2 * EDITOR_INSET_Y)));
    }

    private static Rectangle progressDialogBounds() throws Exception {
        final Rectangle[] bounds = new Rectangle[1];
        SwingUtilities.invokeAndWait(() -> bounds[0] = visibleProgress().getBounds());
        return bounds[0];
    }

    /** Waits for AWT to apply the requested location, which happens after the window maps. */
    private static void awaitCenteredOnEditor(final JFrame editor) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PLACEMENT_SETTLE_TIMEOUT_MILLIS);
        while (!isCenteredOn(editor)) {
            if (System.nanoTime() >= deadline) return;
            Thread.sleep(PLACEMENT_POLL_MILLIS);
        }
    }

    private static boolean isCenteredOn(final JFrame editor) throws Exception {
        final boolean[] centered = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            final JDialog dialog = visibleProgress();
            centered[0] = Math.abs(centerX(dialog) - centerX(editor)) <= CENTER_TOLERANCE_PIXELS
                    && Math.abs(centerY(dialog) - centerY(editor)) <= CENTER_TOLERANCE_PIXELS;
        });
        return centered[0];
    }

    private static int centerX(final Window window) {
        return window.getX() + window.getWidth() / 2;
    }

    private static int centerY(final Window window) {
        return window.getY() + window.getHeight() / 2;
    }

    private static JDialog visibleProgress() {
        return visibleProgress("Shared progress test");
    }

    private static JDialog visibleProgress(final String title) {
        return (JDialog) Arrays.stream(Window.getWindows())
                .filter(w ->
                        w instanceof JDialog d && d.isShowing() && d.getTitle().equals(title))
                .findFirst()
                .orElseThrow();
    }

    private static JProgressBar bar(JDialog dialog) {
        return (JProgressBar) dialog.getContentPane().getComponent(1);
    }
}
