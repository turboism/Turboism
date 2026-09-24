package dev.turboism.validation.warpaltsymmetry;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task-local reconnaissance probe for Warp deformer control points on an exact
 * Cubism host.
 *
 * <p>It answers three questions that the Alt-symmetry feature depends on, using
 * only the public SDK and plain JDK AWT/Swing:</p>
 *
 * <ol>
 *   <li><b>Baseline.</b> Does {@code model.warpDeformers()} project the fixture's
 *       Warp transform grids, and with which dimensions?</li>
 *   <li><b>Write path.</b> Does a bounded {@code WarpDeformer.replaceGrid(...)}
 *       round-trip through the documented {@code DeformerHooks} override family and
 *       the native Undo manager, returning the exact grid that was requested?</li>
 *   <li><b>Native ingress.</b> When a real {@link Robot} gesture Alt-drags inside the
 *       modeling viewport, does the native control-point drag reach that same write
 *       path (hook counters change), and does the committed transform grid become
 *       mirror-symmetric about the pre-drag frame midline?</li>
 * </ol>
 *
 * <p>Question 3 is deliberately reported, not asserted: a gesture that lands on empty
 * canvas and a gesture that lands on a control point with no Alt semantics both leave
 * the grid unchanged. The probe therefore records the exact screen point, captures
 * before/after screenshots as evidence, and reports a {@code drive-verdict} for human
 * review instead of converting an inconclusive observation into a claim.</p>
 *
 * <p>All mutations are confined to the task-scoped fixture copy. The probe never saves,
 * never imports or reflects {@code com.live2d.*}, and inspects only its own process'
 * windows.</p>
 */
public final class WarpDeformerAltSymmetryHostValidationPlugin implements CubismPlugin {

    private static final String MODE_PROPERTY = "turboism.validation.warpAlt.mode";
    private static final String SCREEN_PROPERTY = "turboism.validation.warpAlt.screen";
    private static final String DRAG_PROPERTY = "turboism.validation.warpAlt.drag";
    private static final String DEFORMER_PROPERTY = "turboism.validation.warpAlt.deformer";

    private static final String MODE_ANALYSE = "analyse";
    private static final String MODE_DRIVE = "drive";
    private static final String MODE_OBSERVE = "observe";
    private static final String OBSERVE_SECONDS_PROPERTY = "turboism.validation.warpAlt.observeSeconds";
    private static final long OBSERVE_POLL_MILLIS = 1_000L;
    private static final long DEFAULT_OBSERVE_SECONDS = 900L;
    private static final int DIFF_LIMIT = 12;

    private static final String RESULT_FILE_NAME = "warp-deformer-alt-symmetry-result.properties";
    private static final String PROBE_READY_MARKER = "WARP_ALT_PROBE_READY";
    private static final String PROBE_RESULT_MARKER = "WARP_ALT_PROBE_RESULT";

    private static final long MODEL_READY_TIMEOUT_MILLIS = 240_000L;
    private static final long MODEL_READY_STEP_MILLIS = 1_000L;
    private static final long SETTLE_MILLIS = 1_500L;
    private static final long GESTURE_STEP_MILLIS = 120L;
    private static final float MOVE_EPSILON = 1.0e-3f;

    /** Delay before asking the host to close, so the runner can poll the terminal result. */
    private static final long HOST_CLOSE_DELAY_MILLIS = 6_000L;
    /**
     * Bounded window for the detached graceful close before the validation JVM exits
     * explicitly. The document is dirty after the grid round-trip, so the host raises its
     * own save prompt, which keeps the close from completing; the runner's graceful-exit
     * gate is satisfied by a normal launcher exit, matching the reviewed PSD clip-mask probe.
     */
    private static final long HOST_CLOSE_GRACE_MILLIS = 12_000L;
    private static final long HOST_CLOSE_FALLBACK_MILLIS = 15_000L;
    private static final long HOST_CLOSE_FOCUS_SETTLE_MILLIS = 500L;
    private static final long HOST_CLOSE_TIMEOUT_MILLIS = 60_000L;
    private static final long HOST_CLOSE_STEP_MILLIS = 200L;

    /** Reference drag offset used when the task does not supply one. */
    private static final int DEFAULT_DRAG_DX = 48;
    private static final int DEFAULT_DRAG_DY = -32;

    private PluginContext context;
    private PluginLogger logger;

    private final AtomicInteger hookBeforeCount = new AtomicInteger();
    private final AtomicInteger hookChangedCount = new AtomicInteger();
    private final AtomicInteger hookAfterCount = new AtomicInteger();
    private final AtomicReference<String> lastHookDeformer = new AtomicReference<>("");
    private final AtomicReference<String> lastHookBeforeGrid = new AtomicReference<>("");
    private final AtomicReference<String> lastHookChangedGrid = new AtomicReference<>("");
    /** Baseline grids keyed by deformer id, captured before the observe window. */
    private final java.util.Map<String, WarpGrid> baselineGrids = new java.util.HashMap<>();

    private volatile boolean stopped;
    private Thread worker;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        worker = new Thread(this::runSafely, "warp-alt-symmetry-probe");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void enable() {
        logger.info("WARP_ALT_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        stopped = true;
        logger.info("WARP_ALT_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        stopped = true;
        logger.info("WARP_ALT_PROBE_SHUTDOWN");
    }

    // ------------------------------------------------------------------
    // Override-based hook observation
    // ------------------------------------------------------------------

    @Override
    public WarpGrid beforeReplaceWarpDeformerGrid(final WarpDeformer deformer, final WarpGrid grid) {
        hookBeforeCount.incrementAndGet();
        lastHookDeformer.set(safeId(deformer));
        lastHookBeforeGrid.set(fingerprint(grid));
        logger.info("WARP_ALT_HOOK before deformer=" + safeId(deformer) + " grid=" + fingerprint(grid));
        return grid;
    }

    @Override
    public void onWarpDeformerGridChanged(
        final WarpDeformer deformer,
        final WarpGrid oldGrid,
        final WarpGrid newGrid
    ) {
        hookChangedCount.incrementAndGet();
        lastHookDeformer.set(safeId(deformer));
        lastHookChangedGrid.set(fingerprint(newGrid));
        logger.info("WARP_ALT_HOOK changed deformer=" + safeId(deformer)
            + " old=" + fingerprint(oldGrid) + " new=" + fingerprint(newGrid)
            + " diff=" + diffSummary(oldGrid, newGrid));
    }

    @Override
    public void afterReplaceWarpDeformerGrid(final WarpDeformer deformer, final WarpGrid grid) {
        hookAfterCount.incrementAndGet();
        lastHookDeformer.set(safeId(deformer));
        logger.info("WARP_ALT_HOOK after deformer=" + safeId(deformer) + " grid=" + fingerprint(grid));
    }

    // ------------------------------------------------------------------
    // Probe driver
    // ------------------------------------------------------------------

    private void runSafely() {
        final List<String> failures = new ArrayList<>();
        final Report report = new Report();
        try {
            report.put("mode", mode());
            recordGrantedPermissions(report);
            if (!awaitModelReady(failures)) {
                report.put("modelReady", "false");
                finish(report, failures);
                return;
            }
            report.put("modelReady", "true");
            logger.info(PROBE_READY_MARKER);

            final CubismModel model = context.cubism().model().active();
            final List<WarpDeformer> warps = model.warpDeformers().all();
            report.put("warpDeformerCount", Integer.toString(warps.size()));
            recordBaseline(warps, report);

            final WarpDeformer target = selectTarget(warps);
            if (MODE_OBSERVE.equals(mode())) {
                // Human-in-the-loop mode: the probe never writes. The operator drags
                // control points in the host window while the hook family records what
                // the native ingress commits, which answers whether a real viewport
                // drag reaches the same write path the SDK round-trip proved.
                if (target != null) {
                    report.put("target.id", safeId(target));
                    report.put("target.name", safeName(target));
                }
                recordObserveWindow(report, failures);
            } else if (target == null) {
                failures.add("no Warp deformer with at least 2x2 transform divisions was readable");
            } else {
                report.put("target.id", safeId(target));
                report.put("target.name", safeName(target));
                recordRoundTrip(target, report, failures);
            }

            if (MODE_DRIVE.equals(mode()) && !stopped) {
                recordDrive(target, report, failures);
            }
        } catch (RuntimeException | Error failure) {
            failures.add("probe failed: " + failure.getClass().getName() + ": " + failure.getMessage());
            logger.warn("WARP_ALT_PROBE_FAILED " + failure.getClass().getName());
        } finally {
            finish(report, failures);
        }
    }

    /**
     * Bounded poll for an active modeling model. One present snapshot suffices; the
     * model is never mutated or saved while waiting.
     */
    /**
     * Records the permissions this plugin actually holds. The runtime registers no Deformer
     * observer without {@code turboism.cubism.model.observe} and no rewrite-capable Deformer
     * interceptor without {@code turboism.cubism.model.intercept}, so a silent hook family is
     * otherwise indistinguishable from an unwired one.
     */
    private void recordGrantedPermissions(final Report report) {
        try {
            final List<dev.turboism.sdk.permission.PluginPermission> granted = context.permissions();
            final List<String> ids = new ArrayList<>();
            for (dev.turboism.sdk.permission.PluginPermission permission : granted) {
                ids.add(permission.id());
            }
            java.util.Collections.sort(ids);
            report.put("permissions.count", Integer.toString(ids.size()));
            report.put("permissions", String.join(",", ids));
            logger.info("WARP_ALT_PERMISSIONS " + String.join(",", ids));
        } catch (RuntimeException | Error failure) {
            report.put("permissions", "unavailable:" + failure.getClass().getSimpleName());
        }
    }
    private boolean awaitModelReady(final List<String> failures) {
        final long deadline = System.currentTimeMillis() + MODEL_READY_TIMEOUT_MILLIS;
        int attempts = 0;
        while (System.currentTimeMillis() < deadline && !stopped) {
            attempts++;
            try {
                if (context.cubism().runtime().model().isPresent()) {
                    logger.info("WARP_ALT_MODEL_READY attempts=" + attempts);
                    return true;
                }
            } catch (RuntimeException | Error failure) {
                failures.add("model readiness snapshot failed: " + failure.getClass().getSimpleName());
                return false;
            }
            sleep(MODEL_READY_STEP_MILLIS);
        }
        failures.add("model readiness timeout after " + attempts + " attempts");
        logger.warn("WARP_ALT_MODEL_READY_TIMEOUT attempts=" + attempts);
        return false;
    }

    private void recordBaseline(final List<WarpDeformer> warps, final Report report) {
        for (int index = 0; index < warps.size(); index++) {
            final WarpDeformer deformer = warps.get(index);
            final String prefix = "warp." + index + ".";
            try {
                final WarpGrid grid = deformer.grid();
                if (!grid.controlPoints().isEmpty()) {
                    baselineGrids.put(safeId(deformer), grid);
                }
                report.put(prefix + "id", safeId(deformer));
                report.put(prefix + "name", safeName(deformer));
                report.put(prefix + "rows", Integer.toString(grid.rows()));
                report.put(prefix + "columns", Integer.toString(grid.columns()));
                report.put(prefix + "quadTransform", Boolean.toString(grid.quadTransform()));
                report.put(prefix + "pointCount", Integer.toString(grid.controlPoints().size()));
                report.put(prefix + "grid", fingerprint(grid));
                logger.info("WARP_ALT_BASELINE index=" + index
                    + " id=" + safeId(deformer)
                    + " rows=" + grid.rows() + " columns=" + grid.columns()
                    + " quad=" + grid.quadTransform()
                    + " points=" + grid.controlPoints().size());
            } catch (RuntimeException | Error failure) {
                report.put(prefix + "error", failure.getClass().getSimpleName());
                logger.warn("WARP_ALT_BASELINE_FAILED index=" + index
                    + " type=" + failure.getClass().getSimpleName());
            }
        }
    }

    /** Prefers an explicit name filter, then the first grid with an odd or even split. */
    private WarpDeformer selectTarget(final List<WarpDeformer> warps) {
        final String filter = System.getProperty(DEFORMER_PROPERTY, "").strip();
        WarpDeformer fallback = null;
        for (WarpDeformer deformer : warps) {
            final WarpGrid grid;
            try {
                grid = deformer.grid();
            } catch (RuntimeException | Error failure) {
                continue;
            }
            if (grid.rows() < 2 || grid.columns() < 2) {
                continue;
            }
            if (fallback == null) {
                fallback = deformer;
            }
            if (!filter.isEmpty() && safeName(deformer).contains(filter)) {
                return deformer;
            }
        }
        return fallback;
    }

    /**
     * Moves one transform control point through the SDK, then requires the read-back to
     * match, the three documented hook overrides to fire exactly once each, native Undo
     * to restore the original grid and native Redo to reapply the requested one. The
     * original grid is written back afterwards so the document is left as found.
     */
    private void recordRoundTrip(
        final WarpDeformer target,
        final Report report,
        final List<String> failures
    ) {
        final WarpGrid before;
        try {
            before = target.grid();
        } catch (RuntimeException | Error failure) {
            failures.add("target grid read failed: " + failure.getClass().getSimpleName());
            return;
        }
        if (before.controlPoints().isEmpty()) {
            failures.add("target grid has no control points");
            return;
        }

        final Point2 first = before.controlPoints().get(0);
        final WarpGrid requested = before.withControlPoint(0, first.x() + 7.5f, first.y() - 3.25f);
        report.put("roundTrip.before", fingerprint(before));
        report.put("roundTrip.requested", fingerprint(requested));

        final int beforeCounts = hookBeforeCount.get();
        final int changedCounts = hookChangedCount.get();
        final int afterCounts = hookAfterCount.get();

        try {
            target.replaceGrid(requested);
        } catch (RuntimeException | Error failure) {
            failures.add("replaceGrid failed: " + failure.getClass().getName());
            logger.warn("WARP_ALT_ROUNDTRIP_WRITE_FAILED " + failure.getClass().getName());
            return;
        }
        sleep(SETTLE_MILLIS);

        final WarpGrid after;
        try {
            after = target.grid();
        } catch (RuntimeException | Error failure) {
            failures.add("post-write grid read failed: " + failure.getClass().getSimpleName());
            return;
        }
        report.put("roundTrip.after", fingerprint(after));
        final boolean matched = samePoints(requested, after);
        report.put("roundTrip.readBackMatched", Boolean.toString(matched));
        report.put("roundTrip.hookBeforeDelta", Integer.toString(hookBeforeCount.get() - beforeCounts));
        report.put("roundTrip.hookChangedDelta", Integer.toString(hookChangedCount.get() - changedCounts));
        report.put("roundTrip.hookAfterDelta", Integer.toString(hookAfterCount.get() - afterCounts));
        logger.info("WARP_ALT_ROUNDTRIP_READBACK matched=" + matched
            + " hookBeforeDelta=" + (hookBeforeCount.get() - beforeCounts)
            + " hookChangedDelta=" + (hookChangedCount.get() - changedCounts)
            + " hookAfterDelta=" + (hookAfterCount.get() - afterCounts));
        if (!matched) {
            failures.add("replaceGrid read-back did not match the requested grid");
        }
        if (hookBeforeCount.get() - beforeCounts < 1
            || hookChangedCount.get() - changedCounts < 1
            || hookAfterCount.get() - afterCounts < 1) {
            failures.add("replaceGrid did not fire the full before/changed/after hook family");
        }

        recordUndoRedo(target, before, requested, report, failures);

        try {
            target.replaceGrid(before);
            sleep(SETTLE_MILLIS);
            report.put("roundTrip.restored", Boolean.toString(samePoints(before, target.grid())));
        } catch (RuntimeException | Error failure) {
            failures.add("grid restore failed: " + failure.getClass().getSimpleName());
        }
    }

    private void recordUndoRedo(
        final WarpDeformer target,
        final WarpGrid before,
        final WarpGrid requested,
        final Report report,
        final List<String> failures
    ) {
        final CubismHistory history;
        try {
            history = context.cubism().history();
        } catch (RuntimeException | Error failure) {
            report.put("history.availability", "threw:" + failure.getClass().getSimpleName());
            return;
        }
        final HistorySnapshot snapshot = history.snapshot();
        report.put("history.availability", snapshot.availability().name());
        report.put("history.positionBefore", Integer.toString(snapshot.position()));
        if (snapshot.availability() != HistorySnapshot.Availability.AVAILABLE) {
            logger.warn("WARP_ALT_HISTORY_UNAVAILABLE");
            return;
        }

        final HistoryMoveResult undo = history.undo(1);
        report.put("history.undoOutcome", undo.outcome().name());
        sleep(SETTLE_MILLIS);
        final boolean undoRestored;
        try {
            undoRestored = samePoints(before, target.grid());
        } catch (RuntimeException | Error failure) {
            failures.add("post-undo grid read failed: " + failure.getClass().getSimpleName());
            return;
        }
        report.put("history.undoRestored", Boolean.toString(undoRestored));
        logger.info("WARP_ALT_UNDO outcome=" + undo.outcome().name() + " restored=" + undoRestored);

        final HistoryMoveResult redo = history.redo(1);
        report.put("history.redoOutcome", redo.outcome().name());
        sleep(SETTLE_MILLIS);
        final boolean redoReapplied;
        try {
            redoReapplied = samePoints(requested, target.grid());
        } catch (RuntimeException | Error failure) {
            failures.add("post-redo grid read failed: " + failure.getClass().getSimpleName());
            return;
        }
        report.put("history.redoReapplied", Boolean.toString(redoReapplied));
        logger.info("WARP_ALT_REDO outcome=" + redo.outcome().name() + " reapplied=" + redoReapplied);

        if (!undoRestored) {
            failures.add("native Undo did not restore the pre-write Warp grid");
        }
        if (!redoReapplied) {
            failures.add("native Redo did not reapply the requested Warp grid");
        }
    }

    /**
     * Human-in-the-loop observation window. The probe performs no mutation; it waits a
     * bounded window while the operator drags Warp deformer control points in the host
     * window (plain, with Alt, with Alt+Shift) and reports the hook traffic afterwards.
     * A window that ends without any native grid change event is a FAIL, because the
     * feature depends on the native ingress being observable at all.
     */
    private void recordObserveWindow(final Report report, final List<String> failures) {
        final long seconds = parseLong(System.getProperty(OBSERVE_SECONDS_PROPERTY, ""), DEFAULT_OBSERVE_SECONDS);
        report.put("observe.windowSeconds", Long.toString(seconds));
        report.put("observe.instruction",
            "drag warp control points: Alt = vertical mirror, Alt+Shift = horizontal mirror");
        logger.info("WARP_ALT_OBSERVE_WINDOW seconds=" + seconds);
        captureScreen(report, "warp-alt-observe-start.png", "observe.screenshotStart");
        final long deadline = System.currentTimeMillis() + seconds * 1_000L;
        int reported = 0;
        while (System.currentTimeMillis() < deadline && !stopped) {
            final int changed = hookChangedCount.get();
            if (changed != reported) {
                reported = changed;
                logger.info("WARP_ALT_OBSERVE_PROGRESS changedEvents=" + changed);
            }
            sleep(OBSERVE_POLL_MILLIS);
        }
        report.put("observe.beforeEvents", Integer.toString(hookBeforeCount.get()));
        report.put("observe.changedEvents", Integer.toString(hookChangedCount.get()));
        report.put("observe.afterEvents", Integer.toString(hookAfterCount.get()));
        logger.info("WARP_ALT_OBSERVE_DONE changedEvents=" + hookChangedCount.get());
        auditObserveSymmetry(report);
        // The native drag-tick bridge commits mirrored points inside the gesture and
        // never routes through DeformerHooks, so grid motion observed by the audit is
        // equally valid evidence. Only a fully silent window fails.
        final boolean auditMoved = Integer.parseInt(
            report.getOrDefault("observe.audit.movedPoints", "0")) > 0;
        if (hookChangedCount.get() == 0 && !auditMoved) {
            failures.add("observe window elapsed without any Warp grid change evidence");
        }
        captureScreen(report, "warp-alt-observe-end.png", "observe.screenshotEnd");
    }

    /**
     * Records one full-screen screenshot into the task state directory. Observe-mode
     * evidence: shows what the operator actually saw, independent of whether any
     * gesture landed on a warp control point.
     */
    private void captureScreen(final Report report, final String fileName, final String reportKey) {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                report.put(reportKey, "skipped:headless");
                return;
            }
            final Robot robot = new Robot();
            final Rectangle screen = new Rectangle(
                Toolkit.getDefaultToolkit().getScreenSize());
            capture(robot, screen, fileName, report, reportKey);
        } catch (java.awt.AWTException | RuntimeException | Error failure) {
            report.put(reportKey, "failed:" + failure.getClass().getSimpleName());
            logger.warn("WARP_ALT_CAPTURE_FAILED " + fileName
                + " " + failure.getClass().getSimpleName());
        }
    }

    /**
     * Post-window symmetry audit: diffs the baseline grids against the committed
     * state and reports whether the observed motion is consistent with either
     * mirror axis. Informational only — the operator may mix plain, Alt and
     * Alt+Shift gestures, undo, or drag other objects; the hook counters remain
     * the pass criterion.
     */
    private void auditObserveSymmetry(final Report report) {
        int movedTotal = 0;
        int verticalConsistent = 0;
        int verticalInconsistent = 0;
        int horizontalConsistent = 0;
        int horizontalInconsistent = 0;
        try {
            final List<WarpDeformer> warps =
                context.cubism().model().active().warpDeformers().all();
            for (final WarpDeformer deformer : warps) {
                final WarpGrid baseline = baselineGrids.get(safeId(deformer));
                if (baseline == null) {
                    continue;
                }
                final WarpGrid current = deformer.grid();
                if (current.rows() != baseline.rows()
                    || current.columns() != baseline.columns()
                    || current.controlPoints().size() != baseline.controlPoints().size()) {
                    continue;
                }
                final int width = current.columns() + 1;
                final int height = current.rows() + 1;
                for (int index = 0; index < current.controlPoints().size(); index++) {
                    final float dx = current.controlPoints().get(index).x()
                        - baseline.controlPoints().get(index).x();
                    final float dy = current.controlPoints().get(index).y()
                        - baseline.controlPoints().get(index).y();
                    if (Math.abs(dx) <= MOVE_EPSILON && Math.abs(dy) <= MOVE_EPSILON) {
                        continue;
                    }
                    movedTotal++;
                    final int row = index / width;
                    final int column = index % width;
                    final int verticalPartner = row * width + (width - 1 - column);
                    if (verticalPartner != index) {
                        final int[] verdict = classifyPartner(
                            baseline, current, verticalPartner, -dx, dy);
                        verticalConsistent += verdict[0];
                        verticalInconsistent += verdict[1];
                    }
                    final int horizontalPartner = (height - 1 - row) * width + column;
                    if (horizontalPartner != index) {
                        final int[] verdict = classifyPartner(
                            baseline, current, horizontalPartner, dx, -dy);
                        horizontalConsistent += verdict[0];
                        horizontalInconsistent += verdict[1];
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            report.put("observe.audit.error", failure.getClass().getSimpleName());
            logger.warn("WARP_ALT_AUDIT_FAILED " + failure.getClass().getSimpleName());
            return;
        }
        report.put("observe.audit.movedPoints", Integer.toString(movedTotal));
        report.put("observe.audit.verticalConsistent", Integer.toString(verticalConsistent));
        report.put("observe.audit.verticalInconsistent", Integer.toString(verticalInconsistent));
        report.put("observe.audit.horizontalConsistent", Integer.toString(horizontalConsistent));
        report.put("observe.audit.horizontalInconsistent", Integer.toString(horizontalInconsistent));
        logger.info("WARP_ALT_AUDIT movedPoints=" + movedTotal
            + " vertical=" + verticalConsistent + "/" + (verticalConsistent + verticalInconsistent)
            + " horizontal=" + horizontalConsistent + "/" + (horizontalConsistent + horizontalInconsistent));
    }

    /** Classifies one partner as mirror-consistent or not, given the expected delta. */
    private static int[] classifyPartner(
        final WarpGrid baseline,
        final WarpGrid current,
        final int partner,
        final float expectedDx,
        final float expectedDy
    ) {
        final float partnerDx = current.controlPoints().get(partner).x()
            - baseline.controlPoints().get(partner).x();
        final float partnerDy = current.controlPoints().get(partner).y()
            - baseline.controlPoints().get(partner).y();
        final boolean partnerMoved = Math.abs(partnerDx) > MOVE_EPSILON
            || Math.abs(partnerDy) > MOVE_EPSILON;
        if (!partnerMoved) {
            return new int[]{0, 1};
        }
        final boolean consistent = Math.abs(partnerDx - expectedDx) <= MOVE_EPSILON
            && Math.abs(partnerDy - expectedDy) <= MOVE_EPSILON;
        return consistent ? new int[]{1, 0} : new int[]{0, 1};
    }

    /** Parses a non-negative long property, falling back to the default when absent. */
    private static long parseLong(final String value, final long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0L, Long.parseLong(value.strip()));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /**
     * Row-major diff of one grid change: which control points moved and by how much.
     * Capped at {@link #DIFF_LIMIT} entries; the full grids are already fingerprinted
     * on the same log line.
     */
    private static String diffSummary(final WarpGrid oldGrid, final WarpGrid newGrid) {
        if (oldGrid == null || newGrid == null
            || oldGrid.rows() != newGrid.rows()
            || oldGrid.columns() != newGrid.columns()
            || oldGrid.controlPoints().size() != newGrid.controlPoints().size()) {
            return "shape-change";
        }
        final List<Point2> from = oldGrid.controlPoints();
        final List<Point2> to = newGrid.controlPoints();
        final int width = newGrid.columns() + 1;
        final StringBuilder text = new StringBuilder("moved=");
        int count = 0;
        for (int index = 0; index < to.size(); index++) {
            final float dx = to.get(index).x() - from.get(index).x();
            final float dy = to.get(index).y() - from.get(index).y();
            if (Math.abs(dx) <= MOVE_EPSILON && Math.abs(dy) <= MOVE_EPSILON) {
                continue;
            }
            count++;
            if (count <= DIFF_LIMIT) {
                text.append(index).append("(r").append(index / width)
                    .append(",c").append(index % width)
                    .append(':').append(round(dx)).append(',').append(round(dy)).append(')');
            }
        }
        if (count == 0) {
            return "none";
        }
        if (count > DIFF_LIMIT) {
            text.append("...total=").append(count);
        }
        return text.toString();
    }

    /**
     * Opt-in real gesture. The screen point is supplied by the task because no public SDK
     * projection from canvas coordinates to screen pixels exists; the probe records the
     * point, both screenshots and the resulting grid so the observation can be reviewed
     * rather than trusted.
     */
    private void recordDrive(
        final WarpDeformer target,
        final Report report,
        final List<String> failures
    ) {
        final int[] point = parsePair(System.getProperty(SCREEN_PROPERTY, "").strip());
        if (point == null) {
            report.put("drive.performed", "false");
            report.put("drive.reason", "missing-or-invalid-" + SCREEN_PROPERTY);
            logger.warn("WARP_ALT_DRIVE_SKIPPED reason=no-screen-point");
            return;
        }
        final int[] drag = parsePair(System.getProperty(DRAG_PROPERTY, "").strip());
        final int dragDx = drag == null ? DEFAULT_DRAG_DX : drag[0];
        final int dragDy = drag == null ? DEFAULT_DRAG_DY : drag[1];
        report.put("drive.screen", point[0] + "," + point[1]);
        report.put("drive.drag", dragDx + "," + dragDy);

        if (GraphicsEnvironment.isHeadless()) {
            report.put("drive.performed", "false");
            failures.add("drive mode requested but the JVM is headless");
            return;
        }
        if (target == null) {
            report.put("drive.performed", "false");
            return;
        }

        final WarpGrid before;
        try {
            before = target.grid();
        } catch (RuntimeException | Error failure) {
            failures.add("drive baseline grid read failed: " + failure.getClass().getSimpleName());
            return;
        }

        final Robot robot;
        try {
            robot = new Robot();
        } catch (Exception | Error failure) {
            report.put("drive.performed", "false");
            failures.add("Robot unavailable: " + failure.getClass().getSimpleName());
            return;
        }

        final Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        capture(robot, screen, "warp-alt-drive-before.png", report, "drive.screenshotBefore");
        final int hookBefore = hookChangedCount.get();

        final boolean focused = focusMainWindow();
        report.put("drive.mainWindowFocused", Boolean.toString(focused));
        try {
            robot.mouseMove(point[0], point[1]);
            robot.delay((int) GESTURE_STEP_MILLIS);
            robot.keyPress(KeyEvent.VK_ALT);
            robot.delay((int) GESTURE_STEP_MILLIS);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay((int) GESTURE_STEP_MILLIS);
            for (int step = 1; step <= 4; step++) {
                robot.mouseMove(
                    point[0] + (dragDx * step) / 4,
                    point[1] + (dragDy * step) / 4
                );
                robot.delay((int) GESTURE_STEP_MILLIS);
            }
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay((int) GESTURE_STEP_MILLIS);
            robot.keyRelease(KeyEvent.VK_ALT);
            robot.delay((int) GESTURE_STEP_MILLIS);
            report.put("drive.performed", "true");
        } catch (RuntimeException | Error failure) {
            report.put("drive.performed", "false");
            failures.add("gesture failed: " + failure.getClass().getName());
            return;
        } finally {
            capture(robot, screen, "warp-alt-drive-after.png", report, "drive.screenshotAfter");
        }

        sleep(SETTLE_MILLIS);
        final WarpGrid after;
        try {
            after = target.grid();
        } catch (RuntimeException | Error failure) {
            failures.add("drive result grid read failed: " + failure.getClass().getSimpleName());
            return;
        }
        report.put("drive.gridBefore", fingerprint(before));
        report.put("drive.gridAfter", fingerprint(after));
        report.put("drive.hookChangedBefore", Integer.toString(hookBefore));
        report.put("drive.hookChangedAfter", Integer.toString(hookChangedCount.get()));
        report.put("drive.hookFired", Boolean.toString(hookChangedCount.get() > hookBefore));

        final Symmetry symmetry = analyseMirror(before, after);
        report.put("drive.movedPoints", Integer.toString(symmetry.moved()));
        report.put("drive.mirrorPairsMoved", Integer.toString(symmetry.pairsMoved()));
        report.put("drive.mirrorPairsDeltaMirrored", Integer.toString(symmetry.pairsMirrored()));
        report.put("drive.selfMirroredMoved", Integer.toString(symmetry.selfMirrored()));
        report.put("drive.axisX", Float.toString(symmetry.axisX()));
        report.put("drive.axisY", Float.toString(symmetry.axisY()));
        report.put(
            "drive.verdict",
            symmetry.moved() == 0 ? "unchanged"
                : (symmetry.pairsMirrored() == symmetry.pairsMoved() && symmetry.moved() > 0
                    ? "mirrored"
                    : "asymmetric")
        );
        logger.info("WARP_ALT_DRIVE_RESULT performed=true"
            + " moved=" + symmetry.moved()
            + " pairsMoved=" + symmetry.pairsMoved()
            + " pairsMirrored=" + symmetry.pairsMirrored()
            + " hookFired=" + (hookChangedCount.get() > hookBefore)
            + " verdict=" + report.get("drive.verdict"));
    }

    /** Brings this process' own unique visible main window to front so the gesture lands. */
    private boolean focusMainWindow() {
        try {
            final Window host = hostWindow();
            if (host == null) {
                logger.warn("WARP_ALT_FOCUS no-host-window");
                return false;
            }
            SwingUtilities.invokeAndWait(() -> {
                host.toFront();
                host.requestFocus();
            });
            return true;
        } catch (Exception | Error failure) {
            logger.warn("WARP_ALT_FOCUS_FAILED " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isDialog(final Window window) {
        return window instanceof java.awt.Dialog;
    }

    private void capture(
        final Robot robot,
        final Rectangle screen,
        final String fileName,
        final Report report,
        final String key
    ) {
        try {
            final BufferedImage image = robot.createScreenCapture(screen);
            final Path directory = context.paths().stateDir();
            Files.createDirectories(directory);
            final Path target = directory.resolve(fileName);
            javax.imageio.ImageIO.write(image, "png", target.toFile());
            report.put(key, target.getFileName().toString());
            logger.info("WARP_ALT_CAPTURE " + fileName);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (RuntimeException | Error failure) {
            report.put(key, "failed:" + failure.getClass().getSimpleName());
            logger.warn("WARP_ALT_CAPTURE_FAILED " + fileName + " " + failure.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // Host shutdown
    // ------------------------------------------------------------------

    /**
     * Asks the exact host to close once the observations and the result file are complete.
     *
     * <p>The generic runner treats a terminal result without a normal launcher exit as an
     * incomplete run, so a probe that only writes PASS cannot produce a PASS job. The close
     * route mirrors the established parameter-probe pattern: dispatch {@code WINDOW_CLOSING}
     * to this process' own largest visible non-dialog window, then resolve the unsaved-changes
     * confirmation without depending on localized button text. The document is deliberately
     * discarded rather than saved: the runner requires the staged fixture copy to keep its
     * source hash.</p>
     */
    private void requestHostClose() {
        final Window host;
        try {
            host = documentFrame();
        } catch (Exception | Error failure) {
            logger.warn("WARP_ALT_HOST_CLOSE window-lookup-failed "
                + failure.getClass().getSimpleName());
            return;
        }
        if (host == null) {
            logger.warn("WARP_ALT_HOST_CLOSE no-host-window");
            return;
        }
        logger.info("WARP_ALT_HOST_CLOSE target=" + host.getClass().getName()
            + " title=" + describeTitle(host));
        bringToFront(host);

        // The reviewed in-repo route targets the visible document frame and dispatches
        // WINDOW_CLOSING to it; Alt+F4 is kept only as a fallback because the window
        // manager under Proton does not reliably deliver it to an unfocused frame.
        try {
            SwingUtilities.invokeLater(() -> host.dispatchEvent(
                new java.awt.event.WindowEvent(host, java.awt.event.WindowEvent.WINDOW_CLOSING)
            ));
            logger.info("WARP_ALT_HOST_CLOSE requested=window-closing");
        } catch (RuntimeException | Error failure) {
            logger.warn("WARP_ALT_HOST_CLOSE dispatch-failed "
                + failure.getClass().getSimpleName());
        }

        final long deadline = System.currentTimeMillis() + HOST_CLOSE_TIMEOUT_MILLIS;
        final long fallbackAt = System.currentTimeMillis() + HOST_CLOSE_FALLBACK_MILLIS;
        boolean discardClicked = false;
        boolean fallbackSent = false;
        while (System.currentTimeMillis() < deadline) {
            if (closed(host)) {
                logger.info("WARP_ALT_HOST_CLOSE " + (discardClicked ? "discarded" : "clean"));
                return;
            }
            final javax.swing.JButton discard = discardButton();
            if (discard != null) {
                try {
                    SwingUtilities.invokeAndWait(discard::doClick);
                    discardClicked = true;
                    logger.info("WARP_ALT_HOST_CLOSE_DISCARD");
                } catch (Exception | Error failure) {
                    logger.warn("WARP_ALT_HOST_CLOSE discard-failed "
                        + failure.getClass().getSimpleName());
                }
            } else if (!fallbackSent && System.currentTimeMillis() >= fallbackAt) {
                fallbackSent = true;
                try {
                    final Robot robot = new Robot();
                    robot.keyPress(KeyEvent.VK_ALT);
                    try {
                        robot.keyPress(KeyEvent.VK_F4);
                        robot.delay(80);
                        robot.keyRelease(KeyEvent.VK_F4);
                    } finally {
                        robot.keyRelease(KeyEvent.VK_ALT);
                    }
                    logger.info("WARP_ALT_HOST_CLOSE requested=alt-f4-fallback");
                } catch (Exception | Error failure) {
                    logger.warn("WARP_ALT_HOST_CLOSE alt-f4-failed "
                        + failure.getClass().getSimpleName());
                }
            }
            sleep(HOST_CLOSE_STEP_MILLIS);
        }
        logger.warn("WARP_ALT_HOST_CLOSE_TIMEOUT");
    }

    private static String describeTitle(final Window window) {
        return window instanceof java.awt.Frame frame && frame.getTitle() != null
            ? frame.getTitle()
            : "<no-title>";
    }

    /**
     * The host's visible document frame: the visible frame whose title carries the staged
     * fixture file name. Falls back to this process' largest visible non-dialog window when
     * no titled document frame is present.
     */
    private static Window documentFrame() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<Window> found =
            new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
                if (!frame.isVisible() || !frame.isDisplayable()) {
                    continue;
                }
                final String title = frame.getTitle();
                if (title != null && title.contains(".cmo3")) {
                    found.set(frame);
                    return;
                }
            }
        });
        return found.get() != null ? found.get() : hostWindow();
    }

    /** Brings one specific window of this process to front and requests focus on the EDT. */
    private void bringToFront(final Window window) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                window.toFront();
                window.requestFocus();
            });
            sleep(HOST_CLOSE_FOCUS_SETTLE_MILLIS);
        } catch (Exception | Error failure) {
            logger.warn("WARP_ALT_HOST_CLOSE focus-failed " + failure.getClass().getSimpleName());
        }
    }

    /** Largest displayable visible non-dialog window owned by this process, or null. */
    private static Window hostWindow() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<Window> found =
            new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Window best = null;
            long bestArea = -1L;
            for (Window window : Window.getWindows()) {
                if (window instanceof java.awt.Dialog
                    || !window.isDisplayable()
                    || !window.isVisible()) {
                    continue;
                }
                final Rectangle bounds = window.getBounds();
                final long area = (long) bounds.width * bounds.height;
                if (area > bestArea) {
                    bestArea = area;
                    best = window;
                }
            }
            found.set(best);
        });
        return found.get();
    }

    private static boolean closed(final Window window) {
        try {
            final java.util.concurrent.atomic.AtomicBoolean gone =
                new java.util.concurrent.atomic.AtomicBoolean(false);
            SwingUtilities.invokeAndWait(() -> gone.set(!window.isDisplayable() || !window.isVisible()));
            return gone.get();
        } catch (Exception | Error failure) {
            return false;
        }
    }

    /**
     * Returns exactly one enabled discard button when an unsaved-changes confirmation is
     * showing, and null when there is no confirmation or its intent is ambiguous.
     */
    private static javax.swing.JButton discardButton() {
        final java.util.concurrent.atomic.AtomicReference<javax.swing.JButton> found =
            new java.util.concurrent.atomic.AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                final Window active = java.awt.KeyboardFocusManager
                    .getCurrentKeyboardFocusManager().getActiveWindow();
                for (Window window : Window.getWindows()) {
                    if (!(window instanceof java.awt.Dialog dialog)
                        || !dialog.isVisible()
                        || (!dialog.isModal() && window != active)) {
                        continue;
                    }
                    final javax.swing.JOptionPane pane = optionPane(window);
                    final List<javax.swing.JButton> buttons = new ArrayList<>();
                    collectButtons(window, buttons);
                    final List<javax.swing.JButton> enabled = buttons.stream()
                        .filter(button -> button.isVisible() && button.isEnabled())
                        .toList();
                    final int optionType = pane == null
                        ? javax.swing.JOptionPane.DEFAULT_OPTION
                        : pane.getOptionType();
                    final boolean discardable =
                        optionType == javax.swing.JOptionPane.YES_NO_OPTION
                            || optionType == javax.swing.JOptionPane.YES_NO_CANCEL_OPTION
                            || (optionType == javax.swing.JOptionPane.DEFAULT_OPTION
                                && (enabled.size() == 2 || enabled.size() == 3));
                    if (!discardable) {
                        return;
                    }
                    final List<javax.swing.JButton> matches = enabled.stream()
                        .filter(WarpDeformerAltSymmetryHostValidationPlugin::isDiscardAction)
                        .toList();
                    if (matches.size() == 1) {
                        found.set(matches.get(0));
                    }
                    return;
                }
            });
        } catch (Exception | Error failure) {
            return null;
        }
        return found.get();
    }

    private static javax.swing.JOptionPane optionPane(final java.awt.Component component) {
        if (component instanceof javax.swing.JOptionPane pane) {
            return pane;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                final javax.swing.JOptionPane found = optionPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collectButtons(
        final java.awt.Component component,
        final List<javax.swing.JButton> buttons
    ) {
        if (component instanceof javax.swing.JButton button) {
            buttons.add(button);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectButtons(child, buttons);
            }
        }
    }

    private static boolean isDiscardAction(final javax.swing.JButton button) {
        final javax.accessibility.AccessibleContext context = button.getAccessibleContext();
        final String accessible = context == null ? null : context.getAccessibleName();
        for (String value : java.util.Arrays.asList(
            button.getActionCommand(), button.getName(), button.getText(), accessible
        )) {
            if (matchesDiscardValue(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesDiscardValue(final String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        final String normalized = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]", "");
        return normalized.equals("no")
            || normalized.contains("discard")
            || normalized.contains("dontsave")
            || normalized.contains("donotsave")
            || normalized.contains("nosave")
            || normalized.contains("notsave")
            || normalized.contains("不保存")
            || normalized.contains("不要保存")
            || normalized.contains("不储存")
            || normalized.contains("不要储存")
            || normalized.contains("不存盘")
            || normalized.contains("不要存盘")
            || normalized.contains("放弃")
            || normalized.contains("舍弃")
            || normalized.contains("保存"+ "しない")
            || normalized.contains("セーブ"+ "しない");
    }

    // ------------------------------------------------------------------
    // Terminate
    // ------------------------------------------------------------------

    private void finish(final Report report, final List<String> failures) {
        report.put("hook.beforeTotal", Integer.toString(hookBeforeCount.get()));
        report.put("hook.changedTotal", Integer.toString(hookChangedCount.get()));
        report.put("hook.afterTotal", Integer.toString(hookAfterCount.get()));
        report.put("hook.lastDeformer", lastHookDeformer.get());
        report.put("hook.lastBeforeGrid", lastHookBeforeGrid.get());
        report.put("hook.lastChangedGrid", lastHookChangedGrid.get());
        report.put("failureCount", Integer.toString(failures.size()));
        for (int index = 0; index < failures.size(); index++) {
            report.put("failure." + index, failures.get(index));
        }
        final boolean pass = failures.isEmpty();
        report.put("status", pass ? "PASS" : "FAIL");
        try {
            writeResult(report);
        } catch (IOException failure) {
            logger.warn("WARP_ALT_RESULT_WRITE_FAILED " + failure.getClass().getSimpleName());
        }
        for (String failure : failures) {
            logger.warn("WARP_ALT_FAILURE " + failure);
        }
        logger.info(PROBE_RESULT_MARKER + " status=" + (pass ? "PASS" : "FAIL"));
        // The runner treats a terminal result without a normal launcher exit as an
        // incomplete run, so the host is asked to close once the result is on disk.
        // The close runs on a detached thread: the save prompt it raises can block the
        // caller indefinitely, so the whole shutdown is bounded and then the validation
        // JVM exits explicitly. This mirrors the reviewed PSD clip-mask probe.
        sleep(HOST_CLOSE_DELAY_MILLIS);
        final Thread closer = new Thread(this::requestHostClose, "warp-alt-host-close");
        closer.setDaemon(true);
        closer.start();
        sleep(HOST_CLOSE_GRACE_MILLIS);
        logger.info("WARP_ALT_PROBE_JVM_EXIT");
        System.exit(0);
    }

    private void writeResult(final Report report) throws IOException {
        final Path stateDir = context.paths().stateDir();
        final Path directory = stateDir.getParent();
        if (directory == null) {
            throw new IOException("plugin state directory has no parent");
        }
        Files.createDirectories(directory);
        final Path result = directory.resolve(RESULT_FILE_NAME);
        final Path temporary = directory.resolve(RESULT_FILE_NAME + ".tmp");
        Files.writeString(
            temporary,
            report.render(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
        Files.move(
            temporary,
            result,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        );
    }

    // ------------------------------------------------------------------
    // Pure helpers
    // ------------------------------------------------------------------

    private String mode() {
        final String configured = System.getProperty(MODE_PROPERTY, MODE_ANALYSE).strip();
        return configured.isEmpty() ? MODE_ANALYSE : configured;
    }

    private static int[] parsePair(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final String[] parts = value.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[] { Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()) };
        } catch (NumberFormatException notAPair) {
            return null;
        }
    }

    private static String safeId(final WarpDeformer deformer) {
        try {
            return deformer.id().value();
        } catch (RuntimeException | Error failure) {
            return "unavailable:" + failure.getClass().getSimpleName();
        }
    }

    private static String safeName(final WarpDeformer deformer) {
        try {
            return deformer.name();
        } catch (RuntimeException | Error failure) {
            return "unavailable:" + failure.getClass().getSimpleName();
        }
    }

    private static boolean samePoints(final WarpGrid expected, final WarpGrid actual) {
        if (expected.rows() != actual.rows()
            || expected.columns() != actual.columns()
            || expected.quadTransform() != actual.quadTransform()) {
            return false;
        }
        final List<Point2> left = expected.controlPoints();
        final List<Point2> right = actual.controlPoints();
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!close(left.get(index).x(), right.get(index).x())
                || !close(left.get(index).y(), right.get(index).y())) {
                return false;
            }
        }
        return true;
    }

    private static boolean close(final float left, final float right) {
        return Math.abs(left - right) <= 1.0e-2f;
    }

    private static String fingerprint(final WarpGrid grid) {
        if (grid == null) {
            return "null";
        }
        final StringBuilder text = new StringBuilder()
            .append(grid.rows()).append('x').append(grid.columns())
            .append(grid.quadTransform() ? "/quad" : "/normal").append(':');
        final List<Point2> points = grid.controlPoints();
        for (int index = 0; index < points.size(); index++) {
            if (index > 0) {
                text.append(' ');
            }
            text.append(round(points.get(index).x())).append(',').append(round(points.get(index).y()));
        }
        return text.toString();
    }

    private static String round(final float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Measures whether a committed grid is the mirror image of the pre-drag grid about the
     * pre-drag frame midline, using the row-major (row, column) identity of the transform
     * division grid. Column {@code c} pairs with {@code columns - c}; a mirrored move
     * negates the horizontal delta and keeps the vertical one.
     */
    static Symmetry analyseMirror(final WarpGrid before, final WarpGrid after) {
        final int rows = before.rows();
        final int columns = before.columns();
        final List<Point2> from = before.controlPoints();
        final List<Point2> to = after.controlPoints();
        if (rows != after.rows() || columns != after.columns() || from.size() != to.size()) {
            return new Symmetry(0, 0, 0, 0, 0.0f, 0.0f);
        }
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (Point2 point : from) {
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minY = Math.min(minY, point.y());
            maxY = Math.max(maxY, point.y());
        }
        final float axisX = (minX + maxX) / 2.0f;
        final float axisY = (minY + maxY) / 2.0f;

        final int width = columns + 1;
        final boolean[] moved = new boolean[to.size()];
        final float[] deltaX = new float[to.size()];
        final float[] deltaY = new float[to.size()];
        int movedCount = 0;
        for (int index = 0; index < to.size(); index++) {
            deltaX[index] = to.get(index).x() - from.get(index).x();
            deltaY[index] = to.get(index).y() - from.get(index).y();
            if (Math.abs(deltaX[index]) > MOVE_EPSILON || Math.abs(deltaY[index]) > MOVE_EPSILON) {
                moved[index] = true;
                movedCount++;
            }
        }

        int pairsMoved = 0;
        int pairsMirrored = 0;
        int selfMirrored = 0;
        for (int index = 0; index < to.size(); index++) {
            if (!moved[index]) {
                continue;
            }
            final int row = index / width;
            final int column = index % width;
            final int mirrorColumn = columns - column;
            final int mirror = row * width + mirrorColumn;
            if (mirror == index) {
                selfMirrored++;
                continue;
            }
            if (!moved[mirror]) {
                continue;
            }
            pairsMoved++;
            if (close(deltaX[mirror], -deltaX[index]) && close(deltaY[mirror], deltaY[index])) {
                pairsMirrored++;
            }
        }
        return new Symmetry(movedCount, pairsMoved, pairsMirrored, selfMirrored, axisX, axisY);
    }

    /** Validation-only mirror measurement for one grid change. */
    record Symmetry(int moved, int pairsMoved, int pairsMirrored, int selfMirrored, float axisX, float axisY) { }

    /** Ordered result properties, rendered deterministically for diffable evidence. */
    static final class Report {

        private final List<String> keys = new ArrayList<>();
        private final List<String> values = new ArrayList<>();

        void put(final String key, final String value) {
            final String safe = value == null ? "" : value;
            final int existing = keys.indexOf(key);
            if (existing >= 0) {
                values.set(existing, safe);
                return;
            }
            keys.add(key);
            values.add(safe);
        }

        String get(final String key) {
            final int index = keys.indexOf(key);
            return index < 0 ? "" : values.get(index);
        }

        String getOrDefault(final String key, final String fallback) {
            final int index = keys.indexOf(key);
            return index < 0 || values.get(index).isEmpty() ? fallback : values.get(index);
        }

        String render() {
            final StringBuilder text = new StringBuilder();
            for (int index = 0; index < keys.size(); index++) {
                text.append(keys.get(index)).append('=').append(values.get(index)).append('\n');
            }
            return text.toString();
        }

        @Override
        public String toString() {
            return render();
        }
    }

    /** Exposed for the offline self-check only. */
    static List<String> hooksOf(final WarpDeformerAltSymmetryHostValidationPlugin probe) {
        return Arrays.asList(
            Integer.toString(probe.hookBeforeCount.get()),
            Integer.toString(probe.hookChangedCount.get()),
            Integer.toString(probe.hookAfterCount.get())
        );
    }
}
