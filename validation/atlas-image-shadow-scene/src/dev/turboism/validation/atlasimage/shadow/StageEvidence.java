package dev.turboism.validation.atlasimage.shadow;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/** One replaceable, bounded stage snapshot; it never emits window or model text. */
final class StageEvidence {
    private final Path target;
    private final String scene;
    private final long runStartedEpochMillis = System.currentTimeMillis();
    private final long runStartedNanos = System.nanoTime();
    private long sequence;
    private String stage = "RUN";
    private String event = "START";
    private long eventEpochMillis = runStartedEpochMillis;
    private long eventElapsedMillis;
    private boolean failure;
    private String failureCode = "NONE";
    private boolean timedOut;
    private boolean startedAtTimeout;
    private long onEdtQueued;
    private long onEdtStarted;
    private long onEdtCompleted;
    private long onEdtTimedOut;
    private long onEdtLateSkipped;
    private String onEdtLastOperation = "NONE";
    private String onEdtLastState = "NONE";
    private String onEdtLastOutcome = "NONE";
    private boolean editorDispatchStarted;
    private boolean editorDispatchReturned;
    private long editorDispatchElapsedMillis;
    private boolean editorClosePollStarted;
    private long editorClosePollCount;
    private boolean editorClosed;
    private boolean editorClosePollVisible;
    private boolean editorClosePollUnexpected;
    private long editorUnexpectedWindowCount;
    private String editorUnexpectedWindowClasses = "NONE";
    private long editorNonModalWindowCount;
    private String editorNonModalWindowClasses = "NONE";
    private long editorClosePollMillis;
    private boolean editorClosePollTimedOut;
    private long baselineWindowCount;
    private String baselineWindowClasses = "NONE";
    private boolean editorActionWaitStarted;
    private boolean editorActionWaitCompleted;
    private long editorActionWaitElapsedMillis;
    private boolean freezeStarted;
    private boolean freezeCompleted;
    private boolean payloadWriteStarted;
    private boolean payloadWriteCompleted;
    private boolean payloadReopenVerified;
    private String payloadHash = "NONE";
    private boolean canonicalWriteStarted;
    private boolean canonicalComplete;
    private boolean canonicalFailed;
    private boolean nativeExitStarted;
    private boolean nativeExitReturned;
    private boolean exitPromptSeen;
    private String exitPromptAnswer = "NONE";
    private int exitPromptButtonCount;
    private String exitPromptClasses = "NONE";
    private long exitPromptMillis;
    private long nativeExitClickMillis;
    private int exitWindowSamples;
    private int exitWindowCount;
    private String exitWindowClasses = "NONE";
    private int exitNonDaemonThreadCount;
    private String exitNonDaemonThreadNames = "NONE";
    private int readinessRounds;
    private int readinessSlowRounds;
    private long readinessLastRoundMillis;
    private boolean okDispatchStarted;
    private boolean okDispatchReturned;
    private long okDispatchElapsedMillis;
    private boolean okActionWaitStarted;
    private boolean okActionWaitCompleted;
    private long okActionWaitElapsedMillis;
    private boolean layoutDialogSeen;
    private long layoutDialogMillis;
    private String layoutScaleText = "NONE";
    private long layoutApplyElapsedMillis;
    private boolean layoutClosed;
    private boolean layoutPreserved;
    private boolean layoutAttempted;

    private final long startupBudgetSeconds;
    private final long closePollBudgetSeconds;
    private final long layoutDialogBudgetSeconds;

    StageEvidence(final Path target, final String scene, final long startupBudgetSeconds,
                  final long closePollBudgetSeconds, final long layoutDialogBudgetSeconds) {
        this.target = target;
        this.scene = scene;
        this.startupBudgetSeconds = startupBudgetSeconds;
        this.closePollBudgetSeconds = closePollBudgetSeconds;
        this.layoutDialogBudgetSeconds = layoutDialogBudgetSeconds;
        write();
    }

    synchronized void stage(final String nextStage, final String nextEvent) {
        event(nextStage, nextEvent, 0L);
    }

    synchronized void onEdtQueued(final FixedEdt.Operation operation) {
        onEdtQueued++;
        onEdtLastOperation = operation.name();
        onEdtLastState = FixedEdt.State.QUEUED.name();
        onEdtLastOutcome = "NONE";
        event("ON_EDT", "QUEUED", 0L);
    }

    synchronized void onEdtStarted(final FixedEdt.Operation operation) {
        onEdtStarted++;
        onEdtLastOperation = operation.name();
        onEdtLastState = FixedEdt.State.STARTED.name();
        onEdtLastOutcome = "NONE";
        event("ON_EDT", "STARTED", 0L);
    }

    synchronized void onEdtEnded(final FixedEdt.Operation operation, final FixedEdt.State state,
                                  final String outcome, final long elapsedMillis) {
        onEdtCompleted++;
        onEdtLastOperation = operation.name();
        onEdtLastState = state.name();
        onEdtLastOutcome = outcome;
        event("ON_EDT", "ENDED", elapsedMillis);
    }

    synchronized void onEdtWaitTimedOut(final FixedEdt.Operation operation,
                                         final FixedEdt.State state) {
        onEdtTimedOut++;
        timedOut = true;
        startedAtTimeout = state == FixedEdt.State.STARTED;
        onEdtLastOperation = operation.name();
        onEdtLastState = state.name();
        onEdtLastOutcome = "TIMEOUT";
        event("ON_EDT", "WAIT_TIMEOUT", 0L);
    }

    synchronized void onEdtWaitInterrupted(final FixedEdt.Operation operation,
                                            final FixedEdt.State state) {
        onEdtLastOperation = operation.name();
        onEdtLastState = state.name();
        onEdtLastOutcome = "INTERRUPTED";
        event("ON_EDT", "WAIT_INTERRUPTED", 0L);
    }

    synchronized void onEdtLateSkipped(final FixedEdt.Operation operation) {
        onEdtLateSkipped++;
        onEdtLastOperation = operation.name();
        onEdtLastState = FixedEdt.State.TIMED_OUT.name();
        onEdtLastOutcome = "LATE_CALLBACK_SKIPPED";
        event("ON_EDT", "LATE_CALLBACK_SKIPPED", 0L);
    }

    synchronized void editorDispatchStarted() {
        editorDispatchStarted = true;
        event("EDITOR_MENU", "DISPATCH", 0L);
    }

    synchronized void editorDispatchEnded(final long elapsedMillis, final boolean returned) {
        editorDispatchElapsedMillis = Math.max(0L, elapsedMillis);
        editorDispatchReturned = returned;
        event("EDITOR_MENU", returned ? "EDT_RETURN" : "EDT_FAILURE", editorDispatchElapsedMillis);
    }

    synchronized void editorClosePollStarted() {
        editorClosePollStarted = true;
        event("EDITOR_CLOSE", "POLL_START", 0L);
    }

    /**
     * One close-poll sample. {@code unexpected} tracks modal dialogs only: a window that can swallow
     * input stays fail-closed, while non-modal windows such as Swing popups or the host's own
     * palettes are recorded but cannot block this scene.
     */
    synchronized void editorClosePoll(final long pollCount, final boolean closed,
                                      final boolean editorVisible, final boolean unexpected,
                                      final long unexpectedCount, final String unexpectedClasses,
                                      final long toleratedCount, final String toleratedClasses,
                                      final long pollMillis) {
        editorClosePollCount = pollCount;
        editorClosed = closed;
        editorClosePollVisible = editorVisible;
        editorClosePollUnexpected = editorClosePollUnexpected || unexpected;
        editorUnexpectedWindowCount = Math.max(editorUnexpectedWindowCount,
            Math.max(0L, unexpectedCount));
        if (unexpectedCount > 0L) editorUnexpectedWindowClasses = unexpectedClasses;
        editorNonModalWindowCount = Math.max(editorNonModalWindowCount, Math.max(0L, toleratedCount));
        if (toleratedCount > 0L) editorNonModalWindowClasses = toleratedClasses;
        editorClosePollMillis = Math.max(0L, pollMillis);
        event("EDITOR_CLOSE", closed ? "CLOSED" : "POLLING", editorClosePollMillis);
    }

    /** The close poll ran out of budget with the editor or a modal window still showing. */
    synchronized void editorClosePollExpired(final long pollMillis) {
        editorClosePollTimedOut = true;
        editorClosePollMillis = Math.max(0L, pollMillis);
        event("EDITOR_CLOSE", "POLL_EXPIRED", editorClosePollMillis);
    }


    /** Host windows already showing before the scene acts; only later windows stay suspicious. */
    synchronized void baseline(final long windowCount, final String windowClasses) {
        baselineWindowCount = Math.max(0L, windowCount);
        baselineWindowClasses = windowClasses;
        event("MAIN", "BASELINE", 0L);
    }
    synchronized void editorActionWaitStarted() {
        editorActionWaitStarted = true;
        event("EDITOR_ACTION", "WAIT_START", 0L);
    }

    synchronized void editorActionWaitEnded(final long elapsedMillis, final boolean completed) {
        editorActionWaitElapsedMillis = Math.max(0L, elapsedMillis);
        editorActionWaitCompleted = completed;
        event("EDITOR_ACTION", completed ? "COMPLETED" : "FAILED", editorActionWaitElapsedMillis);
    }

    synchronized void freezeStarted() {
        freezeStarted = true;
        event("FREEZE", "START", 0L);
    }

    synchronized void freezeCompleted() {
        freezeCompleted = true;
        event("FREEZE", "COMPLETE", 0L);
    }

    synchronized void payloadWriteStarted() {
        payloadWriteStarted = true;
        event("PAYLOAD", "WRITE_START", 0L);
    }

    synchronized void payloadWriteCompleted(final String hash) {
        payloadWriteCompleted = true;
        payloadHash = hash;
        event("PAYLOAD", "WRITE_COMPLETE", 0L);
    }

    synchronized void payloadReopenVerified() {
        payloadReopenVerified = true;
        event("PAYLOAD", "REOPEN_HASH_VERIFIED", 0L);
    }

    synchronized void canonicalWriteStarted() {
        canonicalWriteStarted = true;
        event("CANONICAL", "WRITE_START", 0L);
    }

    synchronized void canonicalComplete() {
        canonicalComplete = true;
        event("CANONICAL", "COMPLETE", 0L);
    }

    synchronized void canonicalFailed() {
        canonicalFailed = true;
        event("CANONICAL", "FAILED", 0L);
    }

    synchronized void nativeExitStarted() {
        nativeExitStarted = true;
        event("NATIVE_EXIT", "START", 0L);
    }

    /**
     * Outcome of the best-effort native exit. The run is already published when this is recorded,
     * so a click that never returns is host lifecycle evidence, not a scene failure.
     */
    synchronized void nativeExitProbe(final boolean clickCompleted, final long clickMillis,
                                      final int windowSamples, final int windowCount,
                                      final String windowClasses, final int nonDaemonThreadCount,
                                      final String nonDaemonThreadNames) {
        this.nativeExitReturned = clickCompleted;
        this.nativeExitClickMillis = Math.max(0L, clickMillis);
        this.exitWindowSamples = Math.max(0, windowSamples);
        this.exitWindowCount = Math.max(0, windowCount);
        this.exitWindowClasses = windowClasses == null || windowClasses.isEmpty()
            ? "NONE" : windowClasses;
        this.exitNonDaemonThreadCount = Math.max(0, nonDaemonThreadCount);
        this.exitNonDaemonThreadNames = nonDaemonThreadNames == null || nonDaemonThreadNames.isEmpty()
            ? "NONE" : nonDaemonThreadNames;
        event("NATIVE_EXIT", clickCompleted ? "RETURNED" : "POSTED_NOT_RETURNED",
            this.nativeExitClickMillis);
    }

    /**
     * Host exit prompt observation. A prompt the scene may not answer is still recorded, because an
     * unanswered prompt is the reason a graceful host exit never happens.
     */
    synchronized void exitPrompt(final boolean seen, final String answer, final int buttonCount,
                                 final String classes, final long millis) {
        this.exitPromptSeen = seen;
        this.exitPromptAnswer = answer == null || answer.isEmpty() ? "NONE" : answer;
        this.exitPromptButtonCount = Math.max(0, buttonCount);
        this.exitPromptClasses = classes == null || classes.isEmpty() ? "NONE" : classes;
        this.exitPromptMillis = Math.max(0L, millis);
        event("NATIVE_EXIT", seen ? "PROMPT_" + this.exitPromptAnswer : "PROMPT_NONE",
            this.exitPromptMillis);
    }
    /** Host-readiness gate outcome: queued/slow round trips prove the EDT is still saturated. */
    synchronized void readiness(final int rounds, final int slowRounds, final long lastRoundMillis) {
        readinessRounds = rounds;
        readinessSlowRounds = slowRounds;
        readinessLastRoundMillis = Math.max(0L, lastRoundMillis);
        event("MAIN_READY", "READY", readinessLastRoundMillis);
    }

    synchronized void okDispatchStarted() {
        okDispatchStarted = true;
        event("EDITOR_OK", "DISPATCH", 0L);
    }

    synchronized void okDispatchEnded(final long elapsedMillis, final boolean returned) {
        okDispatchElapsedMillis = Math.max(0L, elapsedMillis);
        okDispatchReturned = returned;
        event("EDITOR_OK", returned ? "EDT_STARTED" : "EDT_FAILURE", okDispatchElapsedMillis);
    }

    synchronized void okActionWaitStarted() {
        okActionWaitStarted = true;
        event("EDITOR_OK_ACTION", "WAIT_START", 0L);
    }

    synchronized void okActionWaitEnded(final long elapsedMillis, final boolean completed) {
        okActionWaitElapsedMillis = Math.max(0L, elapsedMillis);
        okActionWaitCompleted = completed;
        event("EDITOR_OK_ACTION", completed ? "COMPLETED" : "FAILED", okActionWaitElapsedMillis);
    }

    /** The scene deliberately kept the saved layout: nothing was opened, applied or measured. */
    synchronized void layoutPreserved() {
        layoutPreserved = true;
        layoutAttempted = false;
        event("LAYOUT", "PRESERVED", 0L);
    }

    synchronized void layoutDialog(final boolean seen, final long millis) {
        layoutAttempted = true;
        layoutDialogSeen = seen;
        layoutDialogMillis = Math.max(0L, millis);
        event("LAYOUT", seen ? "DIALOG_SEEN" : "DIALOG_MISSING", layoutDialogMillis);
    }

    synchronized void layoutScale(final String text) {
        layoutScaleText = text == null || text.isBlank() ? "NONE" : text;
        event("LAYOUT", "SCALE_SET", 0L);
    }

    synchronized void layoutApplyEnded(final long millis) {
        layoutApplyElapsedMillis = Math.max(0L, millis);
        event("LAYOUT", "APPLY_RETURNED", layoutApplyElapsedMillis);
    }

    synchronized void layoutClosed(final boolean closed) {
        layoutClosed = closed;
        event("LAYOUT", closed ? "DIALOG_CLOSED" : "DIALOG_STILL_OPEN", 0L);
    }

    synchronized void failed(final String code) {
        failure = true;
        if ("NONE".equals(failureCode)) failureCode = code;
        event("FAILURE", "RECORDED", 0L);
    }

    private void event(final String nextStage, final String nextEvent, final long elapsedMillis) {
        stage = nextStage;
        event = nextEvent;
        eventEpochMillis = System.currentTimeMillis();
        eventElapsedMillis = Math.max(0L, elapsedMillis);
        sequence++;
        write();
    }

    private void write() {
        final StringBuilder content = new StringBuilder(1800);
        append(content, "schemaVersion", "1");
        append(content, "scene", scene);
        append(content, "sequence", Long.toString(sequence));
        append(content, "runStartedEpochMillis", Long.toString(runStartedEpochMillis));
        append(content, "eventEpochMillis", Long.toString(eventEpochMillis));
        append(content, "runElapsedMillis", Long.toString(elapsedMillis(runStartedNanos)));
        append(content, "eventElapsedMillis", Long.toString(eventElapsedMillis));
        append(content, "stage", stage);
        append(content, "event", event);
        append(content, "failure", Boolean.toString(failure));
        append(content, "failureCode", failureCode);
        append(content, "timedOut", Boolean.toString(timedOut));
        append(content, "startedAtTimeout", Boolean.toString(startedAtTimeout));
        append(content, "startupBudgetSeconds", Long.toString(startupBudgetSeconds));
        append(content, "closePollBudgetSeconds", Long.toString(closePollBudgetSeconds));
        append(content, "layoutDialogBudgetSeconds", Long.toString(layoutDialogBudgetSeconds));
        append(content, "layout.preserved", Boolean.toString(layoutPreserved));
        append(content, "layout.attempted", Boolean.toString(layoutAttempted));
        append(content, "edtStartBudgetSeconds",
            Long.toString(ShadowSceneContract.EDT_START_TIMEOUT_SECONDS));
        append(content, "edtQueryBudgetSeconds",
            Long.toString(ShadowSceneContract.EDT_QUERY_TIMEOUT_SECONDS));
        append(content, "edtReadyRounds", Integer.toString(ShadowSceneContract.EDT_READY_ROUNDS));
        append(content, "edtReadyRoundMillis",
            Long.toString(ShadowSceneContract.EDT_READY_ROUND_MILLIS));
        append(content, "onEdt.queued", Long.toString(onEdtQueued));
        append(content, "onEdt.started", Long.toString(onEdtStarted));
        append(content, "onEdt.completed", Long.toString(onEdtCompleted));
        append(content, "onEdt.timedOut", Long.toString(onEdtTimedOut));
        append(content, "onEdt.lateSkipped", Long.toString(onEdtLateSkipped));
        append(content, "onEdt.lastOperation", onEdtLastOperation);
        append(content, "onEdt.lastState", onEdtLastState);
        append(content, "onEdt.lastOutcome", onEdtLastOutcome);
        append(content, "readiness.rounds", Integer.toString(readinessRounds));
        append(content, "readiness.slowRounds", Integer.toString(readinessSlowRounds));
        append(content, "readiness.lastRoundMillis", Long.toString(readinessLastRoundMillis));
        append(content, "editor.dispatchStarted", Boolean.toString(editorDispatchStarted));
        append(content, "editor.dispatchReturned", Boolean.toString(editorDispatchReturned));
        append(content, "editor.dispatchElapsedMillis", Long.toString(editorDispatchElapsedMillis));
        append(content, "editor.closePollStarted", Boolean.toString(editorClosePollStarted));
        append(content, "editor.closePollCount", Long.toString(editorClosePollCount));
        append(content, "editor.closed", Boolean.toString(editorClosed));
        append(content, "editor.closePollVisible", Boolean.toString(editorClosePollVisible));
        append(content, "editor.closePollUnexpected", Boolean.toString(editorClosePollUnexpected));
        append(content, "editor.unexpectedWindowCount",
            Long.toString(editorUnexpectedWindowCount));
        append(content, "editor.unexpectedWindowClasses", editorUnexpectedWindowClasses);
        append(content, "editor.nonModalWindowCount", Long.toString(editorNonModalWindowCount));
        append(content, "editor.nonModalWindowClasses", editorNonModalWindowClasses);
        append(content, "editor.closePollMillis", Long.toString(editorClosePollMillis));
        append(content, "editor.closePollTimedOut", Boolean.toString(editorClosePollTimedOut));
        append(content, "baseline.windowCount", Long.toString(baselineWindowCount));
        append(content, "baseline.windowClasses", baselineWindowClasses);
        append(content, "editor.actionWaitStarted", Boolean.toString(editorActionWaitStarted));
        append(content, "editor.actionWaitCompleted", Boolean.toString(editorActionWaitCompleted));
        append(content, "editor.actionWaitElapsedMillis", Long.toString(editorActionWaitElapsedMillis));
        append(content, "ok.dispatchStarted", Boolean.toString(okDispatchStarted));
        append(content, "ok.dispatchReturned", Boolean.toString(okDispatchReturned));
        append(content, "ok.dispatchElapsedMillis", Long.toString(okDispatchElapsedMillis));
        append(content, "ok.actionWaitStarted", Boolean.toString(okActionWaitStarted));
        append(content, "ok.actionWaitCompleted", Boolean.toString(okActionWaitCompleted));
        append(content, "ok.actionWaitElapsedMillis", Long.toString(okActionWaitElapsedMillis));
        append(content, "freeze.started", Boolean.toString(freezeStarted));
        append(content, "freeze.completed", Boolean.toString(freezeCompleted));
        append(content, "payload.writeStarted", Boolean.toString(payloadWriteStarted));
        append(content, "payload.writeCompleted", Boolean.toString(payloadWriteCompleted));
        append(content, "payload.reopenHashVerified", Boolean.toString(payloadReopenVerified));
        append(content, "payload.hash", payloadHash);
        append(content, "canonical.writeStarted", Boolean.toString(canonicalWriteStarted));
        append(content, "canonical.complete", Boolean.toString(canonicalComplete));
        append(content, "canonical.failed", Boolean.toString(canonicalFailed));
        append(content, "nativeExit.started", Boolean.toString(nativeExitStarted));
        append(content, "nativeExit.returned", Boolean.toString(nativeExitReturned));
        append(content, "nativeExit.clickMillis", Long.toString(nativeExitClickMillis));
        append(content, "exit.windowSamples", Integer.toString(exitWindowSamples));
        append(content, "exit.windowCount", Integer.toString(exitWindowCount));
        append(content, "exit.windowClasses", exitWindowClasses);
        append(content, "exit.nonDaemonThreadCount", Integer.toString(exitNonDaemonThreadCount));
        append(content, "exit.nonDaemonThreadNames", exitNonDaemonThreadNames);
        append(content, "exit.promptSeen", Boolean.toString(exitPromptSeen));
        append(content, "exit.promptAnswer", exitPromptAnswer);
        append(content, "exit.promptButtonCount", Integer.toString(exitPromptButtonCount));
        append(content, "exit.promptClasses", exitPromptClasses);
        append(content, "exit.promptMillis", Long.toString(exitPromptMillis));
        append(content, "layout.dialogSeen", Boolean.toString(layoutDialogSeen));
        append(content, "layout.dialogMillis", Long.toString(layoutDialogMillis));
        append(content, "layout.scaleText", layoutScaleText);
        append(content, "layout.applyElapsedMillis", Long.toString(layoutApplyElapsedMillis));
        append(content, "layout.closed", Boolean.toString(layoutClosed));
        final Path parent = target.getParent();
        if (parent == null) return;
        Path temporary = null;
        boolean moved = false;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, ".t040-stage-", ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } catch (Exception ignored) {
            // Stage evidence is bounded diagnostic state; action failure remains authoritative.
        } finally {
            if (!moved && temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // No unbounded diagnostic fallback.
                }
            }
        }
    }

    private static void append(final StringBuilder content, final String key, final String value) {
        content.append(key).append('=').append(value).append('\n');
    }

    private static long elapsedMillis(final long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }
}
