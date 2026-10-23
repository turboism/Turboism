package dev.turboism.sdk.ui.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.Arrays;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class TurboismProgressDialogTest {
    @Test
    void rejectsInvalidProgress() {
        try (var progress = new TurboismProgressDialog("Progress", "Preparing")) {
            assertThrows(IllegalArgumentException.class, () -> progress.setProgress(0, 0));
            assertThrows(IllegalArgumentException.class, () -> progress.setProgress(-1, 10));
            assertThrows(IllegalArgumentException.class, () -> progress.setProgress(11, 10));
        }
    }

    @Test
    void centersOnEditorAndSupportsWorkerUpdatesAndTerminalClose() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        JFrame[] editor = new JFrame[1];
        var progress = new TurboismProgressDialog("Shared progress test", "Preparing");
        try {
            SwingUtilities.invokeAndWait(() -> {
                editor[0] = new JFrame("Live2D Cubism Editor - progress test");
                editor[0].setBounds(90, 80, 800, 600);
                editor[0].setVisible(true);
            });
            progress.show();
            SwingUtilities.invokeAndWait(() -> {
                JDialog dialog = visibleProgress();
                assertSame(editor[0], dialog.getOwner());
                assertEquals(editor[0].getX() + editor[0].getWidth() / 2, dialog.getX() + dialog.getWidth() / 2, 1);
                assertEquals(editor[0].getY() + editor[0].getHeight() / 2, dialog.getY() + dialog.getHeight() / 2, 1);
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

    private static JDialog visibleProgress() {
        return (JDialog) Arrays.stream(Window.getWindows())
                .filter(w ->
                        w instanceof JDialog d && d.isShowing() && d.getTitle().equals("Shared progress test"))
                .findFirst()
                .orElseThrow();
    }

    private static JProgressBar bar(JDialog dialog) {
        return (JProgressBar) dialog.getContentPane().getComponent(1);
    }
}
