package dev.turboism.validation.externalpsd;

import static dev.turboism.validation.externalpsd.DiagnosticObservationRecorder.elapsedMillis;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Bounded host window close coordination, including the task-owned save prompt answers.
 *
 * <p>Closing the host must not hang the probe and must not answer a prompt that belongs to
 * another window. Every close attempt is bounded, traced with byte-limited evidence, and
 * classified before a decision is applied.</p>
 */
final class HostCloseCoordinator {
    static final long HOST_CLOSE_TIMEOUT_MILLIS = 30_000L;
    static final long HOST_CLOSE_POLL_MILLIS = 100L;
    static final long HOST_CLOSE_EDT_CALL_TIMEOUT_MILLIS = 500L;

    static final AtomicLong CLOSE_SESSION_SEQUENCE = new AtomicLong();
    static final int CLOSE_DIALOG_MAX_COUNT = 32;
    static final int CLOSE_DIALOG_FIELD_MAX_BYTES = 128;
    static final int CLOSE_DIAGNOSTIC_MAX_BYTES = 8 * 1024;
    static final int CLOSE_TRACE_MAX_BYTES = 7168;
    static final int CLOSE_TRACE_BEFORE_MAX_BYTES = 1536;
    static final int CLOSE_TRACE_AFTER_MAX_BYTES = 1536;
    static final int CLOSE_TRACE_DISPATCH_MAX_BYTES = 1024;
    static final int CLOSE_TRACE_REASON_MAX_BYTES = 2048;
    static final String CLOSE_TRACE_SEPARATOR = "; ";
    static final int CLOSE_RESULT_REASON_MAX_BYTES = CLOSE_DIAGNOSTIC_MAX_BYTES
        - CLOSE_TRACE_MAX_BYTES - CLOSE_TRACE_SEPARATOR.getBytes(StandardCharsets.UTF_8).length;

    /**
     * Sends the host close event only to the exact task window retained by target resolution.
     * Keeping the value checks in this small helper makes the no-cross-window rule executable
     * offline without constructing a real AWT window in the headless focused test.
     */
    static CloseDispatchResult dispatchBoundWindowClose(final Window target) {
        if (!SwingUtilities.isEventDispatchThread()) {
            return CloseDispatchResult.rejected("bound host close must run on EDT");
        }
        if (target == null) return CloseDispatchResult.rejected("bound host window is unavailable");
        final String identity = GuiRowDispatch.componentIdentity(target);
        return dispatchBoundWindowClose(identity, identity, target.isShowing(),
            target.isDisplayable(),
            () -> target.dispatchEvent(new WindowEvent(target, WindowEvent.WINDOW_CLOSING)));
    }

    /** Test seam for the exact-window checks used by {@link #dispatchBoundWindowClose(Window)}. */
    static CloseDispatchResult dispatchBoundWindowCloseForTest(final String expectedIdentity,
        final String actualIdentity, final boolean showing, final boolean displayable,
        final Runnable dispatch) {
        return dispatchBoundWindowClose(expectedIdentity, actualIdentity, showing, displayable,
            dispatch);
    }

    static CloseDispatchResult dispatchBoundWindowClose(final String expectedIdentity,
        final String actualIdentity, final boolean showing, final boolean displayable,
        final Runnable dispatch) {
        if (expectedIdentity == null || expectedIdentity.isBlank()) {
            return CloseDispatchResult.rejected("bound host window identity is unavailable");
        }
        if (!expectedIdentity.equals(actualIdentity)) {
            return CloseDispatchResult.rejected("close target identity changed from "
                + expectedIdentity + " to " + actualIdentity);
        }
        if (!showing || !displayable) {
            return CloseDispatchResult.rejected("bound host window is not showing/displayable: "
                + actualIdentity);
        }
        if (dispatch == null) return CloseDispatchResult.rejected("close dispatch is unavailable");
        try {
            dispatch.run();
            return CloseDispatchResult.dispatched(
                "event=WINDOW_CLOSING target=" + actualIdentity);
        } catch (Throwable failure) {
            return CloseDispatchResult.rejected("event=WINDOW_CLOSING target=" + actualIdentity
                + " dispatch failed: " + GuiRowDispatch.stackTrace(failure));
        }
    }

    /**
     * Posts WINDOW_CLOSING and observes the modal save path without synchronously waiting for the
     * event handler.  Every Swing read/action in this routine is scheduled on the EDT; the worker
     * waits only on bounded latches, so a host modal loop or native stall remains diagnosable.
     */
    static HostCloseResult closeBoundHostWindow(final Window target) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return HostCloseResult.rejected("host close coordinator must run off EDT");
        }
        final CloseSessionTrace trace = new CloseSessionTrace();
        final AtomicBoolean coordinatorActive = new AtomicBoolean(true);
        final AtomicBoolean noActionClaimed = new AtomicBoolean();
        final AtomicBoolean noActionSucceeded = new AtomicBoolean();
        final AtomicBoolean noActionFailed = new AtomicBoolean();
        final long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(HOST_CLOSE_TIMEOUT_MILLIS);
        try {
            final AtomicReference<CloseDispatchResult> dispatch = new AtomicReference<>();
            final ClosePreparation<List<CloseDialogSnapshot>> preparation =
                prepareCloseAfterSnapshot(
                    () -> visibleDialogsOnEdt(target),
                    () -> {
                        trace.recordDispatchAttempt();
                        final CloseDispatchResult dispatched = dispatchBoundWindowClose(target);
                        trace.recordDispatch(dispatched);
                        dispatch.set(dispatched);
                    },
                    deadline, coordinatorActive);
            final EdtCall<List<CloseDialogSnapshot>> beforeCall = preparation.beforeCall();
            if (!beforeCall.completed()) {
                return withCloseTrace(trace, HostCloseResult.timeout(
                    "could not snapshot pre-close dialogs on EDT"));
            }
            if (beforeCall.failure() != null) {
                return withCloseTrace(trace, HostCloseResult.rejected(
                    "pre-close dialog snapshot failed: " + GuiRowDispatch.stackTrace(beforeCall.failure())));
            }
            final List<CloseDialogSnapshot> beforeDialogs = beforeCall.value();
            if (beforeDialogs == null) {
                return withCloseTrace(trace, HostCloseResult.rejected(
                    "pre-close dialog snapshot was unavailable"));
            }
            trace.recordBefore(beforeDialogs);
            if (!preparation.dispatchPosted()) {
                final Throwable dispatchFailure = preparation.dispatchFailure().get();
                if (dispatchFailure == null) {
                    return withCloseTrace(trace, HostCloseResult.timeout(
                        "close deadline/stop reached before WINDOW_CLOSING dispatch"));
                }
                return withCloseTrace(trace, HostCloseResult.rejected(
                    "WINDOW_CLOSING could not be posted: " + GuiRowDispatch.stackTrace(dispatchFailure)));
            }
            String lastDiagnostic = "WINDOW_CLOSING has not reached the EDT";
            while (coordinatorActive.get() && !Thread.currentThread().isInterrupted()
                && System.nanoTime() < deadline) {
                final CloseDispatchResult dispatched = dispatch.get();
                if (dispatched != null && !dispatched.dispatched()) {
                    return withCloseTrace(trace,
                        HostCloseResult.rejected(dispatched.diagnostic()));
                }
                if (preparation.dispatchFailure().get() != null) {
                    return withCloseTrace(trace, HostCloseResult.rejected(
                        "WINDOW_CLOSING dispatch failed: "
                            + GuiRowDispatch.stackTrace(preparation.dispatchFailure().get())));
                }
                if (!preparation.dispatchStarted().get()) {
                    lastDiagnostic = "WINDOW_CLOSING is queued on the EDT";
                } else {
                    final long observationMillis = Math.min(
                        HOST_CLOSE_EDT_CALL_TIMEOUT_MILLIS,
                        remainingCloseMillis(deadline));
                    if (observationMillis <= 0) break;
                    final EdtCall<CloseDialogInspection> inspection = invokeEdtBounded(
                        () -> inspectAndMaybeDismissCloseDialog(target, beforeDialogs,
                            coordinatorActive, noActionClaimed, noActionSucceeded,
                            noActionFailed, trace),
                        observationMillis);
                    if (!inspection.completed()) {
                        lastDiagnostic = "EDT close observation timed out";
                    } else if (inspection.failure() != null) {
                        return withCloseTrace(trace, HostCloseResult.rejected(
                            "close observation failed: " + GuiRowDispatch.stackTrace(inspection.failure())));
                    } else if (inspection.value() != null) {
                        final CloseDialogInspection observed = inspection.value();
                        lastDiagnostic = observed.diagnostic();
                        if (observed.rejected()) {
                            return withCloseTrace(trace,
                                HostCloseResult.rejected(lastDiagnostic));
                        }
                        if (observed.targetGone()) {
                            if (dispatched == null || !dispatched.dispatched()) {
                                lastDiagnostic = "bound host window disappeared before "
                                    + "WINDOW_CLOSING dispatch completed";
                                continue;
                            }
                            return withCloseTrace(trace, HostCloseResult.success(
                                noActionSucceeded.get()
                                    ? "native close completed after exact dirty-save NO selection"
                                    : "native close completed with no dirty-save dialog"));
                        }
                    }
                }
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Thread.sleep(Math.min(HOST_CLOSE_POLL_MILLIS,
                    Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))));
            }
            return withCloseTrace(trace, HostCloseResult.timeout(
                "native close did not complete before timeout; last=" + lastDiagnostic));
        } finally {
            // Timed-out invokeLater inspections must not act after Supervisor cleanup has taken
            // ownership of the host.
            coordinatorActive.set(false);
        }
    }

    static <T> ClosePreparation<T> prepareCloseAfterSnapshot(
        final Callable<T> beforeOperation, final Runnable dispatchOperation,
        final long deadline, final AtomicBoolean coordinatorActive) throws InterruptedException {
        Objects.requireNonNull(beforeOperation, "beforeOperation");
        Objects.requireNonNull(dispatchOperation, "dispatchOperation");
        Objects.requireNonNull(coordinatorActive, "coordinatorActive");
        final long beforeMillis = remainingCloseMillis(deadline);
        if (!coordinatorActive.get() || beforeMillis <= 0) {
            coordinatorActive.set(false);
            return ClosePreparation.timeout();
        }
        final EdtCall<T> beforeCall = invokeEdtBounded(beforeOperation, beforeMillis);
        if (!beforeCall.completed() || beforeCall.failure() != null
            || !coordinatorActive.get() || System.nanoTime() >= deadline) {
            coordinatorActive.set(false);
            return new ClosePreparation<>(beforeCall, false, new AtomicBoolean(),
                new AtomicReference<>());
        }
        final AtomicBoolean dispatchStarted = new AtomicBoolean();
        final AtomicReference<Throwable> dispatchFailure = new AtomicReference<>();
        try {
            SwingUtilities.invokeLater(() -> {
                if (!coordinatorActive.get() || System.nanoTime() >= deadline) return;
                dispatchStarted.set(true);
                try {
                    dispatchOperation.run();
                } catch (Throwable failure) {
                    dispatchFailure.set(failure);
                }
            });
            return new ClosePreparation<>(beforeCall, true, dispatchStarted, dispatchFailure);
        } catch (RuntimeException failure) {
            dispatchFailure.set(failure);
            return new ClosePreparation<>(beforeCall, false, dispatchStarted, dispatchFailure);
        }
    }

    static long remainingCloseMillis(final long deadline) {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) return 0L;
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    /** Focused seam over the production close preflight/dispatch deadline coordinator. */
    static CloseTimingResult closeTimingForTest(final Callable<?> beforeOperation,
        final Runnable dispatchOperation, final long timeoutMillis,
        final BooleanSupplier stopped) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("close timing test must run off EDT");
        }
        Objects.requireNonNull(beforeOperation, "beforeOperation");
        Objects.requireNonNull(dispatchOperation, "dispatchOperation");
        Objects.requireNonNull(stopped, "stopped");
        if (timeoutMillis <= 0L) {
            return new CloseTimingResult(false, false, false, 0, true,
                stopped.getAsBoolean(), "close timing timeout is not positive");
        }
        if (stopped.getAsBoolean()) {
            return new CloseTimingResult(false, false, false, 0, false, true,
                "close timing was stopped before preflight");
        }
        final long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicInteger dispatchCalls = new AtomicInteger();
        final CountDownLatch dispatchDone = new CountDownLatch(1);
        final ClosePreparation<?> preparation;
        try {
            preparation = prepareCloseAfterSnapshot(beforeOperation, () -> {
                try {
                    if (!stopped.getAsBoolean()) {
                        dispatchCalls.incrementAndGet();
                        dispatchOperation.run();
                    }
                } finally {
                    dispatchDone.countDown();
                }
            }, deadline, active);
        } catch (InterruptedException interrupted) {
            active.set(false);
            throw interrupted;
        }
        if (!preparation.beforeCall().completed() || preparation.beforeCall().failure() != null
            || !preparation.dispatchPosted()) {
            active.set(false);
            final boolean deadlineReached = !preparation.beforeCall().completed()
                || System.nanoTime() >= deadline;
            return new CloseTimingResult(preparation.beforeCall().completed(),
                preparation.dispatchPosted(), preparation.dispatchStarted().get(),
                dispatchCalls.get(), deadlineReached, stopped.getAsBoolean(),
                "close preflight did not post WINDOW_CLOSING: "
                    + (preparation.beforeCall().failure() == null
                        ? "deadline or unavailable" : GuiRowDispatch.stackTrace(preparation.beforeCall().failure())));
        }
        while (active.get() && !stopped.getAsBoolean() && System.nanoTime() < deadline
            && dispatchDone.getCount() != 0L) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;
            dispatchDone.await(Math.min(remaining,
                TimeUnit.MILLISECONDS.toNanos(10L)), TimeUnit.NANOSECONDS);
        }
        final boolean deadlineReached = System.nanoTime() >= deadline;
        final boolean stoppedNow = stopped.getAsBoolean();
        active.set(false);
        return new CloseTimingResult(preparation.beforeCall().completed(),
            preparation.dispatchPosted(), preparation.dispatchStarted().get(),
            dispatchCalls.get(), deadlineReached, stoppedNow,
            deadlineReached ? "close deadline reached" : "close dispatch coordinator completed");
    }

    static <T> EdtCall<T> invokeEdtBounded(final Callable<T> operation,
        final long timeoutMillis) throws InterruptedException {
        Objects.requireNonNull(operation, "EDT operation");
        if (timeoutMillis <= 0) return EdtCall.timeout();
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return EdtCall.completed(operation.call());
            } catch (Throwable failure) {
                return EdtCall.failed(failure);
            }
        }
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    value.set(operation.call());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    done.countDown();
                }
            });
        } catch (RuntimeException error) {
            return EdtCall.failed(error);
        }
        if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) return EdtCall.timeout();
        return failure.get() == null
            ? EdtCall.completed(value.get()) : EdtCall.failed(failure.get());
    }

    static List<CloseDialogSnapshot> visibleDialogsOnEdt(final Window target) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("dialog discovery must run on EDT");
        }
        return visibleCloseDialogs(target);
    }

    static CloseDialogInspection inspectAndMaybeDismissCloseDialog(final Window target,
        final List<CloseDialogSnapshot> beforeDialogs, final AtomicBoolean coordinatorActive,
        final AtomicBoolean noActionClaimed, final AtomicBoolean noActionSucceeded,
        final AtomicBoolean noActionFailed, final CloseSessionTrace trace) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("dialog inspection must run on EDT");
        }
        if (!coordinatorActive.get()) {
            return CloseDialogInspection.waiting("close observation was cancelled");
        }
        if (noActionFailed != null && noActionFailed.get()) {
            return CloseDialogInspection.rejected(
                "dirty-save NO action failed; close observation is fail-closed");
        }
        if (target == null || !target.isShowing() || !target.isDisplayable()) {
            return CloseDialogInspection.targetGone("bound host window is no longer showing");
        }
        if (beforeDialogs == null) {
            return CloseDialogInspection.rejected("pre-close dialog snapshots are unavailable");
        }
        final List<CloseDialogSnapshot> currentDialogs = visibleCloseDialogs(target);
        if (trace != null) trace.recordInspection(currentDialogs);
        final CloseDialogDecision decision = classifyCloseDialog(
            GuiRowDispatch.componentIdentity(target), beforeDialogs, currentDialogs);
        if (trace != null) {
            trace.recordReason(decision == null ? "close dialog decision unavailable"
                : decision.diagnostic());
        }
        if (decision.outcome() == CloseDialogOutcome.DISMISS_NO) {
            return applyNoDialogDecision(decision, noActionClaimed, noActionSucceeded,
                noActionFailed);
        }
        if (decision.outcome() == CloseDialogOutcome.REJECTED) {
            return CloseDialogInspection.rejected(decision.diagnostic());
        }
        return CloseDialogInspection.waiting(decision.diagnostic());
    }

    /**
     * Applies the one permitted dirty-save answer on the EDT. The CAS is shared by every
     * inspection queued for this close session, including inspections that outlive an
     * invokeEdtBounded timeout; a failed action remains claimed and is never retried as another
     * real button action.
     */
    static CloseDialogInspection applyNoDialogDecision(
        final CloseDialogDecision decision, final AtomicBoolean noActionClaimed,
        final AtomicBoolean noActionSucceeded, final AtomicBoolean noActionFailed) {
        if (!SwingUtilities.isEventDispatchThread()) {
            return CloseDialogInspection.rejected("dirty-save option action must run on EDT");
        }
        if (decision == null || decision.outcome() != CloseDialogOutcome.DISMISS_NO
            || decision.dismissNo() == null) {
            return CloseDialogInspection.rejected("No dirty-save action is unavailable");
        }
        if (noActionClaimed == null || !noActionClaimed.compareAndSet(false, true)) {
            return CloseDialogInspection.waiting(
                "dirty-save NO action was already claimed by an earlier inspection");
        }
        try {
            decision.dismissNo().run();
            if (noActionSucceeded != null) noActionSucceeded.set(true);
            return CloseDialogInspection.dismissed(
                decision.diagnostic() + " selected=NO(index=1)");
        } catch (Throwable failure) {
            if (noActionFailed != null) noActionFailed.set(true);
            return CloseDialogInspection.rejected("NO option dispatch failed: "
                + GuiRowDispatch.stackTrace(failure));
        }
    }

    /** Test seam for the shared late-inspection No-action gate. Must be invoked on the EDT. */
    static CloseDialogInspection dismissNoForTest(final CloseDialogDecision decision,
        final AtomicBoolean coordinatorActive, final AtomicBoolean noActionClaimed) {
        if (!SwingUtilities.isEventDispatchThread()) {
            return CloseDialogInspection.rejected("dirty-save test action must run on EDT");
        }
        if (coordinatorActive == null || !coordinatorActive.get()) {
            return CloseDialogInspection.waiting("close observation was cancelled");
        }
        return applyNoDialogDecision(decision, noActionClaimed, new AtomicBoolean(),
            new AtomicBoolean());
    }

    static List<CloseDialogSnapshot> visibleCloseDialogs(final Window target) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("dialog discovery must run on EDT");
        }
        final List<CloseDialogSnapshot> snapshots = new ArrayList<>();
        for (final Window window : Window.getWindows()) {
            if (!(window instanceof Dialog dialog)
                || !dialog.isShowing() || !dialog.isDisplayable()) continue;
            final Dialog.ModalityType modality = dialog.getModalityType();
            final boolean modal = modality != null && modality != Dialog.ModalityType.MODELESS;
            final String modalityType = modality == null ? "UNKNOWN" : modality.name();
            final List<JOptionPane> panes = new ArrayList<>();
            collectOptionPanes(dialog, panes);
            if (panes.size() != 1) {
                snapshots.add(new CloseDialogSnapshot(GuiRowDispatch.componentIdentity(dialog),
                    ExactHostRowDispatch.objectIdentity(dialog.getOwner()), dialog.getOwner() == target,
                    dialog.isShowing(), dialog.isDisplayable(), modal, modalityType,
                    panes.size(), List.of(), List.of(), -1, () -> { }, () -> false));
                continue;
            }
            final JOptionPane pane = panes.get(0);
            final Object[] options = pane.getOptions();
            final List<String> classes = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            int initialIndex = -1;
            final Object initial = pane.getInitialValue();
            if (options != null) {
                for (int index = 0; index < options.length; index++) {
                    final Object option = options[index];
                    classes.add(option == null ? "null" : option.getClass().getName());
                    labels.add(option instanceof JButton button && button.getText() != null
                        ? button.getText() : "");
                    if (option == initial) initialIndex = index;
                }
            }
            final JButton noButton = options != null && options.length > 1
                && options[1] instanceof JButton button ? button : null;
            final Runnable dismissNo = noButton == null ? null : () -> {
                if (!isOperable(noButton)) {
                    throw new IllegalStateException(
                        "No option is not enabled/showing/displayable");
                }
                noButton.doClick();
            };
            final BooleanSupplier dismissNoReady = noButton == null
                ? () -> false : () -> isOperable(noButton);
            snapshots.add(new CloseDialogSnapshot(GuiRowDispatch.componentIdentity(dialog),
                ExactHostRowDispatch.objectIdentity(dialog.getOwner()), dialog.getOwner() == target,
                dialog.isShowing(), dialog.isDisplayable(), modal, modalityType,
                1, List.copyOf(classes), List.copyOf(labels), initialIndex,
                dismissNo, dismissNoReady));
        }
        return snapshots;
    }

    static boolean isOperable(final JButton button) {
        return button != null && button.isEnabled() && button.isShowing()
            && button.isDisplayable();
    }

    static void collectOptionPanes(final Component component,
        final List<JOptionPane> panes) {
        if (component instanceof JOptionPane pane) panes.add(pane);
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) {
                collectOptionPanes(child, panes);
            }
        }
    }

    static CloseDialogDecision classifyCloseDialogForTest(final String targetWindowIdentity,
        final List<CloseDialogSnapshot> beforeDialogs,
        final List<CloseDialogSnapshot> visibleDialogs) {
        return classifyCloseDialog(targetWindowIdentity, beforeDialogs, visibleDialogs);
    }

    static CloseDialogDecision classifyCloseDialog(final String targetWindowIdentity,
        final List<CloseDialogSnapshot> beforeDialogs,
        final List<CloseDialogSnapshot> visibleDialogs) {
        if (targetWindowIdentity == null || targetWindowIdentity.isBlank()) {
            return CloseDialogDecision.rejected("bound host window identity is unavailable");
        }
        if (beforeDialogs == null) {
            return CloseDialogDecision.rejected("pre-close dialog snapshots are unavailable");
        }
        final List<CloseDialogSnapshot> current = visibleDialogs == null ? List.of()
            : visibleDialogs;
        final Map<String, CloseDialogSnapshot> beforeByIdentity = new LinkedHashMap<>();
        for (final CloseDialogSnapshot dialog : beforeDialogs) {
            final String invalid = preExistingDialogFailure(targetWindowIdentity, dialog);
            if (invalid != null) {
                return CloseDialogDecision.rejected(
                    "pre-close dialog cannot be safely excluded: " + invalid);
            }
            if (beforeByIdentity.put(dialog.dialogIdentity(), dialog) != null) {
                return CloseDialogDecision.rejected(
                    "duplicate pre-close dialog identity: " + dialog.diagnostic());
            }
        }

        final Map<String, CloseDialogSnapshot> currentByIdentity = new LinkedHashMap<>();
        for (final CloseDialogSnapshot dialog : current) {
            final String invalid = currentDialogFailure(targetWindowIdentity, dialog);
            if (invalid != null) {
                return CloseDialogDecision.rejected(
                    "close dialog snapshot is unavailable or not exact: " + invalid);
            }
            if (currentByIdentity.put(dialog.dialogIdentity(), dialog) != null) {
                return CloseDialogDecision.rejected(
                    "duplicate close dialog identity: " + dialog.diagnostic());
            }
        }

        final List<CloseDialogSnapshot> excluded = new ArrayList<>();
        for (final CloseDialogSnapshot before : beforeDialogs) {
            final CloseDialogSnapshot after = currentByIdentity.get(before.dialogIdentity());
            if (after == null) {
                return CloseDialogDecision.rejected(
                    "pre-existing dialog disappeared or changed identity before close "
                        + "could be classified: " + before.diagnostic());
            }
            final String changed = preExistingDialogFailure(targetWindowIdentity, after);
            if (changed != null) {
                return CloseDialogDecision.rejected(
                    "pre-existing dialog changed before/after and cannot be excluded: "
                        + changed);
            }
            excluded.add(after);
        }

        final List<CloseDialogSnapshot> added = current.stream()
            .filter(Objects::nonNull)
            .filter(dialog -> !beforeByIdentity.containsKey(dialog.dialogIdentity()))
            .toList();
        final String excludedEvidence = excluded.isEmpty() ? ""
            : " excludedPreExisting=" + dialogInventoryDiagnostic(excluded,
                CLOSE_TRACE_REASON_MAX_BYTES);
        if (added.isEmpty()) {
            return CloseDialogDecision.noDialog(
                "no new dialog is associated with WINDOW_CLOSING target=" + targetWindowIdentity
                    + excludedEvidence);
        }
        if (added.size() > 1) {
            return CloseDialogDecision.rejected(
                "multiple new dialogs are associated with WINDOW_CLOSING: " + added.size()
                    + " evidence=" + dialogInventoryDiagnostic(added) + excludedEvidence);
        }
        final CloseDialogSnapshot dialog = added.get(0);
        if (dialog.optionPaneCount() != 1 || dialog.optionClassNames().size() != 3
            || dialog.optionLabels().size() != 3 || dialog.initialOptionIndex() != 0) {
            return CloseDialogDecision.rejected(
                "close-associated JOptionPane options are unknown: " + dialog.diagnostic()
                    + excludedEvidence);
        }
        final List<String> expectedClasses = List.of(
            JButton.class.getName(), JButton.class.getName(), JButton.class.getName());
        if (!expectedClasses.equals(dialog.optionClassNames())
            || !hostOptionLabel(dialog.optionLabels().get(0), 'Y')
            || !hostOptionLabel(dialog.optionLabels().get(1), 'N')
            || !hostOptionLabel(dialog.optionLabels().get(2), 'C')) {
            return CloseDialogDecision.rejected(
                "close-associated JOptionPane does not have the verified Yes/No/Cancel options"
                    + excludedEvidence);
        }
        if (dialog.dismissNo() == null) {
            return CloseDialogDecision.rejected(
                "close-associated JOptionPane has no operable No action" + excludedEvidence);
        }
        final boolean noReady;
        try {
            noReady = dialog.dismissNoReady() != null && dialog.dismissNoReady().getAsBoolean();
        } catch (Throwable failure) {
            return CloseDialogDecision.rejected(
                "close-associated JOptionPane No-action readiness failed: "
                    + GuiRowDispatch.stackTrace(failure) + excludedEvidence);
        }
        if (!noReady) {
            return CloseDialogDecision.rejected(
                "close-associated JOptionPane No option is not enabled/showing/displayable"
                    + excludedEvidence);
        }
        return CloseDialogDecision.dismissNo(
            "new dirty-save JOptionPane=" + dialog.diagnostic() + excludedEvidence,
            dialog.dismissNo());
    }

    /**
     * Validates a complete before/after snapshot for a dialog that may be excluded as a
     * pre-existing modeless host tool window.  A partial or changed snapshot is never ignored.
     */
    static String preExistingDialogFailure(final String targetWindowIdentity,
        final CloseDialogSnapshot dialog) {
        final String currentFailure = currentDialogFailure(targetWindowIdentity, dialog);
        if (currentFailure != null) return currentFailure;
        if (dialog.modal() || !"MODELESS".equals(dialog.modalityType())
            || dialog.optionPaneCount() != 0
            || !dialog.optionClassNames().isEmpty() || !dialog.optionLabels().isEmpty()
            || dialog.initialOptionIndex() != -1) {
            return "pre-existing dialog is not the verified modeless panes=0 shape: "
                + dialog.diagnostic();
        }
        return null;
    }

    /** Validates identity/owner/liveness and rejects unknown modality evidence for every after. */
    static String currentDialogFailure(final String targetWindowIdentity,
        final CloseDialogSnapshot dialog) {
        if (dialog == null) return "null dialog snapshot";
        if (dialog.dialogIdentity() == null || dialog.dialogIdentity().isBlank()) {
            return "dialog identity is unavailable: " + dialog.diagnostic();
        }
        if (!dialog.ownerMatchesTarget()
            || !targetWindowIdentity.equals(dialog.ownerIdentity())) {
            return "dialog owner identity does not exactly equal bound window: owner="
                + dialog.ownerIdentity() + " bound=" + targetWindowIdentity
                + " evidence=" + dialog.diagnostic();
        }
        if (!dialog.showing() || !dialog.displayable()) {
            return "dialog is not showing/displayable: " + dialog.diagnostic();
        }
        if (dialog.modalityType() == null || dialog.modalityType().isBlank()
            || "UNKNOWN".equals(dialog.modalityType())) {
            return "dialog modality is UNKNOWN: " + dialog.diagnostic();
        }
        final boolean modalByType = !"MODELESS".equals(dialog.modalityType());
        if (modalByType != dialog.modal()) {
            return "dialog modal/modality evidence is inconsistent: " + dialog.diagnostic();
        }
        return null;
    }

    static boolean hostOptionLabel(final String label, final char suffix) {
        return label != null && label.trim().endsWith("(" + suffix + ")");
    }

    static Path guiReadyTriggerPathForTest(final Path stateDir) {
        return guiReadyTriggerPath(stateDir);
    }

    static Path guiReadyTriggerPath(final Path stateDir) {
        if (stateDir == null) throw new IllegalArgumentException(
            "GUI readiness state directory is null");
        final Path normalized = stateDir.toAbsolutePath().normalize();
        if (normalized.equals(Path.of("/"))) throw new IllegalArgumentException(
            "GUI readiness state directory must not be filesystem root");
        if (Files.isSymbolicLink(normalized)
            || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "GUI readiness state directory is not a real directory: " + normalized);
        }
        SessionFileReadiness.requireNoSymlinkPath(normalized, "GUI readiness state directory");
        return normalized.resolve(GuiReadinessDiagnostics.GUI_READY_TRIGGER_NAME);
    }

    /** Bounded evidence for one native close coordinator invocation. */
    static final class CloseSessionTrace {
        final String sessionId;
        final long startedAtNanos;
        final AtomicInteger dispatchAttempts = new AtomicInteger();
        final AtomicInteger inspectionCount = new AtomicInteger();
        final AtomicLong beforeAtNanos = new AtomicLong(-1L);
        final AtomicLong dispatchAtNanos = new AtomicLong(-1L);
        final AtomicLong firstInspectionAtNanos = new AtomicLong(-1L);
        final AtomicReference<String> before = new AtomicReference<>("unavailable");
        final AtomicReference<String> dispatch = new AtomicReference<>("not-dispatched");
        final AtomicReference<String> lastAfter = new AtomicReference<>("unavailable");
        final AtomicReference<String> reason = new AtomicReference<>("unavailable");

        CloseSessionTrace() {
            this("close-" + CLOSE_SESSION_SEQUENCE.incrementAndGet());
        }

        CloseSessionTrace(final String sessionId) {
            this.sessionId = sessionId == null || sessionId.isBlank() ? "unknown" : sessionId;
            startedAtNanos = System.nanoTime();
        }

        void recordBefore(final List<CloseDialogSnapshot> dialogs) {
            beforeAtNanos.compareAndSet(-1L, System.nanoTime());
            before.set(dialogInventoryDiagnostic(dialogs, CLOSE_TRACE_BEFORE_MAX_BYTES));
        }

        void recordDispatchAttempt() {
            dispatchAttempts.incrementAndGet();
            dispatchAtNanos.compareAndSet(-1L, System.nanoTime());
            dispatch.set("started");
        }

        void recordDispatch(final CloseDispatchResult result) {
            dispatch.set(boundedCloseDiagnostic(
                result == null ? "unavailable" : result.diagnostic(),
                CLOSE_TRACE_DISPATCH_MAX_BYTES));
        }

        void recordInspection(final List<CloseDialogSnapshot> dialogs) {
            inspectionCount.incrementAndGet();
            firstInspectionAtNanos.compareAndSet(-1L, System.nanoTime());
            lastAfter.set(dialogInventoryDiagnostic(dialogs, CLOSE_TRACE_AFTER_MAX_BYTES));
        }

        void recordReason(final String value) {
            reason.set(boundedCloseDiagnostic(value, CLOSE_TRACE_REASON_MAX_BYTES));
        }

        String diagnostic() {
            final long dispatchNanos = dispatchAtNanos.get();
            final long firstInspectionNanos = firstInspectionAtNanos.get();
            return boundedCloseDiagnostic(
                "closeSession=" + boundedCloseField(sessionId)
                    + " elapsedMs=" + DiagnosticObservationRecorder.elapsedMillis(startedAtNanos)
                    + " dispatchAttempts=" + dispatchAttempts.get()
                    + " inspectionCount=" + inspectionCount.get()
                    + " beforeToDispatchMs=" + elapsedBetween(beforeAtNanos.get(), dispatchNanos)
                    + " dispatchToFirstInspectionMs="
                    + elapsedBetween(dispatchNanos, firstInspectionNanos)
                    + " before=" + before.get()
                    + " after=" + lastAfter.get()
                    + " dispatch=" + dispatch.get()
                    + " reason=" + reason.get(), CLOSE_TRACE_MAX_BYTES);
        }

        String sessionId() { return sessionId; }

        static long elapsedMillis(final long startNanos) {
            return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        }

        static long elapsedBetween(final long startNanos, final long endNanos) {
            if (startNanos < 0L || endNanos < 0L || endNanos < startNanos) return -1L;
            return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
        }
    }

    static HostCloseResult withCloseTrace(final CloseSessionTrace trace,
        final HostCloseResult result) {
        if (trace == null || result == null) return result;
        final String resultReason = boundedCloseDiagnostic(result.diagnostic(),
            CLOSE_RESULT_REASON_MAX_BYTES);
        final String traceDiagnostic = boundedCloseDiagnostic(trace.diagnostic(),
            CLOSE_TRACE_MAX_BYTES);
        return new HostCloseResult(result.success(), boundedCloseDiagnostic(
            resultReason + CLOSE_TRACE_SEPARATOR + traceDiagnostic,
            CLOSE_DIAGNOSTIC_MAX_BYTES));
    }

    /** Test seam for asserting the final close-result byte budget without exposing host results. */
    static String closeDiagnosticForTest(final CloseSessionTrace trace,
        final String resultDiagnostic) {
        return withCloseTrace(trace, HostCloseResult.rejected(resultDiagnostic)).diagnostic();
    }

    static String dialogInventoryDiagnostic(final List<CloseDialogSnapshot> dialogs) {
        return dialogInventoryDiagnostic(dialogs, CLOSE_TRACE_REASON_MAX_BYTES);
    }

    static String dialogInventoryDiagnostic(final List<CloseDialogSnapshot> dialogs,
        final int maxBytes) {
        if (dialogs == null) return "unavailable";
        final int includedLimit = Math.min(dialogs.size(), CLOSE_DIALOG_MAX_COUNT);
        final StringBuilder text = new StringBuilder("[count=")
            .append(dialogs.size());
        if (dialogs.size() > includedLimit) text.append(" truncated=true");
        text.append(" entries=");
        for (int index = 0; index < includedLimit; index++) {
            if (index > 0) text.append(", ");
            final CloseDialogSnapshot dialog = dialogs.get(index);
            text.append(dialog == null ? "null" : dialog.diagnostic());
        }
        if (dialogs.size() > includedLimit) text.append(", …more");
        return boundedCloseDiagnostic(text.append(']').toString(), maxBytes);
    }

    static String boundedCloseDiagnostic(final String value) {
        return boundedCloseDiagnostic(value, CLOSE_DIAGNOSTIC_MAX_BYTES);
    }

    /** Returns a single-line UTF-8 string whose encoded byte length never exceeds maxBytes. */
    static String boundedCloseDiagnostic(final String value, final int maxBytes) {
        if (value == null || maxBytes <= 0) return "";
        final String singleLine = value.replace('\r', ' ').replace('\n', ' ');
        if (utf8ByteLength(singleLine) <= maxBytes) return singleLine;
        final String marker = "…";
        final int markerBytes = utf8ByteLength(marker);
        if (maxBytes <= markerBytes) return utf8Prefix(singleLine, maxBytes);
        return utf8Prefix(singleLine, maxBytes - markerBytes) + marker;
    }

    static String utf8Prefix(final String value, final int maxBytes) {
        if (value == null || maxBytes <= 0) return "";
        int offset = 0;
        int bytes = 0;
        while (offset < value.length()) {
            final int next = value.offsetByCodePoints(offset, 1);
            final int codePointBytes = utf8ByteLength(value.substring(offset, next));
            if (bytes + codePointBytes > maxBytes) break;
            bytes += codePointBytes;
            offset = next;
        }
        return value.substring(0, offset);
    }

    static int utf8ByteLength(final String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    static String boundedCloseField(final String value) {
        return boundedCloseDiagnostic(value, CLOSE_DIALOG_FIELD_MAX_BYTES);
    }

    static String boundedCloseList(final List<String> values) {
        if (values == null) return "null";
        final StringBuilder text = new StringBuilder("[");
        final int limit = Math.min(values.size(), 8);
        for (int index = 0; index < limit; index++) {
            if (index > 0) text.append(", ");
            text.append(boundedCloseField(values.get(index)));
        }
        if (values.size() > limit) text.append(", ...");
        return text.append(']').toString();
    }

    enum CloseDialogOutcome {
        NO_DIALOG,
        DISMISS_NO,
        REJECTED
    }

    record CloseDialogSnapshot(String dialogIdentity, String ownerIdentity,
        boolean ownerMatchesTarget, boolean showing, boolean displayable, boolean modal,
        String modalityType, int optionPaneCount, List<String> optionClassNames,
        List<String> optionLabels, int initialOptionIndex, Runnable dismissNo,
        BooleanSupplier dismissNoReady) {
        CloseDialogSnapshot(final String dialogIdentity, final String ownerIdentity,
            final boolean ownerMatchesTarget, final boolean showing, final boolean displayable,
            final int optionPaneCount, final List<String> optionClassNames,
            final List<String> optionLabels, final int initialOptionIndex,
            final Runnable dismissNo) {
            this(dialogIdentity, ownerIdentity, ownerMatchesTarget, showing, displayable,
                false, "UNKNOWN", optionPaneCount, optionClassNames, optionLabels,
                initialOptionIndex, dismissNo, dismissNo == null ? () -> false : () -> true);
        }

        CloseDialogSnapshot(final String dialogIdentity, final String ownerIdentity,
            final boolean ownerMatchesTarget, final boolean showing, final boolean displayable,
            final int optionPaneCount, final List<String> optionClassNames,
            final List<String> optionLabels, final int initialOptionIndex,
            final Runnable dismissNo, final BooleanSupplier dismissNoReady) {
            this(dialogIdentity, ownerIdentity, ownerMatchesTarget, showing, displayable,
                false, "UNKNOWN", optionPaneCount, optionClassNames, optionLabels,
                initialOptionIndex, dismissNo, dismissNoReady);
        }

        CloseDialogSnapshot {
            dialogIdentity = dialogIdentity == null ? "" : dialogIdentity;
            ownerIdentity = ownerIdentity == null ? "" : ownerIdentity;
            modalityType = modalityType == null || modalityType.isBlank()
                ? "UNKNOWN" : modalityType;
            optionClassNames = optionClassNames == null ? List.of()
                : List.copyOf(optionClassNames);
            optionLabels = optionLabels == null ? List.of() : List.copyOf(optionLabels);
            dismissNoReady = dismissNoReady == null ? () -> false : dismissNoReady;
        }

        String diagnostic() {
            return "dialog=" + boundedCloseField(dialogIdentity)
                + " owner=" + boundedCloseField(ownerIdentity)
                + " ownerMatches=" + ownerMatchesTarget
                + " showing=" + showing
                + " displayable=" + displayable
                + " modal=" + modal
                + " modality=" + boundedCloseField(modalityType)
                + " panes=" + optionPaneCount
                + " optionClasses=" + boundedCloseList(optionClassNames)
                + " optionLabels=" + boundedCloseList(optionLabels)
                + " initial=" + initialOptionIndex;
        }
    }

    record CloseDialogDecision(CloseDialogOutcome outcome, String diagnostic,
        Runnable dismissNo) {
        CloseDialogDecision {
            outcome = Objects.requireNonNull(outcome, "outcome");
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static CloseDialogDecision noDialog(final String diagnostic) {
            return new CloseDialogDecision(CloseDialogOutcome.NO_DIALOG, diagnostic, null);
        }

        static CloseDialogDecision dismissNo(final String diagnostic, final Runnable action) {
            return new CloseDialogDecision(CloseDialogOutcome.DISMISS_NO, diagnostic, action);
        }

        static CloseDialogDecision rejected(final String diagnostic) {
            return new CloseDialogDecision(CloseDialogOutcome.REJECTED, diagnostic, null);
        }
    }

    record CloseDialogInspection(boolean targetGone, boolean dismissedNo,
        boolean rejected, String diagnostic) {
        static CloseDialogInspection targetGone(final String diagnostic) {
            return new CloseDialogInspection(true, false, false, diagnostic);
        }

        static CloseDialogInspection waiting(final String diagnostic) {
            return new CloseDialogInspection(false, false, false, diagnostic);
        }

        static CloseDialogInspection dismissed(final String diagnostic) {
            return new CloseDialogInspection(false, true, false, diagnostic);
        }

        static CloseDialogInspection rejected(final String diagnostic) {
            return new CloseDialogInspection(false, false, true, diagnostic);
        }
    }

    record CloseDispatchResult(boolean dispatched, String diagnostic) {
        CloseDispatchResult {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static CloseDispatchResult dispatched(final String diagnostic) {
            return new CloseDispatchResult(true, diagnostic);
        }

        static CloseDispatchResult rejected(final String diagnostic) {
            return new CloseDispatchResult(false, diagnostic);
        }
    }

    record HostCloseResult(boolean success, String diagnostic) {
        HostCloseResult {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static HostCloseResult success(final String diagnostic) {
            return new HostCloseResult(true, diagnostic);
        }

        static HostCloseResult rejected(final String diagnostic) {
            return new HostCloseResult(false, diagnostic);
        }

        static HostCloseResult timeout(final String diagnostic) {
            return new HostCloseResult(false, diagnostic);
        }
    }

    record EdtCall<T>(boolean completed, T value, Throwable failure) {
        static <T> EdtCall<T> completed(final T value) {
            return new EdtCall<>(true, value, null);
        }

        static <T> EdtCall<T> failed(final Throwable failure) {
            return new EdtCall<>(true, null, failure);
        }

        static <T> EdtCall<T> timeout() {
            return new EdtCall<>(false, null, null);
        }
    }

    record ClosePreparation<T>(EdtCall<T> beforeCall, boolean dispatchPosted,
        AtomicBoolean dispatchStarted, AtomicReference<Throwable> dispatchFailure) {
        ClosePreparation {
            beforeCall = Objects.requireNonNull(beforeCall, "beforeCall");
            dispatchStarted = Objects.requireNonNull(dispatchStarted, "dispatchStarted");
            dispatchFailure = Objects.requireNonNull(dispatchFailure, "dispatchFailure");
        }

        static <T> ClosePreparation<T> timeout() {
            return new ClosePreparation<>(EdtCall.timeout(), false,
                new AtomicBoolean(), new AtomicReference<>());
        }
    }

    record CloseTimingResult(boolean beforeCompleted, boolean dispatchPosted,
        boolean dispatchStarted, int dispatchCalls, boolean deadlineReached, boolean stopped,
        String diagnostic) {
        CloseTimingResult {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }
}
