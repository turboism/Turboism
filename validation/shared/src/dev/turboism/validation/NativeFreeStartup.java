package dev.turboism.validation;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/** Explicitly enabled task-local startup assistance; never activates a license or trial. */
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

    /** Acknowledges only the observed informational welcome for an already active trial. */
    public static boolean isActiveTrialWelcome(final String title, final String text) {
        if (!"Confirm".equals(title) || text == null) return false;
        final String plain = text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return plain.matches("Live2D Cubism Editor Welcome! Trial license will expire in [1-9][0-9]? day\\(s\\) "
                + "Please note that initializing or updating your operating system during the trial period "
                + "may terminate the trial version\\.");
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
        if (isActiveTrialWelcome(title, text) && "OK".equals(button)) {
            return "ACTIVE_TRIAL_WELCOME_ACKNOWLEDGED";
        }
        if (("Information Dialog Box".equals(title) || "Information".equals(title))
                && "OK".equals(button) && text != null
                && text.contains("Screen Color is only available in the PRO version.")
                && text.contains("Save failed. Please try again meeting the restrictions.")) {
            return "FREE_SCREEN_COLOR_SAVE_FAILURE_ACKNOWLEDGED";
        }
        if (("Information Dialog Box".equals(title) || "Information".equals(title))
                && "OK".equals(button) && text != null
                && text.contains("Multiply Color is only available in the PRO version.")
                && text.contains("Save failed. Please try again meeting the restrictions.")) {
            return "FREE_MULTIPLY_COLOR_SAVE_FAILURE_ACKNOWLEDGED";
        }
        if (("Information Dialog Box".equals(title) || "Information".equals(title))
                && text != null && "OK".equals(button)
                && text.contains("FREE version - The max number of Parameters allowed per object: 2")
                && text.contains("This operation is cancelled as 3 parameters are set.")) {
            return "FREE_PARAMETER_LIMIT_ACKNOWLEDGED";
        }
        if (isNewerModel(title, text)
                && ("Load".equals(button) || "Yes".equals(button) || "Yes(Y)".equals(button))) {
            return "NEWER_MODEL_OPEN_CONFIRMED";
        }
        if (isUpdatePage(title, text) && "Cancel".equals(button)) {
            return "UPDATE_PAGE_CANCELLED";
        }
        return null;
    }

    /** Bounded read-only evidence for visible native dialogs, including unmatched prompts. */
    public static String describeDialogs() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Native dialog observations must run on the EDT");
        }
        final List<String> descriptions = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (!(window instanceof Dialog dialog) || !dialog.isShowing()) continue;
            final List<Component> pending = new ArrayList<>();
            pending.add(dialog);
            final StringBuilder text = new StringBuilder();
            for (int index = 0; index < pending.size() && index < 200; index++) {
                final Component component = pending.get(index);
                if (component instanceof JLabel label && label.isShowing()) text.append(label.getText()).append(' ');
                if (component instanceof AbstractButton button && button.isShowing()) {
                    text.append("button=").append(button.getText()).append(' ');
                }
                if (component instanceof Container container && pending.size() < 200) {
                    pending.addAll(Arrays.asList(container.getComponents()));
                }
            }
            final String value = text.toString().replace('\n', ' ').replace('\r', ' ');
            descriptions.add("class=" + dialog.getClass().getName() + " title=" + dialog.getTitle()
                    + " modal=" + dialog.isModal() + " text=" + value.substring(0, Math.min(1024, value.length())));
            if (descriptions.size() == 8) break;
        }
        return descriptions.toString();
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
                System.setProperty("turboism.validation.observedLicenseMode", "FREE_WELCOME_OBSERVED");
                ok.doClick(0);
                return "FREE_WELCOME_CONFIRMED";
            }
            for (AbstractButton button : buttons) {
                final String action = promptAction(dialog.getTitle(), labels.toString(), button.getText());
                if (action != null) {
                    if ("ACTIVE_TRIAL_WELCOME_ACKNOWLEDGED".equals(action)) {
                        System.setProperty("turboism.validation.observedLicenseMode", "ACTIVE_TRIAL_WELCOME_OBSERVED");
                    }
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
