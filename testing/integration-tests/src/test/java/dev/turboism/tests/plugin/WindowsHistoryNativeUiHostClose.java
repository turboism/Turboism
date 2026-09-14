package dev.turboism.tests.plugin;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.text.Normalizer;
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
    static final String FIXTURE_NAME_PROPERTY = "turboism.validation.fixtureName";

    private static final long CLOSE_TIMEOUT_MILLIS = 30_000L;
    private static final long FOCUS_TIMEOUT_MILLIS = 3_000L;
    private static final long POLL_MILLIS = 100L;
    private static final long EDT_TIMEOUT_MILLIS = 5_000L;
    private static final Pattern TASK_RUN_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern SAFE_FIXTURE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,255}");

    /** Exact normalized labels only; phrases such as {@code Do not discard} are not included. */
    private static final Set<String> DISCARD_LABELS = Set.of(
        "no",
        "non",
        "dontsave",
        "donotsave",
        "nosave",
        "notsave",
        "discard",
        "否",
        "不保存",
        "不要保存",
        "不儲存",
        "不要儲存",
        "不存檔",
        "不要存檔",
        "放弃",
        "放棄",
        "舍弃",
        "捨棄",
        "いいえ",
        "保存しない",
        "セーブしない",
        "아니요",
        "저장안함",
        "버리기",
        "破棄"
    );

    /** Explicit save/unsaved prompt phrases, matched separately from the fixture name. */
    private static final Set<String> SAVE_PROMPT_PHRASES = Set.of(
        "save changes",
        "save the changes",
        "unsaved changes",
        "do you want to save",
        "would you like to save",
        "save changes before closing",
        "save before closing",
        "保存更改",
        "保存修改",
        "未保存的更改",
        "是否保存",
        "変更を保存",
        "未保存の変更",
        "保存しますか",
        "변경 사항을 저장",
        "변경사항을 저장",
        "저장하시겠습니까"
    );

    /** Negative prompt text is not a positive save-confirmation identity. */
    private static final Set<String> NEGATIVE_PROMPT_MARKERS = Set.of(
        "dontsave",
        "donotsave",
        "dontdiscard",
        "donotdiscard",
        "nosave",
        "notsave",
        "不保存",
        "不要保存",
        "不儲存",
        "不要儲存"
    );

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

    /** Immutable title/name evidence used by the pure window-candidate selector seam. */
    record HostWindowCandidate(String windowTitle, String windowName) {
        HostWindowCandidate {
            windowTitle = windowTitle == null ? "" : windowTitle;
            windowName = windowName == null ? "" : windowName;
        }

        String description() {
            return "{title=" + diagnostic(windowTitle) + ", name=" + diagnostic(windowName) + '}';
        }
    }

    /** Immutable button evidence; Swing widgets are never needed by the pure selector. */
    record ButtonSnapshot(
        String className,
        String text,
        String actionCommand,
        String name,
        String accessibleName,
        boolean visible,
        boolean enabled
    ) {
        String description() {
            return "{class=" + diagnostic(className)
                + ", text=" + diagnostic(text)
                + ", action=" + diagnostic(actionCommand)
                + ", name=" + diagnostic(name)
                + ", accessible=" + diagnostic(accessibleName)
                + ", visible=" + visible
                + ", enabled=" + enabled + '}';
        }
    }

    /** Immutable EDT snapshot used to identify and safely classify one host confirmation. */
    record CloseDialogSnapshot(
        String dialogClassName,
        String dialogTitle,
        String messageText,
        boolean optionPanePresent,
        int optionType,
        List<ButtonSnapshot> buttons
    ) {
        CloseDialogSnapshot {
            dialogClassName = dialogClassName == null ? "" : dialogClassName;
            dialogTitle = dialogTitle == null ? "" : dialogTitle;
            messageText = messageText == null ? "" : messageText;
            buttons = List.copyOf(buttons);
        }

        String description() {
            return "window=" + diagnostic(dialogClassName)
                + " title=" + diagnostic(dialogTitle)
                + " message=" + diagnostic(messageText)
                + " optionPane=" + optionPanePresent
                + " optionType=" + optionType
                + " buttonMetadata=" + buttons.stream().map(ButtonSnapshot::description).toList();
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
        return eligibility(
            automate,
            probeRunning,
            terminalSummaryWritten,
            runId,
            hostVersion,
            System.getProperty(FIXTURE_NAME_PROPERTY)
        );
    }

    /** Pure gate variant with the runner-provided fixture name supplied explicitly for tests. */
    static CloseEligibility eligibility(
        final boolean automate,
        final boolean probeRunning,
        final boolean terminalSummaryWritten,
        final String runId,
        final String hostVersion,
        final String fixtureName
    ) {
        if (!automate) return skipped("automation-disabled");
        if (!probeRunning) return skipped("probe-not-running");
        if (!terminalSummaryWritten) return skipped("terminal-summary-not-written");
        if (!isTaskRunId(runId)) return skipped("missing-or-invalid-task-run-id");
        if (!isTaskFixtureName(runId, fixtureName)) {
            return skipped("missing-or-invalid-fixture-name");
        }
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

    /** Requests a normal UI close only after every precondition has passed. */
    static CloseResult closeIfEligible(
        final boolean automate,
        final boolean probeRunning,
        final boolean terminalSummaryWritten,
        final String runId,
        final String hostVersion
    ) throws Exception {
        final String fixtureName = System.getProperty(FIXTURE_NAME_PROPERTY);
        final CloseEligibility gate = eligibility(
            automate,
            probeRunning,
            terminalSummaryWritten,
            runId,
            hostVersion,
            fixtureName
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
            () -> selectHostWindow(Window.getWindows(), fixtureName)
        );
        final DialogScan preexisting = visibleDialogs(target);
        if (!preexisting.owned().isEmpty() || !preexisting.foreign().isEmpty()) {
            throw new IllegalStateException(
                "Cannot start host close while a modal is already visible: owned="
                    + preexisting.owned().stream().map(CloseDialogState::description).toList()
                    + " foreign=" + preexisting.foreign()
            );
        }
        triggerClose(target, gate.route());
        final HostCloseDecision decision = awaitHostCloseConfirmation(target, fixtureName);
        return decision == HostCloseDecision.DISCARD
            ? new CloseResult(CloseStatus.DISCARDED, "unsaved-changes-discarded")
            : new CloseResult(CloseStatus.CLEAN_CLOSE, "host-window-closed-without-confirmation");
    }

    /**
     * Selects exactly one visible, displayable Frame whose title or explicit window name
     * identifies the runner's copied fixture. There is deliberately no area or arbitrary-window
     * fallback.
     */
    static Window selectHostWindow(final Window[] windows) {
        return selectHostWindow(windows, System.getProperty(FIXTURE_NAME_PROPERTY));
    }

    /** Window inspection is an EDT operation; candidate matching itself is pure and testable. */
    static Window selectHostWindow(final Window[] windows, final String fixtureName) {
        Objects.requireNonNull(windows, "windows");
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("host window selection must run on the Cubism EDT");
        }
        final List<Window> visibleFrames = new ArrayList<>();
        final List<HostWindowCandidate> candidates = new ArrayList<>();
        for (final Window window : windows) {
            if (!(window instanceof Frame frame)
                || !frame.isDisplayable()
                || !frame.isVisible()) {
                continue;
            }
            visibleFrames.add(window);
            candidates.add(new HostWindowCandidate(frame.getTitle(), frame.getName()));
        }
        final int selected = selectHostWindowCandidate(candidates, fixtureName);
        return visibleFrames.get(selected);
    }

    /** Returns the unique matching candidate index, or fails closed on zero/multiple matches. */
    static int selectHostWindowCandidate(
        final List<HostWindowCandidate> candidates,
        final String fixtureName
    ) {
        Objects.requireNonNull(candidates, "candidates");
        if (!isSafeFixtureName(fixtureName)) {
            throw new IllegalStateException("runner fixture name is missing or unsafe");
        }
        final List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            final HostWindowCandidate candidate = Objects.requireNonNull(
                candidates.get(index), "candidate"
            );
            if (matchesFixtureText(candidate.windowTitle(), fixtureName)
                || matchesFixtureText(candidate.windowName(), fixtureName)) {
                matches.add(index);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                "Expected exactly one visible Cubism/model window for fixture "
                    + diagnostic(fixtureName)
                    + ", matches=" + matches.size()
                    + ", candidates=" + candidates.stream().map(HostWindowCandidate::description).toList()
            );
        }
        return matches.get(0);
    }

    /** Matches a complete fixture token in a model/window title with only known title decoration. */
    static boolean matchesFixtureText(final String value, final String fixtureName) {
        if (!isSafeFixtureName(fixtureName) || value == null || value.isBlank()) return false;
        final String title = normalizeTitle(value);
        final String fixture = normalizeTitle(fixtureName);
        if (title.equals(fixture)) return true;
        if (title.startsWith("*" + fixture)
            && validTitleSuffix(title.substring(fixture.length() + 1))) {
            return true;
        }
        if (title.startsWith(fixture)
            && validTitleSuffix(title.substring(fixture.length()))) {
            return true;
        }
        return title.endsWith(fixture)
            && validTitlePrefix(title.substring(0, title.length() - fixture.length()));
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

    /**
     * A visible confirmation is discardable only after the EDT snapshot proves both its task
     * fixture and an explicit save/unsaved prompt; shape alone is never sufficient.
     */
    static HostCloseDecision hostCloseDecision(
        final boolean confirmationVisible,
        final CloseDialogSnapshot snapshot,
        final String fixtureName
    ) {
        if (!confirmationVisible) return HostCloseDecision.CLEAN_CLOSE;
        return hostCloseDecision(snapshot, fixtureName);
    }

    /** Pure decision over immutable EDT evidence. */
    static HostCloseDecision hostCloseDecision(
        final CloseDialogSnapshot snapshot,
        final String fixtureName
    ) {
        if (snapshot == null
            || !snapshot.optionPanePresent()
            || !isSafeFixtureName(fixtureName)) {
            return HostCloseDecision.UNSUPPORTED_CONFIRMATION;
        }
        final String promptText = snapshot.dialogTitle() + " " + snapshot.messageText();
        if (!containsFixtureName(promptText, fixtureName)
            || !hasKnownSavePrompt(promptText)
            || !supportedConfirmationShape(snapshot.optionType(), snapshot.buttons().size())) {
            return HostCloseDecision.UNSUPPORTED_CONFIRMATION;
        }
        return selectDiscardButton(snapshot.buttons()) >= 0
            ? HostCloseDecision.DISCARD
            : HostCloseDecision.UNSUPPORTED_CONFIRMATION;
    }

    /** Selects exactly one semantic discard action from immutable EDT button evidence. */
    static int selectDiscardButton(final List<ButtonSnapshot> buttons) {
        Objects.requireNonNull(buttons, "buttons");
        final List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < buttons.size(); index++) {
            final ButtonSnapshot button = Objects.requireNonNull(buttons.get(index), "button");
            if (button.visible() && button.enabled() && isDiscardAction(button)) {
                matches.add(index);
            }
        }
        return matches.size() == 1 ? matches.get(0) : -1;
    }

    /**
     * Waits for the selected host to disappear, handling only its own explicit task save dialog.
     * Unknown and foreign dialogs cause a diagnostic failure without any button click.
     */
    static HostCloseDecision awaitHostCloseConfirmation(final Window hostWindow) throws Exception {
        return awaitHostCloseConfirmation(
            hostWindow,
            System.getProperty(FIXTURE_NAME_PROPERTY)
        );
    }

    private static HostCloseDecision awaitHostCloseConfirmation(
        final Window hostWindow,
        final String fixtureName
    ) throws Exception {
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
                        + scan.foreign()
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
                final HostCloseDecision decision = handleCloseDialog(observed, fixtureName);
                if (decision != HostCloseDecision.DISCARD) {
                    throw new IllegalStateException(
                        "Host-owned confirmation was not a task save prompt: "
                            + observed.description()
                    );
                }
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
            if (target instanceof Frame frame) {
                frame.setState(Frame.NORMAL);
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

    /** All Swing reads, decision, final validation and click happen inside this EDT call. */
    private static HostCloseDecision handleCloseDialog(
        final CloseDialogState observed,
        final String fixtureName
    ) throws Exception {
        return onHostThread(() -> {
            if (!observed.dialog().isDisplayable() || !observed.dialog().isVisible()) {
                throw new IllegalStateException(
                    "Host-owned confirmation disappeared before it could be inspected: "
                        + observed.description()
                );
            }
            // Re-snapshot on the EDT immediately before acting. The worker carries only the
            // immutable observation and an opaque dialog handle; it never reads Swing properties.
            final CloseDialogState current = snapshotCloseDialog(observed.dialog());
            final HostCloseDecision decision = hostCloseDecision(
                true, current.snapshot(), fixtureName
            );
            if (decision != HostCloseDecision.DISCARD) {
                throw new IllegalStateException(
                    "Host-owned confirmation is unsupported or not for this task: "
                        + current.description()
                );
            }
            final int discardIndex = selectDiscardButton(current.snapshot().buttons());
            final List<JButton> liveButtons = visibleButtons(current.dialog());
            if (discardIndex < 0 || discardIndex >= liveButtons.size()) {
                throw new IllegalStateException(
                    "Host-owned confirmation has no single semantic discard action: "
                        + current.description()
                );
            }
            final JButton discard = liveButtons.get(discardIndex);
            final ButtonSnapshot expected = current.snapshot().buttons().get(discardIndex);
            final ButtonSnapshot finalState = snapshotButton(discard);
            if (!discard.isVisible() || !discard.isEnabled() || !finalState.equals(expected)) {
                throw new IllegalStateException(
                    "Host-owned discard action changed before click: " + current.description()
                );
            }
            discard.doClick();
            return decision;
        });
    }

    /** Creates immutable dialog state while running on the EDT. */
    private static CloseDialogState snapshotCloseDialog(final Dialog dialog) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("dialog snapshot must run on the Cubism EDT");
        }
        final JOptionPane optionPane = findOptionPane(dialog);
        final List<JButton> buttons = visibleButtons(dialog);
        return new CloseDialogState(
            dialog,
            new CloseDialogSnapshot(
                dialog.getClass().getName(),
                dialog.getTitle(),
                messageText(optionPane),
                optionPane != null,
                optionPane == null ? JOptionPane.DEFAULT_OPTION : optionPane.getOptionType(),
                buttons.stream().map(WindowsHistoryNativeUiHostClose::snapshotButton).toList()
            )
        );
    }

    private static DialogScan visibleDialogs(final Window hostWindow) throws Exception {
        return onHostThread(() -> {
            final Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .getActiveWindow();
            final List<CloseDialogState> owned = new ArrayList<>();
            final List<String> foreign = new ArrayList<>();
            for (final Window window : Window.getWindows()) {
                if (!(window instanceof Dialog dialog)
                    || !dialog.isVisible()
                    || (!dialog.isModal() && window != active)) {
                    continue;
                }
                if (isOwnedBy(window, hostWindow)) {
                    owned.add(snapshotCloseDialog(dialog));
                } else {
                    foreign.add(windowDescription(window));
                }
            }
            return new DialogScan(List.copyOf(owned), List.copyOf(foreign));
        });
    }

    private static String windowDescription(final Window window) {
        final String title = window instanceof Dialog dialog
            ? dialog.getTitle()
            : window instanceof Frame frame ? frame.getTitle() : "";
        return "{class=" + window.getClass().getName() + ", title=" + diagnostic(title) + '}';
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
        return window.isDisplayable() && window.isVisible() && window instanceof Frame;
    }

    private static JOptionPane findOptionPane(final Component component) {
        if (component instanceof JOptionPane optionPane) return optionPane;
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) {
                final JOptionPane found = findOptionPane(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String messageText(final JOptionPane optionPane) {
        if (optionPane == null) return "";
        final List<String> values = new ArrayList<>();
        collectMessageText(optionPane.getMessage(), values);
        return String.join(" ", values);
    }

    private static void collectMessageText(final Object value, final List<String> values) {
        if (value == null) return;
        if (value instanceof Object[] array) {
            for (final Object element : array) collectMessageText(element, values);
            return;
        }
        if (value instanceof CharSequence text) {
            if (!text.toString().isBlank()) values.add(text.toString());
            return;
        }
        if (value instanceof JLabel label) {
            if (label.getText() != null && !label.getText().isBlank()) {
                values.add(label.getText());
            }
            return;
        }
        if (value instanceof JTextComponent textComponent) {
            if (!textComponent.getText().isBlank()) values.add(textComponent.getText());
            return;
        }
        if (value instanceof Container container) {
            for (final Component child : container.getComponents()) {
                collectMessageText(child, values);
            }
            return;
        }
        values.add(String.valueOf(value));
    }

    private static List<JButton> visibleButtons(final Component component) {
        final List<JButton> buttons = new ArrayList<>();
        collectButtons(component, buttons);
        return buttons.stream()
            .filter(button -> button.isVisible() && button.isEnabled())
            .toList();
    }

    private static void collectButtons(final Component component, final List<JButton> buttons) {
        if (component instanceof JButton button) buttons.add(button);
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) {
                collectButtons(child, buttons);
            }
        }
    }

    private static ButtonSnapshot snapshotButton(final JButton button) {
        final var context = button.getAccessibleContext();
        return new ButtonSnapshot(
            button.getClass().getName(),
            button.getText(),
            button.getActionCommand(),
            button.getName(),
            context == null ? null : context.getAccessibleName(),
            button.isVisible(),
            button.isEnabled()
        );
    }

    private static boolean isDiscardAction(final ButtonSnapshot button) {
        return matchesDiscardValue(button.actionCommand())
            || matchesDiscardValue(button.name())
            || matchesDiscardValue(button.text())
            || matchesDiscardValue(button.accessibleName());
    }

    private static boolean matchesDiscardValue(final String value) {
        return value != null && DISCARD_LABELS.contains(normalizeCompact(value));
    }

    private static boolean supportedConfirmationShape(
        final int optionType,
        final int buttonCount
    ) {
        return switch (optionType) {
            case JOptionPane.YES_NO_OPTION -> buttonCount == 2;
            case JOptionPane.YES_NO_CANCEL_OPTION -> buttonCount == 3;
            case JOptionPane.DEFAULT_OPTION -> buttonCount == 2 || buttonCount == 3;
            default -> false;
        };
    }

    private static boolean hasKnownSavePrompt(final String text) {
        final String normalized = normalizeWords(text);
        final String compact = normalizeCompact(text);
        if (NEGATIVE_PROMPT_MARKERS.stream().anyMatch(compact::contains)) return false;
        return SAVE_PROMPT_PHRASES.stream().anyMatch(phrase -> {
            final String expectedWords = normalizeWords(phrase);
            if (expectedWords.indexOf(' ') >= 0) {
                return containsWordPhrase(normalized, expectedWords);
            }
            return !expectedWords.isEmpty() && compact.contains(normalizeCompact(phrase));
        });
    }

    private static boolean containsWordPhrase(final String text, final String phrase) {
        if (text.equals(phrase)) return true;
        return (" " + text + " ").contains(" " + phrase + " ");
    }

    private static boolean containsFixtureName(final String text, final String fixtureName) {
        if (!isSafeFixtureName(fixtureName) || text == null || text.isBlank()) return false;
        final String haystack = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
        final String needle = normalizeTitle(fixtureName);
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            final int index = haystack.indexOf(needle, from);
            if (index < 0) return false;
            final int end = index + needle.length();
            final boolean before = index == 0 || isFixtureContinuation(haystack.charAt(index - 1));
            final boolean after = end == haystack.length() || isFixtureContinuation(haystack.charAt(end));
            if (!before && !after) return true;
            from = index + 1;
        }
        return false;
    }

    private static boolean isFixtureContinuation(final char value) {
        return Character.isLetterOrDigit(value) || value == '.' || value == '_' || value == '-';
    }

    private static boolean validTitleSuffix(final String suffix) {
        if (suffix == null || suffix.isBlank()) return true;
        final String value = suffix.strip();
        return value.equals("*")
            || value.startsWith("- ")
            || value.startsWith("— ")
            || value.startsWith("| ")
            || value.startsWith(": ")
            || value.startsWith("(")
            || value.startsWith("[");
    }

    private static boolean validTitlePrefix(final String prefix) {
        if (prefix == null || prefix.isBlank()) return true;
        final String value = prefix.stripTrailing();
        return value.endsWith("/")
            || value.endsWith("\\")
            || value.endsWith(" -")
            || value.endsWith(" —")
            || value.endsWith(" |")
            || value.endsWith(" :")
            || value.endsWith("(")
            || value.endsWith("[");
    }

    private static String normalizeTitle(final String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .strip()
            .toLowerCase(Locale.ROOT);
    }

    private static String normalizeWords(final String value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replace('&', ' ')
            .replace('’', '\'')
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .strip();
    }

    private static String normalizeCompact(final String value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static boolean isTaskRunId(final String runId) {
        return runId != null
            && !runId.isBlank()
            && !"unknown".equalsIgnoreCase(runId)
            && TASK_RUN_ID.matcher(runId).matches();
    }

    private static boolean isSafeFixtureName(final String fixtureName) {
        return fixtureName != null
            && !fixtureName.isBlank()
            && !".".equals(fixtureName)
            && !"..".equals(fixtureName)
            && SAFE_FIXTURE_NAME.matcher(fixtureName).matches();
    }

    private static boolean isTaskFixtureName(final String runId, final String fixtureName) {
        if (!isTaskRunId(runId) || !isSafeFixtureName(fixtureName)) return false;
        final String lowerRunId = runId.toLowerCase(Locale.ROOT);
        final String lowerFixture = fixtureName.toLowerCase(Locale.ROOT);
        return lowerFixture.equals(lowerRunId)
            || lowerFixture.startsWith(lowerRunId + ".")
            || lowerFixture.startsWith(lowerRunId + "-")
            || lowerFixture.startsWith(lowerRunId + "_");
    }

    private static CloseEligibility skipped(final String reason) {
        return new CloseEligibility(false, null, reason);
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
        CloseDialogSnapshot snapshot
    ) {
        String description() {
            return snapshot.description();
        }
    }

    private record DialogScan(List<CloseDialogState> owned, List<String> foreign) {
        DialogScan {
            owned = List.copyOf(owned);
            foreign = List.copyOf(foreign);
        }
    }
}
