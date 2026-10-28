package dev.turboism.validation;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Explicitly enabled task-local startup assistance. The reviewing user
 * explicitly authorized selecting the native "Start PRO Version Free Trial"
 * entry on the Cubism license dialog plus the follow-up "Would you like to
 * start the Trial?" Yes confirmation and the activation-success notice, so
 * the trial path is driven end to end whenever offered. The FREE entry
 * remains the fallback when no trial entry exists. Purchase, activation-key,
 * cancel and dismiss entries are never clicked, and no license keys are ever
 * entered.
 */
public final class NativeFreeStartup {
    /** News windows already closed once; bounds the evidence line to one per instance. */
    private static final Set<Window> CLOSED_NEWS_WINDOWS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final String ANNOUNCEMENT_DIALOG_CLASS =
            "com.live2d.cubism.appCtrlImpl.ui.dialog.a";
    private static final String ANNOUNCEMENT_STAGE_FIELD = "d";
    private static final String JAVAFX_PLATFORM_CLASS = "javafx.application.Platform";
    private static final String UUCONFIG_CLASS = "com.live2d.util.UUConfig";
    private static final String UUCONFIG_INSTANCE_FIELD = "a";
    private static final String UUCONFIG_WRITE_METHOD = "b";
    private static final String ANNOUNCEMENT_DO_NOT_SHOW_TODAY_KEY =
            "AnnouncementDialog_DoNotShowAgainToday";
    private static final String ANNOUNCEMENT_LAST_OPENED_DATE_KEY =
            "AnnouncementDialog_LastOpenedDate";
    private static final int ANNOUNCEMENT_SUPPRESSED_DATE = 99991231;
    private static final long ANNOUNCEMENT_HIDE_WAIT_MILLIS = 250L;
    /** Announcement stages already hidden; maps stage instance to last hide attempt millis. */
    private static final Map<Object, Long> HIDDEN_ANNOUNCEMENT_STAGES =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /** Attempt budget per announcement stage instance. */
    private static final Map<Object, Integer> ANNOUNCEMENT_STAGE_ATTEMPTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** How long after the last trial-flow action to keep dumping window inventories. */
    private static final long TRIAL_OBSERVATION_MILLIS = 120_000L;

    /** End of the post-trial-click observation window for {@link #describeWindows()}. */
    private static volatile long trialObservationUntilMillis = 0L;

    /** Last printed {@link #describeWindows()} signature; duplicates are suppressed. */
    private static volatile String lastWindowObservation = "";

    /**
     * Dialog instances already answered once. A native prompt answer can open
     * a nested modal pump inside the click listener; deferring the click via
     * {@link SwingUtilities#invokeLater} keeps this scan runnable from being
     * trapped in that pump, while this set stops a still-closing instance from
     * being answered twice on subsequent passes.
     */
    private static final Set<Dialog> ACTIONED_DIALOGS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    /** Attempt budget per dialog: bounds the retry of an answer whose target
     * keeps showing (a stubborn modal must not be clicked forever). */
    private static final int MAX_DELIVERY_ATTEMPTS = 8;
    private static final int DELIVERY_VERIFY_MILLIS = 800;
    private static final Map<Dialog, Integer> DELIVERY_ATTEMPTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** The active-trial welcome expiry phrase, with the day count as data. */
    private static final java.util.regex.Pattern TRIAL_EXPIRY_DAYS =
            java.util.regex.Pattern.compile("Trial (license|period) will expire in [1-9]\\d* day\\(s\\)");

    private NativeFreeStartup() {}

    /**
     * Posts a guarded button click for a later EDT turn. The guard re-checks
     * visibility and enabled state at execution time so a dialog that
     * transitioned between the scan and the click is never touched, and a
     * still-showing dialog is made retryable afterwards: a single queued
     * click that raced a modal transition used to be the only attempt ever
     * made, which left a confirm modal (and whatever load it gated)
     * unanswered for the whole run.
     */
    private static void deferClick(final Dialog dialog, final AbstractButton button) {
        SwingUtilities.invokeLater(() -> {
            if (!(button.isShowing() && button.isEnabled())) {
                unmarkForRetry(dialog);
                return;
            }
            button.doClick(0);
            verifyDelivery(dialog);
        });
    }

    /**
     * After a delivery attempt, re-checks the dialog on a later EDT turn: a
     * click that was swallowed by a transition, or a dialog that re-shows
     * after briefly hiding, still presents itself to a later scan once the
     * answered mark is lifted. The attempt budget bounds how often one
     * dialog can be re-answered before the mark is left in place for good.
     */
    private static void verifyDelivery(final Dialog dialog) {
        final Timer timer = new Timer(DELIVERY_VERIFY_MILLIS, event -> unmarkForRetry(dialog));
        timer.setRepeats(false);
        timer.start();
    }

    /**
     * Lifts the answered mark on a still-showing dialog while the attempt
     * budget lasts, so a later scan can try the answer again; returns false
     * when the dialog is gone or the budget is spent.
     */
    private static boolean unmarkForRetry(final Dialog dialog) {
        if (!dialog.isShowing()
                || DELIVERY_ATTEMPTS.merge(dialog, 1, Integer::sum) >= MAX_DELIVERY_ATTEMPTS) {
            return false;
        }
        ACTIONED_DIALOGS.remove(dialog);
        return true;
    }

    /**
     * Posts the newer-file load answer for a later EDT turn: prefer the native
     * {@link JOptionPane} option value (exactly what the Load button's own
     * listener sets) and only fall back to a guarded button click when no
     * option pane carries the label. The answer is re-derived on the EDT from
     * the dialog's live components, so it always lands on the explicit
     * load-accept control — never on the default-focused Cancel.
     */
    private static void deferOptionPaneAnswer(final Dialog dialog, final String optionText) {
        SwingUtilities.invokeLater(() -> {
            if (!dialog.isShowing()) return;
            if (answerOptionPane(dialog, optionText)) {
                verifyDelivery(dialog);
                return;
            }
            for (AbstractButton button : collectButtons(dialog)) {
                if (optionText.equals(button.getText()) && button.isEnabled()) {
                    button.doClick(0);
                    verifyDelivery(dialog);
                    return;
                }
            }
            unmarkForRetry(dialog);
        });
    }

    /**
     * Posts the newer-file load answer for a later EDT turn. At execution the
     * dialog is re-identified as the newer-file gate, its accept control is
     * resolved again by label, and the answer is delivered through the option
     * pane value first and the explicit accept button second — never through
     * the default-focused Cancel and never through a queued key. When nothing
     * deliverable exists while the dialog still shows, the dialog is unmarked
     * so a later scan pass retries instead of losing the only attempt.
     */
    private static void deferNewerModelAnswer(final Dialog dialog) {
        SwingUtilities.invokeLater(() -> {
            if (!dialog.isShowing()) return;
            if (!isNewerModelPrompt(dialog.getTitle(), labelText(dialog))) return;
            final List<AbstractButton> buttons = collectButtons(dialog);
            final List<String> texts = new ArrayList<>();
            for (AbstractButton button : buttons) texts.add(button.getText());
            final AbstractButton accept = newerModelAcceptButton(buttons, texts);
            if (accept == null || !accept.isEnabled()) {
                unmarkForRetry(dialog);
                return;
            }
            if (!answerOptionPane(dialog, accept.getText())) accept.doClick(0);
            verifyDelivery(dialog);
        });
    }

    /** All showing {@link JLabel} text inside the given root, for re-identification on the EDT. */
    private static String labelText(final Component root) {
        final StringBuilder labels = new StringBuilder();
        final List<Component> pending = new ArrayList<>();
        pending.add(root);
        for (int index = 0; index < pending.size() && index < 200; index++) {
            final Component component = pending.get(index);
            if (component instanceof JLabel label) labels.append(label.getText()).append('\n');
            if (component instanceof Container container && pending.size() < 200) {
                pending.addAll(Arrays.asList(container.getComponents()));
            }
        }
        return labels.toString();
    }

    /** Every showing {@link AbstractButton} inside the given dialog. */
    private static List<AbstractButton> collectButtons(final Dialog dialog) {
        final List<AbstractButton> buttons = new ArrayList<>();
        final List<Component> pending = new ArrayList<>();
        pending.add(dialog);
        for (int index = 0; index < pending.size() && index < 200; index++) {
            final Component component = pending.get(index);
            if (component instanceof AbstractButton button && button.isShowing()) buttons.add(button);
            if (component instanceof Container container && pending.size() < 200) {
                pending.addAll(Arrays.asList(container.getComponents()));
            }
        }
        return buttons;
    }

    /** Matches only the native FREE choice, never activation, purchase or trial. */
    public static boolean isStartButton(final String title, final String text) {
        return "Start".equals(title) && "Start as FREE version".equals(text);
    }

    /** User-authorized native PRO trial entry; never activation, purchase or cancel. */
    public static boolean isTrialStartButton(final String title, final String text) {
        return "Start".equals(title) && "Start PRO Version Free Trial".equals(text);
    }

    /**
     * User-authorized selection order on the native license dialog: the PRO
     * trial start wins whenever offered, the FREE start is only the fallback.
     * Returns the exact button text to click, or null when neither audited
     * entry exists; purchase, activation-key and cancel entries never match.
     */
    public static String preferredStartButton(final String title, final List<String> texts) {
        return preferredStartButton(title, texts, true);
    }

    /** Allows FREE-only validation tasks to decline initiating a trial. */
    static String preferredStartButton(final String title, final List<String> texts, final boolean allowTrial) {
        if (texts == null) return null;
        boolean trial = false;
        boolean free = false;
        for (String text : texts) {
            if (isTrialStartButton(title, text)) trial = true;
            else if (isStartButton(title, text)) free = true;
        }
        if (trial && allowTrial) return "Start PRO Version Free Trial";
        return free ? "Start as FREE version" : null;
    }

    /**
     * Strips the native "(X)" keyboard-mnemonic suffix the noids prompt builder
     * appends to option labels ("Yes(Y)", "No(N)") so predicate checks compare
     * the semantic label only.
     */
    private static String baseLabel(final String label) {
        return label == null ? "" : label.replaceAll("\\s*\\([A-Za-z]\\)\\s*$", "");
    }

    /** Label set that can never launch a purchase, license-key entry or cancel path. */
    private static boolean isConfirmClassLabel(final String label) {
        final String base = baseLabel(label);
        return "Yes".equals(base) || "是".equals(base);
    }

    /** Label set that only acknowledges an informational prompt. */
    private static boolean isOkClassLabel(final String label) {
        final String base = baseLabel(label);
        return "OK".equals(base) || "确定".equals(base);
    }

    /** Label set that dismisses without enabling anything; never clicked here. */
    private static boolean isCancelClassLabel(final String label) {
        final String base = baseLabel(label);
        return "Cancel".equals(base) || "No".equals(base)
                || "取消".equals(base) || "否".equals(base)
                || "キャンセル".equals(base) || "いいえ".equals(base);
    }

    /**
     * Label set that expresses a preference rather than an answer, such as a
     * "Don't show again" toggle. It neither accepts nor cancels the gate, so a
     * prompt carrying it alongside the accept/cancel pair still matches the
     * button-shape whitelist.
     */
    private static boolean isNeutralClassLabel(final String label) {
        final String base = baseLabel(label);
        return "Don't show again".equals(base) || "Don’t show again".equals(base)
                || "Do not show again".equals(base) || "不再提示".equals(base)
                || "不再显示".equals(base) || "今後表示しない".equals(base);
    }

    /**
     * The user-authorized "open it anyway" answer on the newer-file warning:
     * every observed and localized accept label across the reviewed installs
     * (Load/Cancel, OK/Cancel, Open/Cancel, Yes/No and their localized twins).
     * These labels are only ever clicked when {@link #isNewerModelPrompt}
     * already identified the dialog as that gate.
     */
    static boolean isNewerModelAcceptLabel(final String label) {
        final String base = baseLabel(label);
        return "Load".equals(base) || "OK".equals(base) || "Open".equals(base)
                || "Yes".equals(base) || "加载".equals(base) || "打开".equals(base)
                || "确定".equals(base) || "確定".equals(base) || "ロード".equals(base)
                || "開く".equals(base) || "はい".equals(base) || "是".equals(base);
    }

    /**
     * User-authorized "start the Trial?" confirmation (native text COR3-0378)
     * shown immediately after "Start PRO Version Free Trial" is selected. It
     * offers Yes alongside dismissive choices; only the Yes-class button is a
     * valid answer here.
     */
    public static boolean isProTrialConfirm(final String title, final String text) {
        if (text == null) return false;
        // Locale/resource variants disagree on casing ("Would you like to
        // start the Trial?" on some installs, "...start the trial?" and "The
        // free trial..." on others), so match case-insensitively.
        final String plain = text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
        return plain.contains("limited period of 42 days") && plain.contains("trial");
    }

    /**
     * The trial-confirmation dialog may only be answered with the confirm
     * button itself: every labelled visible choice must be a confirm, OK or
     * cancel class label, and at least one confirm-class entry must exist.
     * Anything else (purchase, activation, unknown labels) refuses the click.
     */
    private static AbstractButton proTrialConfirmButton(final List<AbstractButton> buttons,
            final List<String> buttonTexts) {
        AbstractButton confirm = null;
        AbstractButton ok = null;
        for (int index = 0; index < buttons.size(); index++) {
            final String label = index < buttonTexts.size() ? buttonTexts.get(index) : null;
            if (label == null || label.isEmpty()) continue;
            if (isConfirmClassLabel(label)) {
                if (confirm == null) confirm = buttons.get(index);
            } else if (isOkClassLabel(label)) {
                if (ok == null) ok = buttons.get(index);
            } else if (!isCancelClassLabel(label)) {
                return null;
            }
        }
        return confirm != null ? confirm : ok;
    }

    /**
     * The post-activation success notice (native text COR3-0350, "License
     * activation successful") or a conservatively matched trial-activation
     * report: every labelled choice must be an OK-class button so no
     * destructive or purchase option can be clicked.
     */
    public static boolean isProTrialActivation(final String title, final String text,
            final List<String> buttonTexts) {
        if (text == null || buttonTexts == null) return false;
        boolean okSeen = false;
        for (String label : buttonTexts) {
            if (label == null || label.isEmpty()) continue;
            if (!isOkClassLabel(label)) return false;
            okSeen = true;
        }
        if (!okSeen) return false;
        final String combined = ((title == null ? "" : title) + " " + text)
                .replaceAll("<[^>]*>", " ").toLowerCase(Locale.ROOT);
        return combined.contains("activation successful")
                || (combined.contains("trial")
                        && (combined.contains("activat") || combined.contains("success")));
    }

    /**
     * Non-modal "Home" news window opened at startup when News and Release
     * Notes were updated. It is never a prompt and only steals focus or
     * registers as a leftover window in health/close checks, so the audited
     * handling closes the window itself, never its controls, which open the
     * download page or release notes, or toggle a startup preference.
     */
    public static boolean isHomeNewsDialog(final String title, final String text,
            final List<String> buttonTexts) {
        if (!"Home".equals(title)) return false;
        final StringBuilder combined = new StringBuilder(text == null ? "" : text);
        if (buttonTexts != null) {
            for (String button : buttonTexts) {
                if (button != null) combined.append(' ').append(button);
            }
        }
        final String value = combined.toString();
        return value.contains("A new version is available.")
                && value.contains("News and Release Notes");
    }

    /** Matches only the FREE welcome message, not trial or general confirmations. */
    public static boolean isWelcome(final String title, final String text) {
        return "Confirm".equals(title) && text != null && text.contains("Welcome!")
                && text.contains("Start as FREE version with limited functions");
    }

    /**
     * Acknowledges only the observed informational welcome for an already
     * active trial. The dialog may carry extra header labels (e.g. a
     * "Confirm" caption label) alongside the message, so the signature is a
     * conjunction of markers rather than a whole-text match: the greeting,
     * the day-count expiry phrase and the termination caution. The caution
     * wording itself drifts across versions ("may terminate the trial
     * version" on 5.1.x+, "might cause to terminate trial mode" on 5.0.00),
     * so only the shared stem is required. The trial-start confirm and the
     * FREE welcome share none of that exact wording.
     */
    public static boolean isActiveTrialWelcome(final String title, final String text) {
        if (!"Confirm".equals(title) || text == null) return false;
        final String plain = text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return plain.contains("Welcome!")
                && TRIAL_EXPIRY_DAYS.matcher(plain).find()
                && plain.contains("terminate");
    }

    /** Known native warning; user explicitly authorized opening the task fixture. */
    public static boolean isNewerModel(final String title, final String text) {
        return "Warning".equals(title) && text != null
                && text.contains("The file you are trying to load was created using a newer version")
                && text.contains("Current editor version:")
                && text.contains("Version of the file you are trying to load:");
    }

    /**
     * The newer-file load gate across all reviewed locales: the exact English
     * signature, or a dialog whose text still carries the "newer version" /
     * damaged-file markers on a differently titled or localized build. Foreign
     * prompts that share vocabulary (the update download prompt, the trial
     * confirmations, the activation notice) are excluded explicitly before the
     * loose marker is consulted, and the caller additionally requires the
     * accept/cancel button shape of {@link #newerModelAcceptButton}.
     */
    public static boolean isNewerModelPrompt(final String title, final String text) {
        if (isNewerModel(title, text)) return true;
        if (text == null) return false;
        if (isUpdatePage(title, text) || isProTrialConfirm(title, text)
                || isHomeNewsDialog(title, text, null) || isWelcome(title, text)
                || isActiveTrialWelcome(title, text)) {
            return false;
        }
        final String plain = text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
        return plain.contains("newer version") || plain.contains("破損")
                || plain.contains("损坏") || plain.contains("受損");
    }

    /**
     * The newer-file gate may only be answered through its own accept control:
     * every labelled visible choice must be an accept, cancel or neutral class
     * label and at least one accept must exist. Anything else (purchase,
     * activation, unknown third actions) refuses the answer entirely.
     */
    private static AbstractButton newerModelAcceptButton(final List<AbstractButton> buttons,
            final List<String> buttonTexts) {
        AbstractButton accept = null;
        for (int index = 0; index < buttons.size(); index++) {
            final String label = index < buttonTexts.size() ? buttonTexts.get(index) : null;
            if (label == null || label.isEmpty()) continue;
            if (isNewerModelAcceptLabel(label)) {
                if (accept == null) accept = buttons.get(index);
            } else if (!isCancelClassLabel(label) && !isNeutralClassLabel(label)) {
                return null;
            }
        }
        return accept;
    }

    /** Known update prompt; never exit the editor or open its download page. */
    public static boolean isUpdatePage(final String title, final String text) {
        return "Note!".equals(title) && text != null
                && text.contains("A new version has been released.")
                && text.contains("Do you want to exit the Editor and open the download page?");
    }

    public static String promptAction(final String title, final String text, final String button) {
        final String baseButton = baseLabel(button);
        if (isActiveTrialWelcome(title, text) && ("OK".equals(baseButton) || "Continue".equals(baseButton)
                || "确定".equals(baseButton) || "继续使用".equals(baseButton))) {
            return "ACTIVE_TRIAL_WELCOME_ACKNOWLEDGED";
        }
        if (("Information Dialog Box".equals(title) || "Information".equals(title))
                && "OK".equals(baseButton) && text != null
                && text.contains("Screen Color is only available in the PRO version.")
                && text.contains("Save failed. Please try again meeting the restrictions.")) {
            return "FREE_SCREEN_COLOR_SAVE_FAILURE_ACKNOWLEDGED";
        }
        if (("Information Dialog Box".equals(title) || "Information".equals(title))
                && "OK".equals(baseButton) && text != null
                && text.contains("Multiply Color is only available in the PRO version.")
                && text.contains("Save failed. Please try again meeting the restrictions.")) {
            return "FREE_MULTIPLY_COLOR_SAVE_FAILURE_ACKNOWLEDGED";
        }
        // CUBI-1476: any FREE save-restriction notice (atlas count, per-object
        // parameter bound, deformer/part limits, ...) ends with the same fixed
        // suffix and offers only OK; acknowledging never alters the already
        // rejected save. Listed after the feature-specific rules above so they
        // keep their own evidence labels.
        if (("Information Dialog Box".equals(title) || "Information".equals(title))
                && "OK".equals(baseButton) && text != null
                && text.contains("Save failed. Please try again meeting the restrictions.")) {
            return "FREE_SAVE_RESTRICTION_ACKNOWLEDGED";
        }
        // CUBI-1477: the same restriction family on the native export path.
        if (("Information Dialog Box".equals(title) || "Information".equals(title))
                && "OK".equals(baseButton) && text != null
                && text.contains("Export failed. Please try again meeting the restrictions.")) {
            return "FREE_EXPORT_RESTRICTION_ACKNOWLEDGED";
        }
        if (("Information Dialog Box".equals(title) || "Information".equals(title))
                && text != null && "OK".equals(baseButton)
                && text.contains("FREE version - The max number of Parameters allowed per object: 2")
                && text.contains("This operation is cancelled as 3 parameters are set.")) {
            return "FREE_PARAMETER_LIMIT_ACKNOWLEDGED";
        }
        if (isNewerModel(title, text) && isNewerModelAcceptLabel(button)) {
            return "NEWER_MODEL_OPEN_CONFIRMED";
        }
        if (isUpdatePage(title, text) && "Cancel".equals(baseButton)) {
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
                    text.append("button=").append(button.getText());
                    if (!button.isEnabled()) text.append("(disabled)");
                    text.append(' ');
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
        return select(true);
    }

    /** Performs startup assistance without initiating or confirming a new trial. */
    public static String selectFreeOnly() {
        return select(false);
    }

    private static String select(final boolean allowTrial) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Native startup UI must run on the EDT");
        }
        boolean homeNewsClosed = false;
        try {
            for (Window window : Window.getWindows()) {
                if (!(window instanceof Dialog dialog) || !dialog.isShowing()) continue;
                final List<Component> pending = new ArrayList<>();
                pending.add(dialog);
                final StringBuilder labels = new StringBuilder();
                AbstractButton ok = null;
                final List<AbstractButton> buttons = new ArrayList<>();
                final List<String> buttonTexts = new ArrayList<>();
                for (int index = 0; index < pending.size(); index++) {
                    final Component component = pending.get(index);
                    if (component instanceof JLabel label) labels.append(label.getText()).append('\n');
                    if (component instanceof AbstractButton button && button.isShowing()) {
                        buttons.add(button);
                        buttonTexts.add(button.getText());
                        if ("OK".equals(baseLabel(button.getText()))) ok = button;
                    }
                    if (component instanceof Container container) {
                        pending.addAll(Arrays.asList(container.getComponents()));
                    }
                }
                // The non-modal news window never carries a user decision; close it
                // once per instance and keep scanning so a coexisting modal is
                // still handled in this pass.
                if (!dialog.isModal() && !CLOSED_NEWS_WINDOWS.contains(dialog)
                        && isHomeNewsDialog(dialog.getTitle(), labels.toString(), buttonTexts)) {
                    CLOSED_NEWS_WINDOWS.add(dialog);
                    ACTIONED_DIALOGS.add(dialog);
                    dialog.setVisible(false);
                    dialog.dispose();
                    homeNewsClosed = true;
                    continue;
                }
                if (ACTIONED_DIALOGS.contains(dialog)) continue;
                // Evidence for a matched prompt whose only viable control is
                // disabled; the helper must not pretend it can click it.
                final String startChoice = preferredStartButton(dialog.getTitle(), buttonTexts, allowTrial);
                if (startChoice != null) {
                    for (AbstractButton button : buttons) {
                        if (startChoice.equals(button.getText()) && button.isEnabled()) {
                            ACTIONED_DIALOGS.add(dialog);
                            deferClick(dialog, button);
                            trialObservationUntilMillis = System.currentTimeMillis()
                                    + TRIAL_OBSERVATION_MILLIS;
                            return isTrialStartButton(dialog.getTitle(), startChoice)
                                    ? "START_PRO_TRIAL_SELECTED" : "START_FREE_SELECTED";
                        }
                    }
                }
                if (allowTrial && isProTrialConfirm(dialog.getTitle(), labels.toString())) {
                    final AbstractButton confirm = proTrialConfirmButton(buttons, buttonTexts);
                    if (confirm != null && confirm.isEnabled()) {
                        ACTIONED_DIALOGS.add(dialog);
                        deferClick(dialog, confirm);
                        trialObservationUntilMillis = System.currentTimeMillis()
                                + TRIAL_OBSERVATION_MILLIS;
                        return "PRO_TRIAL_CONFIRMED";
                    }
                    if (confirm != null) {
                        System.out.println("NATIVE_VALIDATION_PROMPT_DISABLED title="
                                + dialog.getTitle() + " button=" + confirm.getText()
                                + " action=PRO_TRIAL_CONFIRMED");
                    }
                }
                if (ok != null && isWelcome(dialog.getTitle(), labels.toString())) {
                    if (ok.isEnabled()) {
                        ACTIONED_DIALOGS.add(dialog);
                        System.setProperty("turboism.validation.observedLicenseMode", "FREE_WELCOME_OBSERVED");
                        deferClick(dialog, ok);
                        return "FREE_WELCOME_CONFIRMED";
                    }
                    System.out.println("NATIVE_VALIDATION_PROMPT_DISABLED title="
                            + dialog.getTitle() + " button=OK action=FREE_WELCOME_CONFIRMED");
                }
                if (allowTrial && ok != null && isProTrialActivation(dialog.getTitle(), labels.toString(), buttonTexts)) {
                    if (ok.isEnabled()) {
                        ACTIONED_DIALOGS.add(dialog);
                        System.setProperty("turboism.validation.observedLicenseMode", "PRO_TRIAL_STARTED");
                        deferClick(dialog, ok);
                        trialObservationUntilMillis = System.currentTimeMillis()
                                + TRIAL_OBSERVATION_MILLIS;
                        return "PRO_TRIAL_ACTIVATED_ACKNOWLEDGED";
                    }
                    System.out.println("NATIVE_VALIDATION_PROMPT_DISABLED title="
                            + dialog.getTitle() + " button=OK action=PRO_TRIAL_ACTIVATED_ACKNOWLEDGED");
                }
                // The newer-file gate default-focuses Cancel: the answer must
                // reach the accept control itself. The EDT-deferred runnable
                // re-identifies the dialog and re-resolves the accept label so
                // a rebuilt dialog or a lost first attempt can never fall back
                // to the focused cancel.
                if (isNewerModelPrompt(dialog.getTitle(), labels.toString())) {
                    final AbstractButton accept = newerModelAcceptButton(buttons, buttonTexts);
                    if (accept != null && accept.isEnabled()) {
                        ACTIONED_DIALOGS.add(dialog);
                        deferNewerModelAnswer(dialog);
                        return "NEWER_MODEL_OPEN_CONFIRMED";
                    }
                    if (accept != null) {
                        System.out.println("NATIVE_VALIDATION_PROMPT_DISABLED title="
                                + dialog.getTitle() + " button=" + accept.getText()
                                + " action=NEWER_MODEL_OPEN_CONFIRMED");
                    }
                }
                for (AbstractButton button : buttons) {
                    final String action = promptAction(dialog.getTitle(), labels.toString(), button.getText());
                    if (action == null) continue;
                    if (!button.isEnabled()) {
                        System.out.println("NATIVE_VALIDATION_PROMPT_DISABLED title="
                                + dialog.getTitle() + " text=" + labels.toString().replace('\n', ' ')
                                + " button=" + button.getText() + " action=" + action);
                        continue;
                    }
                    if ("ACTIVE_TRIAL_WELCOME_ACKNOWLEDGED".equals(action)) {
                        System.setProperty("turboism.validation.observedLicenseMode", "ACTIVE_TRIAL_WELCOME_OBSERVED");
                    }
                    System.out.println("NATIVE_VALIDATION_PROMPT title=" + dialog.getTitle()
                            + " text=" + labels.toString().replace('\n', ' ')
                            + " button=" + button.getText() + " action=" + action);
                    ACTIONED_DIALOGS.add(dialog);
                    if ("NEWER_MODEL_OPEN_CONFIRMED".equals(action)) {
                        deferOptionPaneAnswer(dialog, button.getText());
                        return action;
                    }
                    deferClick(dialog, button);
                    return action;
                }
            }
            final String announcementAction = dismissAnnouncementStageIfPresent();
            if (announcementAction != null) {
                return announcementAction;
            }
            return homeNewsClosed ? "HOME_NEWS_DIALOG_CLOSED" : null;
        } finally {
            emitTrialWindowObservation();
        }
    }

    /**
     * While a trial flow is in flight, print the complete showing-window
     * inventory whenever it changes so every intermediate prompt  --  modal or
     * not, JDialog or any other Window class  --  lands in the console evidence
     * even when no rule matched it yet.
     */
    private static void emitTrialWindowObservation() {
        if (System.currentTimeMillis() >= trialObservationUntilMillis) return;
        final String dump = describeWindows();
        if (dump.equals(lastWindowObservation)) return;
        lastWindowObservation = dump;
        System.out.println("NATIVE_STARTUP_WINDOWS " + dump);
    }

    /**
     * Bounded read-only inventory of every showing top-level window: class,
     * title, modality, owner and visible label/button text, for trial-flow
     * diagnostics on windows that are not JDialogs or that carry no matched
     * rule.
     */
    public static String describeWindows() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Native window observations must run on the EDT");
        }
        final List<String> descriptions = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            final List<Component> pending = new ArrayList<>();
            pending.add(window);
            final StringBuilder text = new StringBuilder();
            for (int index = 0; index < pending.size() && index < 200; index++) {
                final Component component = pending.get(index);
                if (component instanceof JLabel label && label.isShowing()) text.append(label.getText()).append(' ');
                if (component instanceof AbstractButton button && button.isShowing()) {
                    text.append("button=").append(button.getText());
                    if (!button.isEnabled()) text.append("(disabled)");
                    text.append(' ');
                }
                if (component instanceof Container container && pending.size() < 200) {
                    pending.addAll(Arrays.asList(container.getComponents()));
                }
            }
            final String value = text.toString().replace('\n', ' ').replace('\r', ' ');
            final StringBuilder entry = new StringBuilder("class=").append(window.getClass().getName());
            if (window instanceof java.awt.Frame frame) {
                entry.append(" title=").append(frame.getTitle());
            } else if (window instanceof Dialog dialog) {
                entry.append(" title=").append(dialog.getTitle());
            }
            if (window instanceof Dialog dialog) {
                entry.append(" modal=").append(dialog.isModal());
                final Window owner = dialog.getOwner();
                entry.append(" owner=").append(owner == null ? "null"
                        : owner.getClass().getName());
            }
            entry.append(" text=").append(value, 0, Math.min(512, value.length()));
            descriptions.add(entry.toString());
            if (descriptions.size() == 12) break;
        }
        return descriptions.toString();
    }

    /**
     * Delivers the reviewed option to a JOptionPane-backed dialog through
     * {@code pane.setValue(option)}  --  the exact call the option button's own
     * listener performs  --  instead of a synthetic button press, so the answer
     * cannot be lost to an armed-model or press-state race. Returns false when
     * the dialog has no option pane carrying that option.
     */
    private static boolean answerOptionPane(final Dialog dialog, final String optionText) {
        final List<Component> pending = new ArrayList<>();
        pending.add(dialog);
        for (int index = 0; index < pending.size() && index < 200; index++) {
            final Component component = pending.get(index);
            if (component instanceof JOptionPane pane) {
                final Object[] options = pane.getOptions();
                if (options == null) continue;
                for (Object option : options) {
                    if (optionText.equals(String.valueOf(option))) {
                        pane.setValue(option);
                        return true;
                    }
                }
            }
            if (component instanceof Container container && pending.size() < 200) {
                pending.addAll(Arrays.asList(container.getComponents()));
            }
        }
        return false;
    }

    /**
     * Detects and hides the Cubism 5.0.x JavaFX announcement stage
     * ({@code com.live2d.cubism.appCtrlImpl.ui.dialog.a.d}) when present.
     *
     * <p>Cubism 5.0.00–5.0.06 launches a JavaFX {@code Stage} in the background on
     * startup whose {@code onShown} callback disables the main Swing {@code JFrame}
     * and {@code JMenuBar}; because a JavaFX {@code Stage} is not an AWT
     * {@link Window}, {@link Window#getWindows()} never lists it. Hiding the stage
     * on the JavaFX thread via {@code Platform.runLater(stage::hide)} invokes the
     * native {@code onHiding} callback to restore the main frame and menu bar
     * without calling {@code setEnabled(true)} directly. The {@code UUConfig}
     * do-not-show keys are also written best-effort into the task-isolated home's
     * configuration so the dialog does not re-open during the same task run. When
     * the class or field is absent or {@code null}, this method is a no-op.</p>
     */
    static String dismissAnnouncementStageIfPresent() {
        return dismissAnnouncementStageIfPresent(candidateClassLoaders());
    }

    static String dismissAnnouncementStageIfPresent(final Iterable<ClassLoader> loaders) {
        if (loaders == null) return null;
        for (final ClassLoader loader : loaders) {
            if (loader == null) continue;
            final Object stage = findAnnouncementStage(loader);
            if (stage == null || !shouldHideAnnouncementStage(stage)) continue;
            suppressAnnouncementConfigBestEffort(loader);
            if (hideJavaFxStage(stage, loader)) {
                return "ANNOUNCEMENT_STAGE_HIDDEN";
            }
        }
        return null;
    }

    private static Object findAnnouncementStage(final ClassLoader loader) {
        try {
            final Class<?> dialogClass =
                    Class.forName(ANNOUNCEMENT_DIALOG_CLASS, false, loader);
            final var stageField = dialogClass.getDeclaredField(ANNOUNCEMENT_STAGE_FIELD);
            stageField.setAccessible(true);
            return stageField.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean shouldHideAnnouncementStage(final Object stage) {
        final int attempts = ANNOUNCEMENT_STAGE_ATTEMPTS.getOrDefault(stage, 0);
        if (attempts >= MAX_DELIVERY_ATTEMPTS) {
            return false;
        }
        final Long lastAttempt = HIDDEN_ANNOUNCEMENT_STAGES.get(stage);
        if (lastAttempt == null) {
            return true;
        }
        if (System.currentTimeMillis() - lastAttempt < DELIVERY_VERIFY_MILLIS) {
            return false;
        }
        return Boolean.TRUE.equals(queryStageShowing(stage));
    }

    private static Boolean queryStageShowing(final Object stage) {
        try {
            final Object showing = stage.getClass().getMethod("isShowing").invoke(stage);
            if (showing instanceof Boolean flag) return flag;
        } catch (Throwable ignored) {
            // Synthetic test stages may omit isShowing().
        }
        return null;
    }

    /**
     * Writes the native announcement-dialog suppression keys into {@code UUConfig}
     * when present. In validation runs {@code user.home}/{@code APPDATA} is bound to
     * the task-isolated Proton prefix / home, so this only updates task-local
     * configuration and never touches a user profile.
     */
    private static void suppressAnnouncementConfigBestEffort(final ClassLoader loader) {
        try {
            final Class<?> configClass = Class.forName(UUCONFIG_CLASS, true, loader);
            final var instanceField = configClass.getDeclaredField(UUCONFIG_INSTANCE_FIELD);
            instanceField.setAccessible(true);
            final Object config = instanceField.get(null);
            if (config == null) return;
            final var writeMethod =
                    configClass.getMethod(UUCONFIG_WRITE_METHOD, String.class, Object.class);
            writeMethod.invoke(config, ANNOUNCEMENT_DO_NOT_SHOW_TODAY_KEY, Boolean.TRUE);
            writeMethod.invoke(
                    config,
                    ANNOUNCEMENT_LAST_OPENED_DATE_KEY,
                    Integer.valueOf(ANNOUNCEMENT_SUPPRESSED_DATE));
        } catch (Throwable ignored) {
            // UUConfig absent or signature differs on this build; stage hide still proceeds.
        }
    }

    private static boolean hideJavaFxStage(final Object stage, final ClassLoader fallbackLoader) {
        try {
            final Class<?> platformClass = loadClass(
                    JAVAFX_PLATFORM_CLASS, stage.getClass().getClassLoader(), fallbackLoader);
            final var runLater = platformClass.getMethod("runLater", Runnable.class);
            final var hideMethod = stage.getClass().getMethod("hide");
            HIDDEN_ANNOUNCEMENT_STAGES.put(stage, System.currentTimeMillis());
            ANNOUNCEMENT_STAGE_ATTEMPTS.merge(stage, 1, Integer::sum);
            final CountDownLatch hidden = new CountDownLatch(1);
            try {
                runLater.invoke(null, (Runnable) () -> {
                    try {
                        hideMethod.invoke(stage);
                    } catch (Throwable ignored) {
                        HIDDEN_ANNOUNCEMENT_STAGES.remove(stage);
                    } finally {
                        hidden.countDown();
                    }
                });
            } catch (Throwable invokeFailure) {
                HIDDEN_ANNOUNCEMENT_STAGES.remove(stage);
                return false;
            }
            hidden.await(ANNOUNCEMENT_HIDE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            return HIDDEN_ANNOUNCEMENT_STAGES.containsKey(stage);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return HIDDEN_ANNOUNCEMENT_STAGES.containsKey(stage);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Class<?> loadClass(
            final String name, final ClassLoader primary, final ClassLoader fallback)
            throws ClassNotFoundException {
        if (primary != null) {
            try {
                return Class.forName(name, false, primary);
            } catch (ClassNotFoundException ignored) {
                // try fallback loader
            }
        }
        return Class.forName(name, false, fallback);
    }

    private static List<ClassLoader> candidateClassLoaders() {
        final List<ClassLoader> loaders = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            final ClassLoader loader = window.getClass().getClassLoader();
            if (loader != null && !loaders.contains(loader)) {
                loaders.add(loader);
            }
        }
        for (ClassLoader fallback : new ClassLoader[] {
            Thread.currentThread().getContextClassLoader(),
            NativeFreeStartup.class.getClassLoader(),
            ClassLoader.getSystemClassLoader()
        }) {
            if (fallback != null && !loaders.contains(fallback)) {
                loaders.add(fallback);
            }
        }
        return loaders;
    }
}
