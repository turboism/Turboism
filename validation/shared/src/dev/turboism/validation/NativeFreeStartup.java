package dev.turboism.validation;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/** Explicitly enabled, validation-only FREE startup assistance inside the task JVM. */
public final class NativeFreeStartup {
    private NativeFreeStartup() {}

    /** Matches only the native FREE choice, never activation, purchase or trial. */
    public static boolean isStartButton(final String title, final String text) {
        return "Start".equals(title) && "Start as FREE version".equals(text);
    }

    /** Matches only the FREE welcome message, not trial or general confirmations. */
    public static boolean isWelcome(final String title, final String text) {
        return "Confirm".equals(title) && text != null && text.contains("Welcome!")
                && text.contains("Start as FREE version with limited functions");
    }

    /** Known native warning; user explicitly authorized opening the task fixture. */
    public static boolean isNewerModel(final String title, final String text) {
        return "Warning".equals(title) && text != null
                && text.contains("The file you are trying to load was created using a newer version")
                && text.contains("Current editor version:")
                && text.contains("Version of the file you are trying to load:");
    }

    /** Known update prompt; never exit the editor or open its download page. */
    public static boolean isUpdatePage(final String title, final String text) {
        return "Note!".equals(title) && text != null
                && text.contains("A new version has been released.")
                && text.contains("Do you want to exit the Editor and open the download page?");
    }

    public static String promptAction(final String title, final String text, final String button) {
        if (isNewerModel(title, text)
                && ("Load".equals(button) || "Yes".equals(button) || "Yes(Y)".equals(button))) {
            return "NEWER_MODEL_OPEN_CONFIRMED";
        }
        if (isUpdatePage(title, text) && "Cancel".equals(button)) {
            return "UPDATE_PAGE_CANCELLED";
        }
        return null;
    }

    /** Performs at most one exact UI action on the EDT and returns its evidence label, or null. */
    public static String select() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Native startup UI must run on the EDT");
        }
        for (Window window : Window.getWindows()) {
            if (!(window instanceof JDialog dialog) || !dialog.isShowing()) continue;
            final List<Component> pending = new ArrayList<>();
            pending.add(dialog);
            final StringBuilder labels = new StringBuilder();
            AbstractButton ok = null;
            final List<AbstractButton> buttons = new ArrayList<>();
            for (int index = 0; index < pending.size(); index++) {
                final Component component = pending.get(index);
                if (component instanceof JLabel label) labels.append(label.getText()).append('\n');
                if (component instanceof AbstractButton button && button.isShowing() && button.isEnabled()) {
                    buttons.add(button);
                    if ("OK".equals(button.getText())) ok = button;
                    if (isStartButton(dialog.getTitle(), button.getText())) {
                        button.doClick(0);
                        return "START_FREE_SELECTED";
                    }
                }
                if (component instanceof Container container) {
                    pending.addAll(Arrays.asList(container.getComponents()));
                }
            }
            if (ok != null && isWelcome(dialog.getTitle(), labels.toString())) {
                ok.doClick(0);
                return "FREE_WELCOME_CONFIRMED";
            }
            for (AbstractButton button : buttons) {
                final String action = promptAction(dialog.getTitle(), labels.toString(), button.getText());
                if (action != null) {
                    System.out.println("NATIVE_VALIDATION_PROMPT title=" + dialog.getTitle()
                            + " text=" + labels.toString().replace('\n', ' ')
                            + " button=" + button.getText() + " action=" + action);
                    button.doClick(0);
                    return action;
                }
            }
        }
        return null;
    }
}
