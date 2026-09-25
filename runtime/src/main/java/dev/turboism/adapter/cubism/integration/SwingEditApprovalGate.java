package dev.turboism.adapter.cubism.integration;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Production {@link EditApprovalGate}: shows the Turboism edit-approval dialog when the first
 * approval-gated editing request arrives on a connection (US4).
 *
 * <p>The dispatcher runs on the Swing event thread, so the confirmation dialog is shown
 * directly — a modal {@link JOptionPane} pumps a nested event loop and blocks this call until
 * the user decides, matching how the official approval step suspends the request it guards.
 * Denial is the safe answer everywhere: headless environments, dialogs that fail to open, and
 * explicit user refusal all return {@code false}.</p>
 */
public final class SwingEditApprovalGate implements EditApprovalGate {

    private static final String TITLE = "Turboism Edit Session";
    private static final String MESSAGE_TAIL =
        "requests permission to edit the current model.\n\n"
            + "Allow editing for this connection?";

    private final Supplier<Optional<Object>> mainWindow;

    /**
     * @param mainWindow supplies the editor main window handle (pre-resolved through the
     *                   verified window chain); empty centers the dialog on screen
     */
    public SwingEditApprovalGate(final Supplier<Optional<Object>> mainWindow) {
        this.mainWindow = Objects.requireNonNull(mainWindow, "mainWindow");
    }

    /** {@return a gate that always prompts with no owner window} */
    public static SwingEditApprovalGate unparented() {
        return new SwingEditApprovalGate(Optional::empty);
    }

    @Override
    public boolean isApproved(final EditConnectionInfo connection) {
        // The persisted grant lives on the connection state; the gate itself only prompts, so
        // the read path reports the host authorization flag as the best available answer.
        return connection.authorized();
    }

    @Override
    public boolean requestApproval(final EditConnectionInfo connection) {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        try {
            final Window owner = mainWindow.get()
                .filter(Window.class::isInstance)
                .map(Window.class::cast)
                .orElse(null);
            final int choice = SwingUtilities.isEventDispatchThread()
                ? prompt(owner, connection)
                : promptOnEdt(owner, connection);
            return choice == JOptionPane.YES_OPTION;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private int prompt(final Window owner, final EditConnectionInfo connection) {
        final String plugin = connection.pluginName().isEmpty()
            ? "An external plugin"
            : "The plugin \"" + connection.pluginName() + "\"";
        return JOptionPane.showConfirmDialog(
            owner,
            plugin + " " + MESSAGE_TAIL,
            TITLE,
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
    }

    private int promptOnEdt(final Window owner, final EditConnectionInfo connection) {
        final int[] choice = {JOptionPane.NO_OPTION};
        try {
            SwingUtilities.invokeAndWait(() -> choice[0] = prompt(owner, connection));
        } catch (Exception failure) {
            return JOptionPane.NO_OPTION;
        }
        return choice[0];
    }
}
