package dev.turboism.plugin.geometricmeshlayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.turboism.sdk.cubism.model.Point2;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Display-backed checks for the plugin-owned configuration window.
 *
 * <p>These cases need a real windowing system: they assert on the constructed {@link JDialog}, which
 * a headless JVM refuses to create. The suite skips on a headless runner and executes on a desktop
 * host, which is where the window-ordering defect was observed.</p>
 */
class GeometricMeshLayoutDialogDisplayTest {

    @Test
    void configurationDialogIsOwnedByTheHostFrame() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");

        SwingUtilities.invokeAndWait(() -> {
            final java.awt.Frame host = new java.awt.Frame("Live2D Cubism Editor");
            host.setSize(400, 300);
            host.setVisible(true);
            final GeometricMeshLayoutDialog dialog = new GeometricMeshLayoutDialog(
                    GeometricMeshConfig.defaults(),
                    new Point2(10.0f, 20.0f),
                    new NoopListener(),
                    PluginCatalogs.english());
            try {
                dialog.show();
                final JDialog window = ownedDialog(dialog);
                assertNotNull(window, "the configuration window must be constructed on a display");
                assertSame(host, window.getOwner(), "a modeless dialog must be owned by the host frame");
            } finally {
                dialog.dispose();
                host.dispose();
            }
        });
    }

    @Test
    void anchorUpdateRaisesTheDialogAboveTheHost() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");

        SwingUtilities.invokeAndWait(() -> {
            final java.awt.Frame host = new java.awt.Frame("Live2D Cubism Editor");
            host.setSize(400, 300);
            host.setVisible(true);
            final GeometricMeshLayoutDialog dialog = new GeometricMeshLayoutDialog(
                    GeometricMeshConfig.defaults(),
                    new Point2(10.0f, 20.0f),
                    new NoopListener(),
                    PluginCatalogs.english());
            try {
                dialog.show();
                final JDialog window = ownedDialog(dialog);
                assertNotNull(window, "the configuration window must be constructed on a display");
                // Placing the anchor focuses the modeling view and raises the host; the tool then
                // calls show() again so the operator keeps the configuration window in reach.
                host.toFront();
                dialog.updateAnchor(new Point2(30.0f, 40.0f));
                dialog.show();
                assertEquals(true, window.isShowing(), "the configuration window must stay visible");
            } finally {
                dialog.dispose();
                host.dispose();
            }
        });
    }

    private static JDialog ownedDialog(final GeometricMeshLayoutDialog dialog) {
        // Window.getWindows() keeps disposed windows until they are collected, so only a live
        // (displayable) dialog of this name belongs to the activation under test.
        for (final Window window : Window.getWindows()) {
            if (window instanceof JDialog candidate
                    && candidate.isDisplayable()
                    && GeometricMeshLayoutDialog.DIALOG_NAME.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    private static final class NoopListener implements GeometricMeshLayoutDialog.Listener {
        @Override
        public void onConfigChanged(final GeometricMeshConfig config) {}

        @Override
        public void onConfirm(final GeometricMeshConfig config) {}

        @Override
        public void onCancel() {}
    }
}
