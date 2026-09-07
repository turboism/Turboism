package dev.turboism.validation.boundingbox;

import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Deterministic self-check for the in-process task-window selection
 * precondition: the pure window-relative coordinate calculation
 * ({@code 942x1012 -> (110,720)}, proportional scaling, bounds rejection), the
 * pure unique-main-window selection rule (visible/showing/non-dialog/large
 * window; absence and ambiguity fail closed), and the content-pane geometry
 * seam (authoritative screen-space content bounds derived from the focused
 * window's Swing content pane; the frozen reference maps 942x1012 -> (110,720),
 * translated and proportionally scaled content bounds map correctly,
 * non-positive and implausibly small extents below the 480 main-window minimum
 * side fail closed with the 480 boundary itself accepted, targets outside the
 * content area fail closed, and the rejected derivations stay distinguishable
 * as negative controls: scaling over the decorated outer extent yields
 * (107,734) and the D5 observed insets-derived client (0,-10,942x1042) yields
 * (110,731), never the authoritative (110,720)). Also covers the bounded
 * active-model
 * readiness poll: delayed false/false/true returns READY only on the third
 * attempt with the exact attempts, permanent absence times out, stopped,
 * interrupted and snapshot-failure states fail closed distinctly, and no
 * next stage runs before readiness success. Runs with no AWT windows, no
 * Robot and no production-scale timing thresholds (the interrupt path uses
 * one ~20 ms worker sleep). Lives in the plugin package so package-private members of
 * {@link BoundingBoxOverlayHostValidationPlugin} stay test-only internal; it
 * compiles into the self-check output, never the probe JAR.
 */
public final class SelectionPreconditionSelfCheck {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int CHECKS = 0;

    public static void main(final String[] args) {
        referencePointScalesExactly();
        proportionalScaling();
        boundsRejected();
        mainWindowCandidatePredicate();
        uniqueMainWindowSelected();
        ambiguousMainWindowRejected();
        absentMainWindowRejected();
        contentGeometryIdentity();
        contentGeometryTranslated();
        contentGeometryProportionalScaling();
        contentGeometryInvalidRejected();
        contentGeometryImplausibleRejected();
        targetOutsideContentRejected();
        rejectedGeometryDerivationsDistinguishable();
        readinessConstants();
        readinessDelayedSequenceDoesNotPassEarly();
        readinessPermanentAbsenceTimesOut();
        readinessStoppedFailsClosed();
        readinessInterruptedFailsClosed();
        readinessSnapshotFailureFailsClosed();
        readinessFailClosedOutcomesDistinct();
        if (FAILURES.isEmpty()) {
            System.out.println("BOUNDING_BOX_SELECTION_SELFCHECK status=PASS checks=" + CHECKS);
            return;
        }
        for (String failure : FAILURES) {
            System.err.println("BOUNDING_BOX_SELECTION_SELFCHECK failure: " + failure);
        }
        System.err.println("BOUNDING_BOX_SELECTION_SELFCHECK status=FAIL checks=" + FAILURES.size());
        Runtime.getRuntime().exit(1);
    }

    /** The established reference geometry must map to itself exactly. */
    private static void referencePointScalesExactly() {
        check("reference x scales to itself at reference width",
            scale(BoundingBoxOverlayHostValidationPlugin.REF_SELECTION_X,
                BoundingBoxOverlayHostValidationPlugin.REF_WINDOW_WIDTH,
                BoundingBoxOverlayHostValidationPlugin.REF_WINDOW_WIDTH)
                == BoundingBoxOverlayHostValidationPlugin.REF_SELECTION_X,
            "actual=" + scale(BoundingBoxOverlayHostValidationPlugin.REF_SELECTION_X,
                BoundingBoxOverlayHostValidationPlugin.REF_WINDOW_WIDTH,
                BoundingBoxOverlayHostValidationPlugin.REF_WINDOW_WIDTH));
        check("reference y scales to itself at reference height",
            scale(BoundingBoxOverlayHostValidationPlugin.REF_SELECTION_Y,
                BoundingBoxOverlayHostValidationPlugin.REF_WINDOW_HEIGHT,
                BoundingBoxOverlayHostValidationPlugin.REF_WINDOW_HEIGHT)
                == BoundingBoxOverlayHostValidationPlugin.REF_SELECTION_Y,
            "actual=" + scale(BoundingBoxOverlayHostValidationPlugin.REF_SELECTION_Y,
                BoundingBoxOverlayHostValidationPlugin.REF_WINDOW_HEIGHT,
                BoundingBoxOverlayHostValidationPlugin.REF_WINDOW_HEIGHT));
    }

    /** Proportional scaling of the established reference point. */
    private static void proportionalScaling() {
        check("doubled width scales x 110 -> 220", scale(110, 942, 1884) == 220,
            "actual=" + scale(110, 942, 1884));
        check("doubled height scales y 720 -> 1440", scale(720, 1012, 2024) == 1440,
            "actual=" + scale(720, 1012, 2024));
        check("half width scales x 110 -> 55", scale(110, 942, 471) == 55,
            "actual=" + scale(110, 942, 471));
    }

    /** Invalid references and non-positive extents must be rejected. */
    private static void boundsRejected() {
        check("reference equal to extent rejected", throwsOnScale(110, 110, 110), "accepted");
        check("negative reference rejected", throwsOnScale(-1, 942, 942), "accepted");
        check("zero reference extent rejected", throwsOnScale(0, 0, 942), "accepted");
        check("zero target extent rejected", throwsOnScale(110, 942, 0), "accepted");
        check("negative target extent rejected", throwsOnScale(110, 942, -10), "accepted");
    }

    private static boolean throwsOnScale(final int reference, final int referenceExtent, final int targetExtent) {
        try {
            scale(reference, referenceExtent, targetExtent);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    /** The main-window candidacy predicate itself. */
    private static void mainWindowCandidatePredicate() {
        check("visible showing non-dialog large window is a candidate",
            candidate(true, true, false, 1200, 1000), "not candidate");
        check("hidden window is not a candidate",
            !candidate(false, false, false, 1200, 1000), "candidate");
        check("dialog is not a candidate",
            !candidate(true, true, true, 1200, 1000), "candidate");
        check("not-showing window is not a candidate",
            !candidate(true, false, false, 1200, 1000), "candidate");
        check("too-small window is not a candidate",
            !candidate(true, true, false, 400, 300), "candidate");
        check("window below the minimum side is not a candidate",
            !candidate(true, true, false,
                BoundingBoxOverlayHostValidationPlugin.MIN_MAIN_WINDOW_SIDE - 1, 1000),
            "candidate");
    }

    private static boolean candidate(final boolean visible, final boolean showing, final boolean dialog,
        final int width, final int height) {
        return new BoundingBoxOverlayHostValidationPlugin.WindowFacts(visible, showing, dialog, width, height)
            .isMainCandidate();
    }

    /** Exactly one main-window candidate must select cleanly. */
    private static void uniqueMainWindowSelected() {
        final List<BoundingBoxOverlayHostValidationPlugin.WindowFacts> facts = new ArrayList<>();
        facts.add(new BoundingBoxOverlayHostValidationPlugin.WindowFacts(false, false, false, 1200, 1000));
        facts.add(new BoundingBoxOverlayHostValidationPlugin.WindowFacts(true, true, false, 1200, 1000));
        facts.add(new BoundingBoxOverlayHostValidationPlugin.WindowFacts(true, true, true, 500, 400));
        final String failure = BoundingBoxOverlayHostValidationPlugin.mainWindowSelectionFailure(facts);
        check("exactly one candidate selects cleanly", failure == null, "failure=" + failure);
    }

    /** Several candidates fail closed as ambiguous. */
    private static void ambiguousMainWindowRejected() {
        final List<BoundingBoxOverlayHostValidationPlugin.WindowFacts> facts = new ArrayList<>();
        facts.add(new BoundingBoxOverlayHostValidationPlugin.WindowFacts(true, true, false, 1200, 1000));
        facts.add(new BoundingBoxOverlayHostValidationPlugin.WindowFacts(true, true, false, 1000, 800));
        final String failure = BoundingBoxOverlayHostValidationPlugin.mainWindowSelectionFailure(facts);
        check("two candidates fail closed as ambiguous",
            failure != null && failure.startsWith("ambiguous-main-window"),
            "failure=" + failure);
    }

    /** Zero candidates fail closed as absent. */
    private static void absentMainWindowRejected() {
        final List<BoundingBoxOverlayHostValidationPlugin.WindowFacts> facts = new ArrayList<>();
        facts.add(new BoundingBoxOverlayHostValidationPlugin.WindowFacts(true, false, false, 1200, 1000));
        facts.add(new BoundingBoxOverlayHostValidationPlugin.WindowFacts(true, true, true, 900, 700));
        final String failure = BoundingBoxOverlayHostValidationPlugin.mainWindowSelectionFailure(facts);
        check("no candidate fails closed", "no-visible-main-window".equals(failure), "failure=" + failure);
        check("empty window list fails closed",
            "no-visible-main-window".equals(
                BoundingBoxOverlayHostValidationPlugin.mainWindowSelectionFailure(new ArrayList<>())),
            "accepted empty list");
    }

    /** Authoritative content geometry maps the frozen reference to (110,720). */
    private static void contentGeometryIdentity() {
        final BoundingBoxOverlayHostValidationPlugin.ClientGeometry content =
            contentGeometry(0, 0, 942, 1012);
        check("authoritative content origin", content.x() == 0 && content.y() == 0, content.toString());
        check("authoritative content extent", content.width() == 942 && content.height() == 1012, content.toString());
        final Point point = clientPoint(content, 110, 720, 942, 1012);
        check("authoritative content point is (110,720)", point.x == 110 && point.y == 720, point.toString());
    }

    /** Translated content bounds offset the scaled point by the content origin. */
    private static void contentGeometryTranslated() {
        final BoundingBoxOverlayHostValidationPlugin.ClientGeometry content =
            contentGeometry(100, 200, 942, 1012);
        check("translated content origin", content.x() == 100 && content.y() == 200, content.toString());
        check("translated content extent", content.width() == 942 && content.height() == 1012, content.toString());
        final Point point = clientPoint(content, 110, 720, 942, 1012);
        check("translated content point is (210,920)", point.x == 210 && point.y == 920, point.toString());
    }

    /** Proportional scaling inside the content area. */
    private static void contentGeometryProportionalScaling() {
        final BoundingBoxOverlayHostValidationPlugin.ClientGeometry content =
            contentGeometry(0, 0, 1884, 2024);
        final Point point = clientPoint(content, 110, 720, 942, 1012);
        check("doubled content point is (220,1440)", point.x == 220 && point.y == 1440, point.toString());
    }

    /** Non-positive content extents fail closed. */
    private static void contentGeometryInvalidRejected() {
        check("zero content width rejected", throwsOnContent(0, 0, 0, 1012), "accepted");
        check("zero content height rejected", throwsOnContent(0, 0, 942, 0), "accepted");
        check("negative content width rejected", throwsOnContent(0, 0, -1, 1012), "accepted");
        check("negative content height rejected", throwsOnContent(0, 0, 942, -1), "accepted");
    }

    /**
     * Implausibly small content extents (either side below the main-window
     * minimum side of 480) fail closed; the 480 boundary itself stays accepted.
     */
    private static void contentGeometryImplausibleRejected() {
        check("content width 479 rejected", throwsOnContent(0, 0, 479, 1012), "accepted");
        check("content height 479 rejected", throwsOnContent(0, 0, 942, 479), "accepted");
        final BoundingBoxOverlayHostValidationPlugin.ClientGeometry boundary =
            contentGeometry(0, 0, 480, 480);
        check("480 content boundary accepted",
            boundary.width() == 480 && boundary.height() == 480, boundary.toString());
    }

    /** A scaled target that lands on or beyond the content edge fails closed. */
    private static void targetOutsideContentRejected() {
        final BoundingBoxOverlayHostValidationPlugin.ClientGeometry content =
            new BoundingBoxOverlayHostValidationPlugin.ClientGeometry(0, 0, 400, 1012);
        check("scaled target at content edge rejected",
            throwsOnPoint(content, 941, 720, 942, 1012), "accepted");
        final BoundingBoxOverlayHostValidationPlugin.ClientGeometry tiny =
            new BoundingBoxOverlayHostValidationPlugin.ClientGeometry(0, 0, 200, 200);
        check("scaled target beyond content extent rejected",
            throwsOnPoint(tiny, 941, 720, 942, 1012), "accepted");
    }

    /**
     * Literal negative controls, replayed in-test because the obsolete
     * outer-minus-insets derivation no longer exists in production: scaling
     * (110,720) over the decorated outer extent 950x1046 still yields the
     * rejected D3 result (107,734), and the D5 observed outer bounds
     * (-4,-10,950x1046) with reported AWT insets (4,0,4,4) (left,top,right,
     * bottom) derive the misleading client (0,-10,942x1042) and point
     * (110,731); both stay distinguishable from the authoritative
     * content-mapped (110,720), so restoring the old derivation fails.
     */
    private static void rejectedGeometryDerivationsDistinguishable() {
        final Rectangle bounds = new Rectangle(-4, -10, 950, 1046);
        final int oldPx = bounds.x + scale(110, 942, bounds.width);
        final int oldPy = bounds.y + scale(720, 1012, bounds.height);
        check("old outer-bound mapping stays (107,734)", oldPx == 107 && oldPy == 734,
            "actual=(" + oldPx + "," + oldPy + ")");
        final Insets insets = new Insets(0, 4, 4, 4); // top,left,bottom,right as reported by the host
        final BoundingBoxOverlayHostValidationPlugin.ClientGeometry misleading =
            new BoundingBoxOverlayHostValidationPlugin.ClientGeometry(
                bounds.x + insets.left, bounds.y + insets.top,
                bounds.width - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom);
        check("misleading insets-derived client stays (0,-10,942x1042)",
            misleading.x() == 0 && misleading.y() == -10
                && misleading.width() == 942 && misleading.height() == 1042,
            misleading.toString());
        final Point wrong = clientPoint(misleading, 110, 720, 942, 1012);
        check("insets-derived point stays (110,731)", wrong.x == 110 && wrong.y == 731,
            "actual=(" + wrong.x + "," + wrong.y + ")");
        final Point authoritative = clientPoint(contentGeometry(0, 0, 942, 1012), 110, 720, 942, 1012);
        check("authoritative content point is (110,720)",
            authoritative.x == 110 && authoritative.y == 720,
            "actual=(" + authoritative.x + "," + authoritative.y + ")");
        check("authoritative content point differs from both rejected mappings",
            (authoritative.x != oldPx || authoritative.y != oldPy)
                && (authoritative.x != wrong.x || authoritative.y != wrong.y),
            "authoritative=(" + authoritative.x + "," + authoritative.y + ")"
                + " old=(" + oldPx + "," + oldPy + ")"
                + " insets=(" + wrong.x + "," + wrong.y + ")");
    }

    /** The readiness gate constants are pinned to the spec ceiling and bounded step. */
    private static void readinessConstants() {
        check("readiness timeout is exactly 60 s",
            BoundingBoxOverlayHostValidationPlugin.MODEL_READY_TIMEOUT_MILLIS == 60_000L,
            "timeout=" + BoundingBoxOverlayHostValidationPlugin.MODEL_READY_TIMEOUT_MILLIS);
        check("readiness polling step is bounded at 500 ms",
            BoundingBoxOverlayHostValidationPlugin.MODEL_READY_STEP_MILLIS == 500L,
            "step=" + BoundingBoxOverlayHostValidationPlugin.MODEL_READY_STEP_MILLIS);
    }

    /**
     * Delayed readiness (false, false, true) must return READY only on the
     * third attempt, sample exactly three times and permit the next stage
     * exactly once; an immediate-pass mutant (READY on attempt one) breaks the
     * attempts assertion and fails this self-check.
     */
    private static void readinessDelayedSequenceDoesNotPassEarly() {
        final boolean[] samples = {false, false, true};
        final AtomicInteger sampled = new AtomicInteger();
        final long[] clock = {0L};
        final int[] nextStage = {0};
        final BoundingBoxOverlayHostValidationPlugin.ModelReadiness readiness = poll(
            () -> samples[Math.min(sampled.getAndIncrement(), samples.length - 1)],
            () -> false,
            clockNanos(clock),
            FAKE_DEADLINE_NANOS,
            0L);
        if (readiness.ready()) {
            nextStage[0]++;
        }
        check("delayed readiness returns READY only on the third attempt",
            readiness.outcome() == BoundingBoxOverlayHostValidationPlugin.ReadinessOutcome.READY
                && readiness.attempts() == 3,
            "outcome=" + readiness.outcome() + " attempts=" + readiness.attempts());
        check("delayed readiness samples exactly three times",
            sampled.get() == 3, "sampled=" + sampled.get());
        check("next stage runs exactly once after readiness",
            nextStage[0] == 1, "nextStage=" + nextStage[0]);
    }

    /**
     * Permanently absent readiness must time out at the monotonic deadline
     * with the exact attempt count, the bounded duration evidence, and no
     * next stage.
     */
    private static void readinessPermanentAbsenceTimesOut() {
        final long[] clock = {0L};
        final int[] nextStage = {0};
        final BoundingBoxOverlayHostValidationPlugin.ModelReadiness readiness = poll(
            () -> {
                clock[0] += 10_000_000L; // each absent sample advances the fake clock
                return false;
            },
            () -> false,
            clockNanos(clock),
            25_000_000L, // reached after three absent samples (10, 20, 30 ms)
            0L);
        if (readiness.ready()) {
            nextStage[0]++;
        }
        check("permanently absent readiness times out with exact attempts",
            readiness.outcome() == BoundingBoxOverlayHostValidationPlugin.ReadinessOutcome.TIMEOUT
                && readiness.attempts() == 3,
            "outcome=" + readiness.outcome() + " attempts=" + readiness.attempts());
        check("timeout records the bounded duration",
            readiness.durationMillis() == 30L, "durationMillis=" + readiness.durationMillis());
        check("timeout never reaches the next stage",
            nextStage[0] == 0, "nextStage=" + nextStage[0]);
    }

    /**
     * A stopped probe fails closed immediately (zero attempts) and between
     * polls (after one absent sample), both distinctly from timeout and
     * never reaching the next stage.
     */
    private static void readinessStoppedFailsClosed() {
        final int[] nextStage = {0};
        final BoundingBoxOverlayHostValidationPlugin.ModelReadiness immediate = poll(
            () -> false,
            () -> true,
            clockNanos(new long[]{0L}),
            FAKE_DEADLINE_NANOS,
            0L);
        if (immediate.ready()) {
            nextStage[0]++;
        }
        check("stopped before polling fails closed with zero attempts",
            immediate.outcome() == BoundingBoxOverlayHostValidationPlugin.ReadinessOutcome.STOPPED
                && immediate.attempts() == 0,
            "outcome=" + immediate.outcome() + " attempts=" + immediate.attempts());
        final AtomicInteger samples = new AtomicInteger();
        final long[] clock = {0L};
        final BoundingBoxOverlayHostValidationPlugin.ModelReadiness midPoll = poll(
            () -> {
                samples.incrementAndGet();
                return false;
            },
            () -> samples.get() >= 1, // stop after the first absent sample
            clockNanos(clock),
            FAKE_DEADLINE_NANOS,
            0L);
        if (midPoll.ready()) {
            nextStage[0]++;
        }
        check("mid-poll stop fails closed after one attempt",
            midPoll.outcome() == BoundingBoxOverlayHostValidationPlugin.ReadinessOutcome.STOPPED
                && midPoll.attempts() == 1,
            "outcome=" + midPoll.outcome() + " attempts=" + midPoll.attempts());
        check("stopped paths never reach the next stage",
            nextStage[0] == 0, "nextStage=" + nextStage[0]);
    }

    /**
     * An interrupt during the bounded polling step fails closed with
     * INTERRUPTED; the real interruption is exercised with one tiny ~20 ms
     * worker sleep only.
     */
    private static void readinessInterruptedFailsClosed() {
        final BoundingBoxOverlayHostValidationPlugin.ModelReadiness[] holder = new BoundingBoxOverlayHostValidationPlugin.ModelReadiness[1];
        final Thread poller = new Thread(() -> holder[0] = poll(
            () -> false,
            () -> false,
            System::nanoTime,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(30L),
            20L));
        poller.start();
        try {
            Thread.sleep(10L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("self-check interrupted", interrupted);
        }
        poller.interrupt();
        try {
            poller.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("self-check interrupted while joining", interrupted);
        }
        final BoundingBoxOverlayHostValidationPlugin.ModelReadiness readiness = holder[0];
        check("interrupted poll terminates",
            readiness != null && !poller.isAlive(), "readiness=" + readiness);
        check("interrupted poll fails closed with INTERRUPTED",
            readiness != null
                && readiness.outcome() == BoundingBoxOverlayHostValidationPlugin.ReadinessOutcome.INTERRUPTED
                && readiness.attempts() == 1,
            "outcome=" + (readiness == null ? "null" : readiness.outcome())
                + " attempts=" + (readiness == null ? "?" : readiness.attempts()));
        check("interrupted path never reaches the next stage",
            readiness == null || !readiness.ready(), "readiness=" + readiness);
    }

    /**
     * A throwing snapshot fails closed with SNAPSHOT_FAILED and the exact
     * failure class simple name, and never reaches the next stage.
     */
    private static void readinessSnapshotFailureFailsClosed() {
        final int[] nextStage = {0};
        final BoundingBoxOverlayHostValidationPlugin.ModelReadiness readiness = poll(
            () -> {
                throw new IllegalStateException("boom");
            },
            () -> false,
            clockNanos(new long[]{0L}),
            FAKE_DEADLINE_NANOS,
            0L);
        if (readiness.ready()) {
            nextStage[0]++;
        }
        check("snapshot failure fails closed with SNAPSHOT_FAILED",
            readiness.outcome() == BoundingBoxOverlayHostValidationPlugin.ReadinessOutcome.SNAPSHOT_FAILED
                && readiness.attempts() == 1,
            "outcome=" + readiness.outcome() + " attempts=" + readiness.attempts());
        check("snapshot failure records the failure type",
            "IllegalStateException".equals(readiness.failureType()),
            "failureType=" + readiness.failureType());
        check("snapshot failure never reaches the next stage",
            nextStage[0] == 0, "nextStage=" + nextStage[0]);
    }

    /**
     * The fail-closed outcomes are pairwise distinct: one poller that
     * collapses stopped or snapshot-failure into timeout fails these
     * assertions.
     */
    private static void readinessFailClosedOutcomesDistinct() {
        final long[] clock = {0L};
        final BoundingBoxOverlayHostValidationPlugin.ModelReadiness timedOut = poll(
            () -> {
                clock[0] += 10_000_000L;
                return false;
            },
            () -> false,
            clockNanos(clock),
            25_000_000L,
            0L);
        final BoundingBoxOverlayHostValidationPlugin.ModelReadiness stopped = poll(
            () -> false,
            () -> true,
            clockNanos(clock),
            FAKE_DEADLINE_NANOS,
            0L);
        final BoundingBoxOverlayHostValidationPlugin.ModelReadiness failed = poll(
            () -> {
                throw new IllegalStateException("boom");
            },
            () -> false,
            clockNanos(clock),
            FAKE_DEADLINE_NANOS,
            0L);
        check("timeout and stopped outcomes differ",
            timedOut.outcome() != stopped.outcome(),
            "timeout=" + timedOut.outcome() + " stopped=" + stopped.outcome());
        check("stopped and snapshot-failure outcomes differ",
            stopped.outcome() != failed.outcome(),
            "stopped=" + stopped.outcome() + " snapshot=" + failed.outcome());
        check("timeout and snapshot-failure outcomes differ",
            timedOut.outcome() != failed.outcome(),
            "timeout=" + timedOut.outcome() + " snapshot=" + failed.outcome());
    }

    private static final long FAKE_DEADLINE_NANOS = 1_000_000_000L;

    private static LongSupplier clockNanos(final long[] clock) {
        return () -> clock[0];
    }

    private static BoundingBoxOverlayHostValidationPlugin.ModelReadiness poll(
        final BooleanSupplier present,
        final BooleanSupplier stopped,
        final LongSupplier clockNanos,
        final long deadlineNanos,
        final long stepMillis
    ) {
        return BoundingBoxOverlayHostValidationPlugin.pollModelReadiness(
            present, stopped, clockNanos, deadlineNanos, stepMillis);
    }

    private static boolean throwsOnContent(final int x, final int y, final int width, final int height) {
        try {
            contentGeometry(x, y, width, height);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean throwsOnPoint(
        final BoundingBoxOverlayHostValidationPlugin.ClientGeometry client,
        final int refX, final int refY, final int refWidth, final int refHeight
    ) {
        try {
            clientPoint(client, refX, refY, refWidth, refHeight);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static BoundingBoxOverlayHostValidationPlugin.ClientGeometry contentGeometry(
        final int x, final int y, final int width, final int height) {
        return BoundingBoxOverlayHostValidationPlugin.contentGeometry(x, y, width, height);
    }

    private static Point clientPoint(
        final BoundingBoxOverlayHostValidationPlugin.ClientGeometry client,
        final int refX, final int refY, final int refWidth, final int refHeight) {
        return BoundingBoxOverlayHostValidationPlugin.clientPoint(client, refX, refY, refWidth, refHeight);
    }

    private static int scale(final int reference, final int referenceExtent, final int targetExtent) {
        return BoundingBoxOverlayHostValidationPlugin.scaleRelative(reference, referenceExtent, targetExtent);
    }

    private static void check(final String name, final boolean condition, final String detail) {
        CHECKS++;
        if (!condition) {
            FAILURES.add(name + ": " + detail);
        }
    }
}
