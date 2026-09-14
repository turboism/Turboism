package dev.turboism.tests.plugin;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Dialog;
import java.awt.KeyboardFocusManager;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Bounded, opt-in normal-close path for the native history UI probe.
 *
 * <p>This helper deliberately owns no process-exit fallback. It can request the same
 * version-specific close gesture used by the other Windows validation probes, and it can click
 * only one unambiguous discard action in a confirmation dialog owned by the selected host window.
 * A caller must have already persisted its terminal evidence before invoking it.</p>
 */
final class WindowsHistoryNativeUiHostClose {

    static final String RUN_ID_PROPERTY = "turboism.validation.runId";
    static final String HOST_VERSION_PROPERTY = "turboism.validation.hostVersion";

    private static final long CLOSE_TIMEOUT_MILLIS = 30_000L;
    private static final long FOCUS_TIMEOUT_MILLIS = 3_000L;
    private static final long POLL_MILLIS = 100L;
    private static final long EDT_TIMEOUT_MILLIS = 5_000L;
    private static final Pattern TASK_RUN_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private WindowsHistoryNativeUiHostClose() {
    }

    /** The host gesture that is known to work for one exact Cubism version family. */
    enum HostCloseRoute {
        SYNTHETIC_WINDOW_CLOSING,
        ROBOT_ALT_F4
    }

    /** Why an unsaved confirmation was or was not acted on. */
    enum HostCloseDecision {
        CLEAN_CLOSE,
        DISCARD,
        UNSUPPORTED_CONFIRMATION
    }

    /** The observable result of a close request; failures throw with diagnostics. */
    enum CloseStatus {
        SKIPPED,
        CLEAN_CLOSE,
        DISCARDED
    }

    record CloseResult(CloseStatus status, String reason) {
        CloseResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The pre-UI gate is pure so its safety boundary can be tested without a live desktop. */
    record CloseEligibility(boolean eligible, HostCloseRoute route, String reason) {
        CloseEligibility {
            Objects.requireNonNull(reason, "reason");
            if (eligible && route == null) {
                throw new IllegalArgumentException("an eligible close must have a route");
            }
            if (!eligible && route != null) {
                throw new IllegalArgumentException("a skipped close must not have a route");
            }
        }
    }

    /**
     * Returns whether the caller has a task-scoped identity strong enough to permit a close.
     *
     * <p>The native UI catalog currently runs on 5302, but the 5203 synthetic route remains here
     * because it is an existing, reviewed route. No newer version is admitted by this helper.</p>
     */
    static CloseEligibility eligibility(
        final boolean automate,
        final boolean probeRunning,
        final boolean terminalSummaryWritten,
        final String runId,
        final String hostVersion
    ) {
        if (!automate) return skipped("automation-disabled");
        if (!probeRunning) return skipped("probe-not-running");
        if (!terminalSummaryWritten) return skipped("terminal-summary-not-written");
        if (!isTaskRunId(runId)) return skipped("missing-or-invalid-task-run-id");
        if (hostVersion == null || hostVersion.isBlank()) {
            return skipped("missing-host-version");
        }
        try {
            return new CloseEligibility(true, hostCloseRoute(hostVersion), "eligible");
        } catch (IllegalArgumentException unsupported) {
            return skipped("unsupported-host-version:" + diagnostic(hostVersion));
        }
    }

    /** Alias for callers that only need the boolean gate. */
    static boolean isEligible(
        final boolean automate,
        final boolean probeRunning,
        final boolean terminalSummaryWritten,
        final String runId,
        final String hostVersion
    ) {
        return eligibility(
            automate, probeRunning, terminalSummaryWritten, runId, hostVersion
        ).eligible();
    }

    /**
     * Requests a normal UI close only after every precondition has passed.
     *
     * <p>This method must run off the EDT. The worker waits there while the host EDT handles a
     * close event or a nested confirmation dialog; blocking the EDT itself would prevent that
     * event from being processed.</p>
     */
    static CloseResult closeIfEligible(
        final boolean automate,
        final boolean probeRunning,
        final boolean terminalSummaryWritten,
        final String runId,
        final String hostVersion
    ) throws Exception {
        final CloseEligibility gate = eligibility(
            automate, probeRunning, terminalSummaryWritten, runId, hostVersion
        );
        if (!gate.eligible()) {
            return new CloseResult(CloseStatus.SKIPPED, gate.reason());
        }
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                "automated native UI host close must run off the EDT"
            );
        }

        final Window target = onHostThread(
            () -> selectHostWindow(Window.getWindows())
        );
        final DialogScan preexisting = visibleDialogs(target);
        if (!preexisting.owned().isEmpty() || !preexisting.foreign().isEmpty()) {
            throw new IllegalStateException(
                "Cannot start host close while a modal is already visible: owned="
                    + preexisting.owned().stream().map(CloseDialogState::description).toList()
                    + " foreign=" + describeWindows(preexisting.foreign())
            );
        }
        triggerClose(target, gate.route());
        final HostCloseDecision decision = awaitHostCloseConfirmation(target);
        return decision == HostCloseDecision.DISCARD
            ? new CloseResult(CloseStatus.DISCARDED, "unsaved-changes-discarded")
            : new CloseResult(CloseStatus.CLEAN_CLOSE, "host-window-closed-without-confirmation");
    }

    /** Selects the largest visible, displayable non-dialog window in this JVM. */
    static Window selectHostWindow(final Window[] windows) {
        Objects.requireNonNull(windows, "windows");
        Window target = null;
        long largestArea = -1L;
        for (final Window window : windows) {
            if (window == null
                || window instanceof Dialog
                || !window.isDisplayable()
                || !window.isVisible()) {
                continue;
            }
            final long area = (long) window.getWidth() * window.getHeight();
            if (area > largestArea) {
                target = window;
                largestArea = area;
            }
        }
        if (target == null) {
            throw new IllegalStateException(
                "No visible, displayable non-dialog Cubism/model window found"
            );
        }
        return target;
    }

    /** Keeps the existing exact-version route: synthetic close is a 5203-only quirk. */
    static HostCloseRoute hostCloseRoute(final String hostVersion) {
        if (hostVersion == null) {
            throw new IllegalArgumentException(
                "turboism.validation.hostVersion must be 5203 or 5302"
            );
        }
        return switch (hostVersion) {
            case "5203" -> HostCloseRoute.SYNTHETIC_WINDOW_CLOSING;
            case "5302" -> HostCloseRoute.ROBOT_ALT_F4;
            default -> throw new IllegalArgumentException(
                "turboism.validation.hostVersion must be 5203 or 5302: " + hostVersion
            );
        };
    }

    /** Pure shape check copied from the reviewed close flow; labels are checked separately. */
    static HostCloseDecision hostCloseDecision(
        final boolean confirmationVisible,
        final int optionType,
        final int enabledButtonCount
    ) {
        if (!confirmationVisible) return HostCloseDecision.CLEAN_CLOSE;
        if (optionType == JOptionPane.YES_NO_OPTION
            || optionType == JOptionPane.YES_NO_CANCEL_OPTION) {
            return HostCloseDecision.DISCARD;
        }
        return optionType == JOptionPane.DEFAULT_OPTION
                && (enabledButtonCount == 2 || enabledButtonCount == 3)
            ? HostCloseDecision.DISCARD
            : HostCloseDecision.UNSUPPORTED_CONFIRMATION;
    }

    /** Selects exactly one semantic discard action; ambiguity fails closed. */
    static JButton selectDiscardButton(final List<JButton> buttons) {
        Objects.requireNonNull(buttons, "buttons");
        final List<JButton> matches = buttons.stream()
            .filter(WindowsHistoryNativeUiHostClose::isDiscardAction)
            .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * Waits for the selected host to disappear, handling only its own explicit unsaved dialog.
     * Unknown and foreign dialogs cause a diagnostic failure without any button click.
     */
    static HostCloseDecision awaitHostCloseConfirmation(final Window hostWindow) throws Exception {
        Objects.requireNonNull(hostWindow, "hostWindow");
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                "host close confirmation wait must run off the EDT"
            );
        }

        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
            CLOSE_TIMEOUT_MILLIS
        );
        CloseDialogState observed = null;
        boolean discarded = false;
        while (System.nanoTime() < deadlineNanos) {
            if (hostWindowClosed(hostWindow)) {
                return discarded
                    ? HostCloseDecision.DISCARD
                    : HostCloseDecision.CLEAN_CLOSE;
            }

            final DialogScan scan = visibleDialogs(hostWindow);
            if (!scan.foreign().isEmpty()) {
                throw new IllegalStateException(
                    "Refusing to guess through a modal not owned by the selected host: "
                        + describeWindows(scan.foreign())
                );
            }
            if (scan.owned().size() > 1) {
                throw new IllegalStateException(
                    "More than one host-owned modal is visible; close is ambiguous: "
                        + scan.owned().stream().map(CloseDialogState::description).toList()
                );
            }
            if (scan.owned().size() == 1) {
                if (discarded) {
                    throw new IllegalStateException(
                        "Host confirmation remained visible after its discard action: "
                            + scan.owned().get(0).description()
                    );
                }
                observed = scan.owned().get(0);
                handleCloseDialog(observed);
                discarded = true;
                continue;
            }
            Thread.sleep(POLL_MILLIS);
        }

        if (hostWindowClosed(hostWindow)) {
            return discarded
                ? HostCloseDecision.DISCARD
                : HostCloseDecision.CLEAN_CLOSE;
        }
        throw new IllegalStateException(
            "Host window remained open after the bounded close wait"
                + " discarded=" + discarded
                + (observed == null ? "" : " lastDialog=" + observed.description())
        );
    }

    private static void triggerClose(
        final Window target,
        final HostCloseRoute route
    ) throws Exception {
        if (route == HostCloseRoute.ROBOT_ALT_F4) {
            focusHostWindow(target);
            pressAltF4(new Robot());
            return;
        }
        // Cubism 5.2 can enter its modal save loop synchronously from WINDOW_CLOSING. Queue the
        // event without waiting for dispatchEvent to return; the worker below observes the modal.
        SwingUtilities.invokeLater(() -> {
            if (isLiveHostWindow(target)) {
                target.dispatchEvent(new WindowEvent(target, WindowEvent.WINDOW_CLOSING));
            }
        });
    }

    private static void focusHostWindow(final Window target) throws Exception {
        onHostThread(() -> {
            if (!isLiveHostWindow(target)) {
                throw new IllegalStateException("selected host window is no longer live");
            }
            if (target instanceof java.awt.Frame frame) {
                frame.setState(java.awt.Frame.NORMAL);
            }
            target.toFront();
            target.requestFocus();
            return null;
        });

        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
            FOCUS_TIMEOUT_MILLIS
        );
        while (System.nanoTime() < deadlineNanos) {
            final boolean focused = onHostThread(
                () -> isLiveHostWindow(target)
                    && KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()
                        == target
            );
            if (focused) return;
            Thread.sleep(POLL_MILLIS);
        }
        throw new IllegalStateException(
            "Selected Cubism/model window did not become the active focused window"
        );
    }

    private static void pressAltF4(final Robot robot) {
        robot.keyPress(KeyEvent.VK_ALT);
        try {
            robot.keyPress(KeyEvent.VK_F4);
        } finally {
            try {
                robot.keyRelease(KeyEvent.VK_F4);
            } finally {
                robot.keyRelease(KeyEvent.VK_ALT);
            }
        }
    }

    private static void handleCloseDialog(final CloseDialogState state) throws Exception {
        final JOptionPane optionPane = state.optionPane();
        if (optionPane == null) {
            throw new IllegalStateException(
                "Host-owned modal is not an inspectable confirmation: " + state.description()
            );
        }
        final HostCloseDecision shape = hostCloseDecision(
            true, optionPane.getOptionType(), state.buttons().size()
        );
        if (shape != HostCloseDecision.DISCARD) {
            throw new IllegalStateException(
                "Host-owned confirmation shape is unsupported: " + state.description()
            );
        }
        final JButton discard = selectDiscardButton(state.buttons());
        if (discard == null) {
            throw new IllegalStateException(
                "Host-owned confirmation has no single semantic discard action: "
                    + state.description()
            );
        }
        onHostThread(() -> {
            if (!state.dialog().isVisible() || !discard.isVisible() || !discard.isEnabled()) {
                throw new IllegalStateException(
                    "Host-owned discard action became unavailable: " + state.description()
                );
            }
            discard.doClick();
            return null;
        });
    }

    private static DialogScan visibleDialogs(final Window hostWindow) throws Exception {
        return onHostThread(() -> {
            final Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .getActiveWindow();
            final List<CloseDialogState> owned = new ArrayList<>();
            final List<Window> foreign = new ArrayList<>();
            for (final Window window : Window.getWindows()) {
                if (!(window instanceof Dialog dialog)
                    || !dialog.isVisible()
                    || (!dialog.isModal() && window != active)) {
                    continue;
                }
                if (isOwnedBy(window, hostWindow)) {
                    owned.add(new CloseDialogState(
                        dialog,
                        findOptionPane(window),
                        visibleButtons(window)
                    ));
                } else {
                    foreign.add(window);
                }
            }
            return new DialogScan(List.copyOf(owned), List.copyOf(foreign));
        });
    }

    private static boolean isOwnedBy(final Window child, final Window owner) {
        final Set<Window> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Window current = child.getOwner();
        while (current != null && seen.add(current)) {
            if (current == owner) return true;
            current = current.getOwner();
        }
        return false;
    }

    private static boolean hostWindowClosed(final Window window) throws Exception {
        return onHostThread(() -> !window.isDisplayable() || !window.isVisible());
    }

    private static boolean isLiveHostWindow(final Window window) {
        return window.isDisplayable() && window.isVisible() && !(window instanceof Dialog);
    }

    private static JOptionPane findOptionPane(final java.awt.Component component) {
        if (component instanceof JOptionPane optionPane) return optionPane;
        if (component instanceof java.awt.Container container) {
            for (final java.awt.Component child : container.getComponents()) {
                final JOptionPane found = findOptionPane(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<JButton> visibleButtons(final java.awt.Component component) {
        final List<JButton> buttons = new ArrayList<>();
        collectButtons(component, buttons);
        return buttons.stream()
            .filter(button -> button.isVisible() && button.isEnabled())
            .toList();
    }

    private static void collectButtons(
        final java.awt.Component component,
        final List<JButton> buttons
    ) {
        if (component instanceof JButton button) buttons.add(button);
        if (component instanceof java.awt.Container container) {
            for (final java.awt.Component child : container.getComponents()) {
                collectButtons(child, buttons);
            }
        }
    }

    private static boolean isDiscardAction(final JButton button) {
        return matchesDiscardValue(button.getActionCommand())
            || matchesDiscardValue(button.getName())
            || matchesDiscardValue(button.getText())
            || matchesDiscardValue(accessibleName(button))
            || matchesNoButtonValue(button.getActionCommand())
            || matchesNoButtonValue(button.getName())
            || matchesNoButtonValue(button.getText())
            || matchesNoButtonValue(accessibleName(button));
    }

    private static String accessibleName(final JButton button) {
        final var context = button.getAccessibleContext();
        return context == null ? null : context.getAccessibleName();
    }

    private static boolean matchesDiscardValue(final String value) {
        if (value == null || value.isBlank()) return false;
        final String normalized = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]", "");
        return normalized.equals("no")
            || normalized.contains("discard")
            || normalized.contains("dontsave")
            || normalized.contains("donotsave")
            || normalized.contains("nosave")
            || normalized.contains("notsave")
            || normalized.equals("否")
            || normalized.contains("不保存")
            || normalized.contains("不要保存")
            || normalized.contains("不儲存")
            || normalized.contains("不要儲存")
            || normalized.contains("不存檔")
            || normalized.contains("不要存檔")
            || normalized.contains("放弃")
            || normalized.contains("放棄")
            || normalized.contains("舍弃")
            || normalized.contains("捨棄")
            || normalized.contains("保存しない")
            || normalized.contains("セーブしない");
    }

    private static boolean matchesNoButtonValue(final String value) {
        return value != null
            && value.strip().matches("(?i)no\\s*\\(\\s*[_&]?n\\s*\\)");
    }

    private static String buttonMetadata(final JButton button) {
        return "{class=" + button.getClass().getName()
            + ", text=" + diagnostic(button.getText())
            + ", action=" + diagnostic(button.getActionCommand())
            + ", name=" + diagnostic(button.getName())
            + ", accessible=" + diagnostic(accessibleName(button)) + '}';
    }

    private static boolean isTaskRunId(final String runId) {
        return runId != null
            && !runId.isBlank()
            && !"unknown".equalsIgnoreCase(runId)
            && TASK_RUN_ID.matcher(runId).matches();
    }

    private static CloseEligibility skipped(final String reason) {
        return new CloseEligibility(false, null, reason);
    }

    private static String describeWindows(final List<Window> windows) {
        return windows.stream()
            .map(window -> window.getClass().getName())
            .toList()
            .toString();
    }

    private static String diagnostic(final String value) {
        if (value == null) return "";
        final String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 256 ? singleLine : singleLine.substring(0, 256) + "…";
    }

    private static <T> T onHostThread(final Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(call.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(EDT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(
                "Cubism EDT did not complete the bounded host-close operation"
            );
        }
        final Throwable thrown = failure.get();
        if (thrown instanceof Exception exception) throw exception;
        if (thrown instanceof Error error) throw error;
        if (thrown != null) {
            throw new IllegalStateException("host-close EDT operation failed", thrown);
        }
        return result.get();
    }

    private record CloseDialogState(
        Dialog dialog,
        JOptionPane optionPane,
        List<JButton> buttons
    ) {
        String description() {
            return "window=" + dialog.getClass().getName()
                + " optionType=" + (optionPane == null ? "none" : optionPane.getOptionType())
                + " buttonMetadata=" + buttons.stream()
                    .map(WindowsHistoryNativeUiHostClose::buttonMetadata)
                    .toList();
        }
    }

    private record DialogScan(List<CloseDialogState> owned, List<Window> foreign) {
    }
}
