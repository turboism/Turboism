package dev.turboism.ui.dialog;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.ui.dialog.HostDialogAction;
import dev.turboism.sdk.ui.dialog.HostDialogAutomationService;
import dev.turboism.sdk.ui.dialog.HostDialogMatcher;
import dev.turboism.sdk.ui.dialog.HostDialogOutcome;
import dev.turboism.sdk.ui.dialog.HostDialogSnapshot;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime implementation of {@link HostDialogAutomationService} (host JVM only).
 *
 * <p>Port of the dialog-handling logic verified on Cubism 5.2.03 / 5.3.02 by the
 * Windows parameter validation probe (visible dialog enumeration, JOptionPane
 * discovery, visible-button collection, multilingual semantic button matching,
 * two-phase deadline polling). Operates exclusively on the host JVM's AWT window
 * tree on the EDT: no Robot, no coordinates, no cross-process windows, no OCR.
 * Ambiguity and unrecognizable dialogs fail closed.</p>
 */
public final class RuntimeHostDialogAutomationService implements HostDialogAutomationService {

    public static final String UI_DIALOG_AUTOMATE = PermissionIds.TURBOISM_UI_DIALOG_AUTOMATE;

    private static final long POLL_MILLIS = 100L;
    private static final long EDT_ACCEPT_MILLIS = 5_000L;

    /** Exact normalized button values per semantic action (probe word lists, generalized). */
    private static final Map<HostDialogAction, List<String>> EXACT_VALUES = Map.of(
        HostDialogAction.OK, List.of("ok", "okay", "确定", "確定", "はい"),
        HostDialogAction.YES, List.of("yes", "是", "はい"),
        HostDialogAction.NO, List.of("no"),
        HostDialogAction.CANCEL, List.of("cancel", "取消", "キャンセル")
    );

    /** Phrase substrings per semantic action (probe discard/no word lists). */
    private static final Map<HostDialogAction, List<String>> CONTAINS_VALUES = Map.of(
        HostDialogAction.OK, List.of(),
        HostDialogAction.YES, List.of(),
        HostDialogAction.NO, List.of(
            "discard", "dontsave", "donotsave", "nosave", "notsave",
            "不保存", "不要保存", "不儲存", "不要儲存", "不存檔", "不要存檔",
            "放弃", "放棄", "舍弃", "捨棄", "保存しない", "セーブしない"
        ),
        HostDialogAction.CANCEL, List.of()
    );

    /** Mnemonic suffix letter for "No (N)"-style localized labels. */
    private static final Map<HostDialogAction, String> MNEMONIC_LETTERS = Map.of(
        HostDialogAction.OK, "o",
        HostDialogAction.YES, "y",
        HostDialogAction.NO, "n",
        HostDialogAction.CANCEL, "c"
    );

    private final PermissionChecker permissionChecker;

    public RuntimeHostDialogAutomationService(final PermissionChecker permissionChecker) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
    }

    @Override
    public HostDialogOutcome act(
        final HostDialogMatcher matcher,
        final HostDialogAction action,
        final Duration timeout
    ) {
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        permissionChecker.check(UI_DIALOG_AUTOMATE, "ui.dialog.automate.act");
        try {
            return actInternal(matcher, action, timeout);
        } catch (DialogAutomationFailure failure) {
            // Flow failures never escape as exceptions (fail closed as UNSUPPORTED).
            return HostDialogOutcome.UNSUPPORTED;
        }
    }

    @Override
    public List<HostDialogSnapshot> snapshots() {
        permissionChecker.check(UI_DIALOG_AUTOMATE, "ui.dialog.automate.snapshots");
        try {
            return onHostThread(() -> visibleDialogs().stream()
                .map(dialog -> new HostDialogSnapshot(
                    dialog.getClass().getName(),
                    dialog.isModal(),
                    optionTypeOf(dialog).orElse(-1),
                    visibleButtons(dialog).stream().map(JButton::getText).toList()
                ))
                .toList());
        } catch (DialogAutomationFailure failure) {
            return List.of();
        }
    }

    private HostDialogOutcome actInternal(
        final HostDialogMatcher matcher,
        final HostDialogAction action,
        final Duration timeout
    ) {
        final long deadlineNanos = System.nanoTime() + timeout.toNanos();
        // Phase A: wait for the target dialog to appear (already visible → immediate).
        DialogState observed = null;
        while (System.nanoTime() < deadlineNanos) {
            observed = frontmostDialog(matcher);
            if (observed != null) {
                break;
            }
            sleepQuietly(POLL_MILLIS);
        }
        if (observed == null) {
            return HostDialogOutcome.NOT_FOUND;
        }
        // Step 2: JOptionPane type detection; a bare dialog is unsupported for an
        // all-empty matcher (mirrors the probe's unsupported-confirmation decision).
        final boolean matcherAllEmpty = matcher.windowClassPrefix().isEmpty()
            && matcher.optionType().isEmpty();
        // Step 2: a bare dialog is unsupported only when it offers no action surface at all
        // (no JOptionPane and no visible buttons) for an all-empty matcher; a bare dialog
        // with buttons still goes through semantic button matching (Cubism's unsaved-changes
        // confirmation is a bare dialog with 2-3 buttons, no JOptionPane).
        if (observed.optionPane() == null && observed.buttons().isEmpty() && matcherAllEmpty) {
            return HostDialogOutcome.UNSUPPORTED;
        }
        // Phase B: trigger the semantic action on the EDT.
        final HostDialogOutcome triggered = trigger(observed, action);
        if (triggered != HostDialogOutcome.ACTED) {
            return triggered;
        }
        // Phase C: wait for the dialog to disappear (isVisible == false or !isShowing).
        while (System.nanoTime() < deadlineNanos) {
            if (dialogClosed(observed.dialog())) {
                return HostDialogOutcome.ACTED;
            }
            sleepQuietly(POLL_MILLIS);
        }
        return HostDialogOutcome.TIMEOUT;
    }

    /**
     * Step 1: frontmost host dialog on the EDT — active window first (isShowing and
     * matching), then visible modal (or active) AWT dialogs, filtered by matcher.
     */
    private static DialogState frontmostDialog(final HostDialogMatcher matcher) throws DialogAutomationFailure {
        return onHostThread(() -> {
            final Window active =
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            if (active instanceof Dialog && active.isShowing() && matches(active, matcher)) {
                return stateOf((Dialog) active);
            }
            for (final Window window : Window.getWindows()) {
                if (!(window instanceof Dialog dialog) || !dialog.isVisible()) {
                    continue;
                }
                if (!dialog.isModal() && window != active) {
                    continue;
                }
                if (!matches(window, matcher)) {
                    continue;
                }
                return stateOf(dialog);
            }
            return null;
        });
    }

    private static boolean matches(final Window window, final HostDialogMatcher matcher) {
        final Optional<String> prefix = matcher.windowClassPrefix();
        if (prefix.isPresent() && !window.getClass().getName().startsWith(prefix.get())) {
            return false;
        }
        final Optional<Integer> expectedOptionType = matcher.optionType();
        if (expectedOptionType.isPresent()) {
            final Optional<Integer> actualOptionType = optionTypeOf(window);
            if (actualOptionType.isEmpty() || !actualOptionType.get().equals(expectedOptionType.get())) {
                return false;
            }
        }
        return true;
    }

    private static Optional<Integer> optionTypeOf(final Window window) {
        final JOptionPane optionPane = findOptionPane(window);
        return optionPane == null ? Optional.empty() : Optional.of(optionPane.getOptionType());
    }

    /** Step 3: apply the semantic action on the EDT; unique match clicks, ambiguity fails closed. */
    private static HostDialogOutcome trigger(
        final DialogState state,
        final HostDialogAction action
    ) throws DialogAutomationFailure {
        return onHostThread(() -> {
            if (action == HostDialogAction.CLOSE) {
                state.dialog().dispatchEvent(new WindowEvent(state.dialog(), WindowEvent.WINDOW_CLOSING));
                return HostDialogOutcome.ACTED;
            }
            final List<JButton> matches = state.buttons().stream()
                .filter(button -> isActionButton(button, action))
                .toList();
            if (matches.size() > 1) {
                return HostDialogOutcome.AMBIGUOUS;
            }
            if (matches.isEmpty()) {
                return HostDialogOutcome.UNSUPPORTED;
            }
            matches.get(0).doClick();
            return HostDialogOutcome.ACTED;
        });
    }

    /** Multilingual semantic match over text/actionCommand/name/accessibleName. */
    private static boolean isActionButton(final JButton button, final HostDialogAction action) {
        return matchesActionValue(button.getActionCommand(), action)
            || matchesActionValue(button.getName(), action)
            || matchesActionValue(button.getText(), action)
            || matchesActionValue(accessibleName(button), action);
    }

    private static boolean matchesActionValue(final String value, final HostDialogAction action) {
        if (value == null || value.isBlank()) {
            return false;
        }
        // Cubism localizes confirmation buttons with a mnemonic suffix, e.g. "Cancel(C)",
        // "No(N)", "Yes(Y)", "OK(&O)": strip the trailing mnemonic before normalizing so the
        // plain word still matches the exact vocabulary.
        final String stripped = value.strip()
            .replaceFirst("\\s*\\(\\s*[_&]?\\p{L}\\s*\\)\\s*$", "");
        if (matchesMnemonicForm(value.strip(), MNEMONIC_LETTERS.get(action))) {
            return true;
        }
        final String normalized = stripped.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        if (EXACT_VALUES.get(action).contains(normalized)) {
            return true;
        }
        return CONTAINS_VALUES.get(action).stream().anyMatch(normalized::contains);
    }

    /** "No(N)"-style mnemonic suffix forms, e.g. no(_N) / no (n) / ok(&O). */
    private static boolean matchesMnemonicForm(final String raw, final String mnemonicLetter) {
        return raw.matches("(?i)" + mnemonicLetter + "\\s*\\(\\s*[_&]?" + mnemonicLetter + "\\s*\\)");
    }

    private static String accessibleName(final JButton button) {
        final var context = button.getAccessibleContext();
        return context == null ? null : context.getAccessibleName();
    }

    private static DialogState stateOf(final Dialog dialog) {
        return new DialogState(dialog, findOptionPane(dialog), visibleButtons(dialog));
    }

    private static List<Dialog> visibleDialogs() {
        final Window active =
            KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        final List<Dialog> dialogs = new ArrayList<>();
        for (final Window window : Window.getWindows()) {
            if (!(window instanceof Dialog dialog) || !dialog.isVisible()) {
                continue;
            }
            if (dialog.isModal() || window == active) {
                dialogs.add(dialog);
            }
        }
        return dialogs;
    }

    private static JOptionPane findOptionPane(final Component component) {
        if (component instanceof JOptionPane optionPane) {
            return optionPane;
        }
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) {
                final JOptionPane found = findOptionPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<JButton> visibleButtons(final Component component) {
        final List<JButton> buttons = new ArrayList<>();
        collectButtons(component, buttons);
        return buttons.stream()
            .filter(button -> button.isVisible() && button.isEnabled())
            .toList();
    }

    private static void collectButtons(final Component component, final List<JButton> buttons) {
        if (component instanceof JButton button) {
            buttons.add(button);
        }
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) {
                collectButtons(child, buttons);
            }
        }
    }

    private static boolean dialogClosed(final Dialog dialog) throws DialogAutomationFailure {
        return onHostThread(() -> !dialog.isDisplayable() || !dialog.isVisible());
    }

    /** EDT execution with a bounded acceptance wait (port of the probe's onHostThread). */
    private static <T> T onHostThread(final java.util.concurrent.Callable<T> call)
        throws DialogAutomationFailure {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return call.call();
            } catch (DialogAutomationFailure failure) {
                throw failure;
            } catch (Exception exception) {
                throw new DialogAutomationFailure(exception.getMessage(), exception);
            }
        }
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(call.call());
            } catch (Exception exception) {
                failure.set(exception);
            } finally {
                completed.countDown();
            }
        });
        try {
            if (!completed.await(EDT_ACCEPT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new DialogAutomationFailure(
                    "host EDT did not accept the dialog automation call within 5 seconds", null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DialogAutomationFailure("interrupted while waiting for the host EDT", interrupted);
        }
        if (failure.get() != null) {
            throw new DialogAutomationFailure(failure.get().getMessage(), failure.get());
        }
        return result.get();
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Internal flow failure that act converts to a fail-closed outcome. */
    private static final class DialogAutomationFailure extends RuntimeException {
        DialogAutomationFailure(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    private record DialogState(
        Dialog dialog,
        JOptionPane optionPane,
        List<JButton> buttons
    ) {
        private DialogState {
            buttons = List.copyOf(buttons);
        }
    }
}
