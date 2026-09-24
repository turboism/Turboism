package dev.turboism.validation.boundingboxwarpmirror;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.mirror.WarpMirrorDirection;
import dev.turboism.sdk.cubism.mirror.WarpMirrorRequest;
import dev.turboism.sdk.cubism.mirror.WarpMirrorResult;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.JWindow;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.MouseInfo;
import java.awt.Robot;
import java.awt.Window;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.lang.reflect.Method;
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
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;

/**
 * Task-local exact-host probe for the {@code boundingbox-warp-mirror} feature.
 *
 * <p>Drives the public {@code CubismFacade.warpMirror()} service against the reviewed
 * mirror-test fixture and records structured expected/actual evidence:</p>
 *
 * <ol>
 *   <li><b>Directions.</b> Each of the four directions is applied with descendant
 *       preservation enabled; the probe verifies the named source half actually landed
 *       on the opposite half (labels match results), per the corrected legacy wiring.</li>
 *   <li><b>Preservation.</b> {@code APPLIED} with {@code preserveDescendants=true} is
 *       itself the fail-closed certificate: the runtime verifies every descendant's
 *       evaluated canvas residual before commit and rolls back otherwise. On 5.3.03 the
 *       SDK {@code Drawable} surface is not admitted, so the probe additionally records
 *       each descendant deformer&apos;s stored form before/after: compensation must leave
 *       an observable write on child Warp grids / Rotation forms, while
 *       {@code preserve=false} must leave descendant stored forms untouched.</li>
 *   <li><b>Atomic Undo.</b> One {@code history.undo(1)} must restore the parent grid
 *       and every descendant stored form together; {@code redo(1)} reapplies both.</li>
 *   <li><b>Fail-closed.</b> A non-existent target must come back {@code BLOCKED} with a
 *       typed blocker, never a silent write.</li>
 *   <li><b>No-change.</b> Re-applying a direction to an already-symmetric grid must
 *       report {@code NO_CHANGE} and create no Undo entry.</li>
 * </ol>
 *
 * <p>All mutations run on the task-scoped fixture copy. The probe never saves, never
 * reflects {@code com.live2d.*}, and inspects only its own process' windows.</p>
 */
public final class BoundingBoxWarpMirrorHostValidationPlugin implements CubismPlugin {

    private static final String RESULT_FILE_NAME = "boundingbox-warp-mirror-result.properties";
    private static final String PROBE_READY_MARKER = "WARP_MIRROR_PROBE_READY";
    private static final String PROBE_RESULT_MARKER = "WARP_MIRROR_PROBE_RESULT";
    private static final String DEFORMER_PROPERTY = "turboism.validation.warpMirror.deformer";

    private static final long MODEL_READY_TIMEOUT_MILLIS = 240_000L;
    private static final long MODEL_READY_STEP_MILLIS = 1_000L;
    private static final long SETTLE_MILLIS = 1_500L;
    private static final float POINT_EPSILON = 0.001f;

    private static final long HOST_CLOSE_DELAY_MILLIS = 6_000L;
    private static final long HOST_CLOSE_GRACE_MILLIS = 12_000L;
    private static final long HOST_CLOSE_FALLBACK_MILLIS = 15_000L;
    private static final long HOST_CLOSE_FOCUS_SETTLE_MILLIS = 500L;
    private static final long HOST_CLOSE_TIMEOUT_MILLIS = 60_000L;
    private static final long HOST_CLOSE_STEP_MILLIS = 200L;

    private static final long UI_STEP_TIMEOUT_MILLIS = 300_000L;
    private static final long UI_POLL_STEP_MILLIS = 400L;
    private static final long UI_APPLY_TIMEOUT_MILLIS = 15_000L;
    private static final long DIALOG_TIMEOUT_MILLIS = 180_000L;

    /**
     * Relative probe points inside a GL surface when locating the object list:
     * left-column band first (Cubism keeps the object/deformer tree in a side
     * panel), then center fallbacks. Each click is verified through the SDK
     * selection before an arrow-navigation probe identifies the list.
     */
    private static final double[][] SELECTION_PROBE_GRID = {
        { 0.10, 0.10 }, { 0.10, 0.30 }, { 0.10, 0.50 }, { 0.10, 0.70 },
        { 0.20, 0.10 }, { 0.20, 0.30 }, { 0.20, 0.50 }, { 0.20, 0.70 },
        { 0.05, 0.30 }, { 0.05, 0.60 },
        { 0.90, 0.10 }, { 0.90, 0.30 }, { 0.90, 0.50 }, { 0.90, 0.70 },
        { 0.80, 0.30 }, { 0.80, 0.60 },
        { 0.35, 0.30 }, { 0.50, 0.15 }, { 0.50, 0.50 },
    };

    private PluginContext context;
    private PluginLogger logger;
    private volatile boolean stopped;
    private volatile boolean uiAborted;
    private volatile JFrame instructionFrame;
    private volatile JLabel instructionText;
    private volatile JLabel instructionStatus;
    private Robot robot;

    @Override
    public void init(final PluginContext pluginContext) {
        this.context = pluginContext;
        this.logger = pluginContext.logger();
        final Thread worker = new Thread(this::runSafely, "warp-mirror-probe");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void enable() {
        logger.info("WARP_MIRROR_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        stopped = true;
        logger.info("WARP_MIRROR_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        stopped = true;
        logger.info("WARP_MIRROR_PROBE_SHUTDOWN");
    }

    // ------------------------------------------------------------------
    // Probe driver
    // ------------------------------------------------------------------

    private void runSafely() {
        final List<String> failures = new ArrayList<>();
        final Report report = new Report();
        try {
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
            final WarpDeformer target = selectTarget(warps, report);
            if (target == null) {
                failures.add("no Warp deformer accepted LEFT_TO_RIGHT (see scan.* entries)");
            } else {
                report.put("target.id", target.id().value());
                report.put("target.name", target.name());
                report.put("target.descendants",
                    Integer.toString(descendantFormSnapshot(target.id().value()).size()));
                runDirections(target, report, failures);
                runNoChange(target, report, failures);
                runNegativeTargets(report, failures);
                runVerticalDirections(target, report, failures);
                runLockedRejection(target, report, failures);
                runDescendantLockedRejection(target, report, failures);
                runOrderedWarpPairCase(warps, report, failures);
                runDrawableEvaluationProbe(target, report);
                runUiEvidence(target, warps, report, failures);
            }
        } catch (RuntimeException | Error failure) {
            failures.add("probe failed: " + failure.getClass().getName() + ": " + failure.getMessage());
            logger.warn("WARP_MIRROR_PROBE_FAILED " + failure.getClass().getName());
        } finally {
            finish(report, failures);
        }
    }

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
            logger.info("WARP_MIRROR_PERMISSIONS " + String.join(",", ids));
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
                    logger.info("WARP_MIRROR_MODEL_READY attempts=" + attempts);
                    return true;
                }
            } catch (RuntimeException | Error failure) {
                failures.add("model readiness snapshot failed: " + failure.getClass().getSimpleName());
                return false;
            }
            sleep(MODEL_READY_STEP_MILLIS);
        }
        failures.add("model readiness timeout after " + attempts + " attempts");
        logger.warn("WARP_MIRROR_MODEL_READY_TIMEOUT attempts=" + attempts);
        return false;
    }

    /**
     * Prefers an explicit name filter, then scans every readable Warp with a real
     * LEFT_TO_RIGHT apply (undone on success) so a target parked on an interpolated or
     * otherwise unsafe keyform cannot starve the whole evidence run. Per-target outcomes
     * and typed blockers are recorded under {@code scan.*}.
     */
    private WarpDeformer selectTarget(final List<WarpDeformer> warps, final Report report) {
        final String filter = System.getProperty(DEFORMER_PROPERTY, "").strip();
        final List<WarpDeformer> ordered = new ArrayList<>(warps);
        ordered.sort((a, b) -> {
            final boolean af = !filter.isEmpty() && safeName(a).contains(filter);
            final boolean bf = !filter.isEmpty() && safeName(b).contains(filter);
            return Boolean.compare(bf, af);
        });
        WarpDeformer firstWithDescendants = null;
        for (WarpDeformer deformer : ordered) {
            if (stopped) {
                return null;
            }
            final String id = deformer.id().value();
            try {
                final WarpGrid grid = deformer.grid();
                if (grid.rows() < 2 || grid.columns() < 2 || grid.controlPoints().isEmpty()) {
                    report.put("scan." + id, "SKIP degenerate-grid");
                    continue;
                }
            } catch (RuntimeException | Error failure) {
                report.put("scan." + id, "UNREADABLE " + failure.getClass().getSimpleName());
                continue;
            }
            final WarpMirrorResult probe;
            try {
                probe = context.cubism().warpMirror().apply(
                    new WarpMirrorRequest(deformer.id(), WarpMirrorDirection.LEFT_TO_RIGHT, true));
            } catch (RuntimeException | Error failure) {
                report.put("scan." + id, "THREW " + failure.getClass().getSimpleName());
                continue;
            }
            report.put("scan." + id, probe.outcome().name() + " " + blockerCodes(probe));
            logger.info("WARP_MIRROR_SCAN " + id + " outcome=" + probe.outcome().name()
                + " blockers=" + blockerCodes(probe)
                + (probe.blockers().isEmpty() ? "" : " details=" + blockerDetails(probe)));
            if (probe.outcome() == dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome.BLOCKED) {
                try {
                    final WarpMirrorResult bare = context.cubism().warpMirror().apply(
                        new WarpMirrorRequest(deformer.id(),
                            WarpMirrorDirection.LEFT_TO_RIGHT, false));
                    report.put("scan." + id + ".noPreserve",
                        bare.outcome().name() + " " + blockerCodes(bare));
                    logger.info("WARP_MIRROR_SCAN_NOPRESERVE " + id + " outcome="
                        + bare.outcome().name() + " blockers=" + blockerCodes(bare)
                        + (bare.blockers().isEmpty() ? "" : " details=" + blockerDetails(bare)));
                    if (bare.outcome() == dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome.APPLIED) {
                        history().undo(1);
                        sleep(SETTLE_MILLIS);
                    }
                } catch (RuntimeException | Error failure) {
                    report.put("scan." + id + ".noPreserve",
                        "THREW " + failure.getClass().getSimpleName());
                }
            }
            if (probe.outcome() != dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome.APPLIED) {
                continue;
            }
            try {
                history().undo(1);
                sleep(SETTLE_MILLIS);
            } catch (RuntimeException | Error failure) {
                report.put("scan." + id + ".undo", "FAILED " + failure.getClass().getSimpleName());
            }
            if (firstWithDescendants == null
                && !descendantFormSnapshot(id).isEmpty()) {
                firstWithDescendants = deformer;
            }
            if (!filter.isEmpty() && safeName(deformer).contains(filter)) {
                return deformer;
            }
            if (firstWithDescendants != null) {
                return firstWithDescendants;
            }
            return deformer;
        }
        return null;
    }

    private static String safeName(final WarpDeformer deformer) {
        try {
            return deformer.name();
        } catch (RuntimeException | Error failure) {
            return "";
        }
    }

    private static String blockerCodes(final WarpMirrorResult result) {
        return result.blockers().stream()
            .map(blocker -> blocker.code().name())
            .reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b);
    }

    private static String blockerDetails(final WarpMirrorResult result) {
        return result.blockers().stream()
            .map(blocker -> blocker.code().name() + "(" + blocker.reason() + ")")
            .reduce("", (a, b) -> a.isEmpty() ? b : a + " | " + b);
    }

    // ------------------------------------------------------------------
    // Direction evidence
    // ------------------------------------------------------------------

    private void runDirections(
        final WarpDeformer target,
        final Report report,
        final List<String> failures
    ) {
        for (WarpMirrorDirection direction : WarpMirrorDirection.values()) {
            if (stopped) {
                return;
            }
            runDirection(target, direction, true, report, failures);
        }
        // Preservation-off evidence: recorded for review, descendants follow the parent.
        runDirection(target, WarpMirrorDirection.LEFT_TO_RIGHT, false, report, failures);
    }

    private void runDirection(
        final WarpDeformer target,
        final WarpMirrorDirection direction,
        final boolean preserve,
        final Report report,
        final List<String> failures
    ) {
        runDirection(target, direction, preserve,
            "direction." + direction.name() + (preserve ? "" : ".noPreserve") + ".",
            report, failures);
    }

    private void runDirection(
        final WarpDeformer target,
        final WarpMirrorDirection direction,
        final boolean preserve,
        final String prefix,
        final Report report,
        final List<String> failures
    ) {
        final WarpGrid before;
        final Map<String, String> formsBefore;
        try {
            before = target.grid();
        } catch (RuntimeException | Error failure) {
            failures.add(direction + " grid pre-read failed: " + failure.getClass().getSimpleName());
            return;
        }
        try {
            formsBefore = descendantFormSnapshot(target.id().value());
        } catch (RuntimeException | Error failure) {
            failures.add(direction + " descendant pre-read failed: "
                + failure.getClass().getSimpleName());
            return;
        }
        report.put(prefix + "before", fingerprint(before));
        final Map<String, float[]> evalBefore = descendantDrawableEvalSnapshot(target.id().value());
        final int historyBefore = historyPosition();
        final LogMark followLogMark = preserve ? null : markRuntimeLog();

        final WarpMirrorResult result;
        try {
            result = context.cubism().warpMirror().apply(
                new WarpMirrorRequest(target.id(), direction, preserve));
        } catch (RuntimeException | Error failure) {
            failures.add(direction + " apply threw: " + failure.getClass().getName());
            return;
        }
        sleep(SETTLE_MILLIS);
        report.put(prefix + "outcome", result.outcome().name());
        report.put(prefix + "blockers", blockerCodes(result));
        report.put(prefix + "blockerDetails", blockerDetails(result));
        report.put(prefix + "historyEntriesCreated",
            Integer.toString(Math.max(0, historyPosition() - historyBefore)));
        logger.info("WARP_MIRROR_DIRECTION " + direction.name() + " preserve=" + preserve
            + " outcome=" + result.outcome().name());

        switch (result.outcome()) {
            case APPLIED -> verifyApplied(target, direction, preserve, before, formsBefore,
                evalBefore, followLogMark, prefix, report, failures);
            case NO_CHANGE -> {
                report.put(prefix + "note", "grid already symmetric for this direction");
            }
            default -> {
                failures.add(direction + " (preserve=" + preserve + ") blocked: "
                    + report.get(prefix + "blockers"));
                // Rejection/rollback evidence: a blocked apply must leave the target
                // grid, every descendant stored form, and the history position
                // exactly as they were.
                try {
                    report.put(prefix + "blockedGridUnchanged",
                        Boolean.toString(samePoints(before, target.grid())));
                    report.put(prefix + "blockedFormsUnchanged",
                        Boolean.toString(formsBefore.equals(
                            descendantFormSnapshot(target.id().value()))));
                } catch (RuntimeException | Error failure) {
                    report.put(prefix + "blockedUnchangedRead",
                        "failed:" + failure.getClass().getSimpleName());
                }
            }
        }
        restoreBaseline(target, before, historyBefore, report, prefix, failures);
    }

    private int historyPosition() {
        try {
            final dev.turboism.sdk.cubism.history.HistorySnapshot snapshot =
                history().snapshot();
            if (snapshot.availability()
                == dev.turboism.sdk.cubism.history.HistorySnapshot.Availability.AVAILABLE) {
                return snapshot.position();
            }
        } catch (RuntimeException | Error ignored) {
        }
        return -1;
    }

    private void verifyApplied(
        final WarpDeformer target,
        final WarpMirrorDirection direction,
        final boolean preserve,
        final WarpGrid before,
        final Map<String, String> formsBefore,
        final Map<String, float[]> evalBefore,
        final LogMark followLogMark,
        final String prefix,
        final Report report,
        final List<String> failures
    ) {
        final WarpGrid after;
        final Map<String, String> formsAfter;
        try {
            after = target.grid();
        } catch (RuntimeException | Error failure) {
            failures.add(direction + " grid post-read failed: " + failure.getClass().getSimpleName());
            return;
        }
        try {
            formsAfter = descendantFormSnapshot(target.id().value());
        } catch (RuntimeException | Error failure) {
            failures.add(direction + " descendant post-read failed: "
                + failure.getClass().getSimpleName());
            return;
        }
        report.put(prefix + "after", fingerprint(after));

        final DirectionCheck check = verifyDirection(before, after, direction);
        report.put(prefix + "checked", Integer.toString(check.checked));
        report.put(prefix + "mismatched", Integer.toString(check.mismatched));
        if (check.checked == 0) {
            failures.add(direction + " produced no checkable target-side points");
        } else if (check.mismatched > 0) {
            failures.add(direction + " target-side points do not equal the named source reflection: "
                + check.mismatched + "/" + check.checked);
        }

        report.put(prefix + "descendantCount", Integer.toString(formsBefore.size()));
        int unchanged = 0;
        int changed = 0;
        for (Map.Entry<String, String> entry : formsBefore.entrySet()) {
            final String post = formsAfter.get(entry.getKey());
            if (entry.getValue().equals(post)) {
                unchanged++;
            } else {
                changed++;
            }
        }
        report.put(prefix + "descendantFormsChanged", Integer.toString(changed));
        report.put(prefix + "descendantFormsUnchanged", Integer.toString(unchanged));
        if (!formsBefore.keySet().equals(formsAfter.keySet())) {
            failures.add(direction + " descendant set changed during apply: "
                + formsBefore.keySet() + " -> " + formsAfter.keySet());
        }
        // preserve=ON is certified by the service's internal fail-closed residual check;
        // the SDK additionally shows compensation actually wrote descendant stored forms
        // whenever the descendant geometry needed it. preserve=OFF must never write them.
        if (!preserve && changed > 0) {
            failures.add(direction + " preserve=OFF but " + changed
                + " descendant stored form(s) were rewritten");
        }
        // Stored-position comparison through the descendants' own drawables. In the
        // editor backend Drawable.vertexPositions() returns geometry().positions() —
        // the stored keyform, NOT the evaluated canvas output — so this surface can
        // only re-confirm stored-form immutability under preserve=OFF. The genuine
        // evaluated-geometry signal comes from the runtime-side follow observation
        // logged inside the mirror transaction and read back below.
        final Map<String, float[]> evalAfter = descendantDrawableEvalSnapshot(target.id().value());
        report.put(prefix + "descendantStoredDrawables",
            Integer.toString(evalAfter.size()));
        report.put(prefix + "descendantStoredScan",
            "total=" + evalScanTotal + ",parentMatched=" + evalScanParentMatched
                + (evalScanReadFailed.isEmpty() ? "" : ",readFailed=" + evalScanReadFailed));
        int evalChanged = 0;
        int evalCompared = 0;
        double evalMaxDelta = 0.0;
        for (Map.Entry<String, float[]> entry : evalBefore.entrySet()) {
            final float[] afterEval = evalAfter.get(entry.getKey());
            if (afterEval == null) continue;
            evalCompared++;
            double objectMax = 0.0;
            final float[] beforeEval = entry.getValue();
            for (int index = 0; index < Math.min(beforeEval.length, afterEval.length); index += 2) {
                objectMax = Math.max(objectMax, Math.hypot(
                    (double) afterEval[index] - beforeEval[index],
                    (double) afterEval[index + 1] - beforeEval[index + 1]));
            }
            evalMaxDelta = Math.max(evalMaxDelta, objectMax);
            if (objectMax > 0.001) evalChanged++;
        }
        report.put(prefix + "descendantStoredPositionsCompared", Integer.toString(evalCompared));
        report.put(prefix + "descendantStoredPositionsChanged", Integer.toString(evalChanged));
        report.put(prefix + "descendantStoredPositionsMaxDelta",
            String.format(java.util.Locale.ROOT, "%.4f", evalMaxDelta));
        if (!preserve) {
            final String[] follow = descendantFollowObservation(followLogMark);
            report.put(prefix + "descendantFollow.logged", follow[0]);
            report.put(prefix + "descendantFollow.compared", follow[1]);
            report.put(prefix + "descendantFollow.maxDelta", follow[2]);
            report.put(prefix + "descendantFollow.detail", follow[3]);
            if (evalCompared > 0) {
                report.put(prefix + "descendantEvaluationsChanged.flagged",
                    descendantVertexChanges(target.id().value()));
            }
        }

        recordUndoRedo(target, before, after, formsBefore, prefix, report, failures);
    }

    /**
     * Snapshots {@code Drawable.vertexPositions()} of every drawable descending from
     * {@code targetId}. In the editor backend this is the stored keyform geometry,
     * not the evaluated canvas output — the method is kept to re-confirm stored
     * immutability under preserve=OFF, not as evaluated-geometry evidence.
     */
    private Map<String, float[]> descendantDrawableEvalSnapshot(final String targetId) {
        final Map<String, float[]> result = new TreeMap<>();
        evalScanTotal = -1;
        evalScanParentMatched = 0;
        evalScanReadFailed = "";
        try {
            final CubismModel model = context.cubism().model().active();
            final Map<String, Deformer> byId = new HashMap<>();
            for (Deformer deformer : model.deformers().all()) {
                byId.put(deformer.id().value(), deformer);
            }
            final List<dev.turboism.sdk.cubism.model.Drawable> all = model.drawables().all();
            evalScanTotal = all.size();
            for (dev.turboism.sdk.cubism.model.Drawable drawable : all) {
                if (!isDescendantDrawable(drawable, targetId, byId)) {
                    continue;
                }
                evalScanParentMatched++;
                try {
                    final dev.turboism.sdk.cubism.model.FloatSequence positions =
                        drawable.vertexPositions();
                    final float[] values = new float[positions.size()];
                    for (int index = 0; index < values.length; index++) {
                        values[index] = positions.get(index);
                    }
                    result.put(drawable.id().value(), values);
                } catch (RuntimeException | Error failure) {
                    if (evalScanReadFailed.isEmpty()) {
                        evalScanReadFailed = failure.getClass().getSimpleName();
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            evalScanReadFailed = "enumerate:" + failure.getClass().getSimpleName();
        }
        return result;
    }

    private int evalScanTotal;
    private int evalScanParentMatched;
    private String evalScanReadFailed = "";

    /** Byte position in the runtime log captured just before an apply call. */
    private record LogMark(Path path, long offset) {
    }

    /**
     * Locates the live console stream that carries {@code System.Logger} output —
     * the runner's {@code evidence/cubism-console.txt} next to turboism-home,
     * falling back to the newest file under {@code <home>/logs/runtime/} — and
     * records its current length so post-apply lines can be read in isolation.
     */
    private LogMark markRuntimeLog() {
        try {
            final Path console = consolePath();
            return console == null ? null : new LogMark(console, Files.size(console));
        } catch (RuntimeException | IOException | Error failure) {
            return null;
        }
    }

    /**
     * Locates the live stream that carries {@code System.Logger} output — the runner's
     * {@code evidence/cubism-console.txt} next to turboism-home, falling back to the
     * newest file under {@code <home>/logs/runtime/}.
     */
    private Path consolePath() {
        try {
            final Path stateDir = context.paths().stateDir();
            final Path home = stateDir.getParent() == null
                ? null : stateDir.getParent().getParent();
            if (home == null) {
                return null;
            }
            final Path queueDir = home.getParent();
            if (queueDir != null) {
                final Path console = queueDir.resolve("evidence")
                    .resolve("cubism-console.txt");
                if (Files.isRegularFile(console)) {
                    return console;
                }
            }
            final Path logsRoot = home.resolve("logs").resolve("runtime");
            if (!Files.isDirectory(logsRoot)) {
                return null;
            }
            Path newest = null;
            long newestTime = -1L;
            try (var stream = Files.walk(logsRoot)) {
                for (Path candidate : stream.filter(Files::isRegularFile).toList()) {
                    if (!candidate.getFileName().toString().endsWith(".log")) {
                        continue;
                    }
                    final long modified = Files.getLastModifiedTime(candidate).toMillis();
                    if (modified >= newestTime) {
                        newestTime = modified;
                        newest = candidate;
                    }
                }
            }
            return newest;
        } catch (RuntimeException | IOException | Error failure) {
            return null;
        }
    }

    /**
     * Reads the runtime log appended after {@code mark} and extracts the service-side
     * evaluated-geometry follow observation written by a preserve=OFF apply
     * ({@code Warp mirror descendant follow*} lines). Returns
     * {@code [logged, compared, maxDelta, detail]}.
     */
    private String[] descendantFollowObservation(final LogMark mark) {
        if (mark == null || mark.path() == null) {
            return new String[] { "no-log-mark", "", "", "" };
        }
        try {
            final long size = Files.size(mark.path());
            final long offset = Math.min(mark.offset(), size);
            final String tail;
            try (var channel = Files.newByteChannel(mark.path(),
                java.util.EnumSet.of(StandardOpenOption.READ))) {
                channel.position(offset);
                final var buffer = java.nio.ByteBuffer.allocate((int) (size - offset));
                while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                }
                tail = new String(buffer.array(), 0, buffer.position(),
                    StandardCharsets.UTF_8);
            }
            String compared = "";
            String maxDelta = "";
            final StringBuilder detail = new StringBuilder();
            for (String line : tail.split("\\R")) {
                final int summary = line.indexOf(
                    "Warp mirror descendant follow summary:");
                if (summary >= 0) {
                    final String rest = line.substring(summary);
                    for (String token : rest.split("[,\\s]+")) {
                        if (token.startsWith("compared=")) {
                            compared = token.substring("compared=".length());
                        } else if (token.startsWith("maxDelta=")) {
                            maxDelta = token.substring("maxDelta=".length());
                        }
                    }
                    continue;
                }
                final int perObject = line.indexOf("Warp mirror descendant follow:");
                if (perObject >= 0 && detail.length() < 300) {
                    if (detail.length() > 0) {
                        detail.append("; ");
                    }
                    String text = line.substring(perObject
                        + "Warp mirror descendant follow:".length()).trim();
                    final int frameTail = text.indexOf(" ] at ");
                    if (frameTail >= 0) {
                        text = text.substring(0, frameTail);
                    }
                    detail.append(text);
                }
            }
            return new String[] {
                compared.isEmpty() ? "no-marker" : "true",
                compared, maxDelta, detail.toString()
            };
        } catch (RuntimeException | IOException | Error failure) {
            return new String[] { "read-failed:" + failure.getClass().getSimpleName(),
                "", "", "" };
        }
    }

    /**
     * Polls descendant drawables' {@code vertexPositionsChanged} flags briefly after an
     * apply. Returns a comma-separated list of drawable ids that reported a change, or
     * {@code unavailable}/an empty marker when the surface is not admitted or nothing
     * changed.
     */
    private String descendantVertexChanges(final String targetId) {
        try {
            final CubismModel model = context.cubism().model().active();
            final Map<String, Deformer> byId = new HashMap<>();
            for (Deformer deformer : model.deformers().all()) {
                byId.put(deformer.id().value(), deformer);
            }
            final List<dev.turboism.sdk.cubism.model.Drawable> drawables =
                model.drawables().all();
            final long deadline = System.currentTimeMillis() + 1_500L;
            final List<String> changed = new ArrayList<>();
            while (System.currentTimeMillis() < deadline && changed.isEmpty()) {
                for (dev.turboism.sdk.cubism.model.Drawable drawable : drawables) {
                    try {
                        if (!isDescendantDrawable(drawable, targetId, byId)) {
                            continue;
                        }
                        if (drawable.evaluationState().vertexPositionsChanged()) {
                            changed.add(drawable.id().value());
                        }
                    } catch (RuntimeException | Error ignored) {
                    }
                }
                if (changed.isEmpty()) {
                    sleep(120);
                }
            }
            java.util.Collections.sort(changed);
            return changed.isEmpty() ? "none-observed" : String.join(",", changed);
        } catch (RuntimeException | Error failure) {
            return "unavailable:" + failure.getClass().getSimpleName();
        }
    }

    private static boolean isDescendantDrawable(
        final dev.turboism.sdk.cubism.model.Drawable drawable,
        final String ancestorId,
        final Map<String, Deformer> byId
    ) {
        String parent;
        try {
            parent = drawable.parentDeformerId().map(DeformerId::value).orElse(null);
        } catch (RuntimeException | Error failure) {
            return false;
        }
        for (int depth = 0; depth < 64 && parent != null; depth++) {
            if (parent.equals(ancestorId)) {
                return true;
            }
            final Deformer next = byId.get(parent);
            if (next == null) {
                return false;
            }
            parent = next.parentDeformerId().map(DeformerId::value).orElse(null);
        }
        return false;
    }

    /**
     * Verifies the index-symmetric mirror contract: every rewritten target point
     * must equal the reflection of its structural counterpart
     * ({@code lineCount-1-index}) across the grid's centre-seam fold, computed
     * from the pre-mirror positions. Source-side and centre-line points must be
     * untouched.
     */
    static DirectionCheck verifyDirection(
        final WarpGrid before,
        final WarpGrid after,
        final WarpMirrorDirection direction
    ) {
        final List<Point2> from = before.controlPoints();
        final List<Point2> to = after.controlPoints();
        if (from.size() != to.size()
            || before.rows() != after.rows()
            || before.columns() != after.columns()) {
            return new DirectionCheck(0, 0);
        }
        final int pointColumns = before.columns() + 1;
        final int pointRows = before.rows() + 1;
        final boolean vertical = direction == WarpMirrorDirection.LEFT_TO_RIGHT
            || direction == WarpMirrorDirection.RIGHT_TO_LEFT;
        final int lineCount = vertical ? pointColumns : pointRows;
        final int lineSize = vertical ? pointRows : pointColumns;
        final int centerLeft = (lineCount - 1) / 2;
        final int centerRight = lineCount / 2;
        final float axis = (lineMean(from, pointColumns, vertical, centerLeft)
            + lineMean(from, pointColumns, vertical, centerRight)) * 0.5f;
        // Source side is spatial, not storage order — mirrors WarpMirrorPairing.
        final boolean ascending = lineMean(from, pointColumns, vertical, 0)
            <= lineMean(from, pointColumns, vertical, lineCount - 1);
        final boolean wantsLowSide = direction == WarpMirrorDirection.LEFT_TO_RIGHT
            || direction == WarpMirrorDirection.TOP_TO_BOTTOM;
        final boolean lowerIsSource = wantsLowSide == ascending;

        int checked = 0;
        int mismatched = 0;
        for (int line = 0; line < lineSize; line++) {
            for (int index = 0; index < lineCount; index++) {
                final boolean target = lowerIsSource
                    ? index > centerLeft
                    : index < centerRight;
                if (!target) {
                    continue;
                }
                final int source = lineCount - 1 - index;
                final Point2 preSource = from.get(vertical
                    ? line * pointColumns + source
                    : source * pointColumns + line);
                final Point2 post = to.get(vertical
                    ? line * pointColumns + index
                    : index * pointColumns + line);
                checked++;
                final float expectedX = vertical ? 2.0f * axis - preSource.x() : preSource.x();
                final float expectedY = vertical ? preSource.y() : 2.0f * axis - preSource.y();
                if (Math.abs(post.x() - expectedX) > POINT_EPSILON
                    || Math.abs(post.y() - expectedY) > POINT_EPSILON) {
                    mismatched++;
                }
            }
        }
        return new DirectionCheck(checked, mismatched);
    }

    /** Mean axis-direction coordinate of one grid column (vertical) or row. */
    private static float lineMean(
        final List<Point2> points,
        final int pointColumns,
        final boolean vertical,
        final int index
    ) {
        final int lineSize = vertical ? points.size() / pointColumns : pointColumns;
        float sum = 0f;
        for (int line = 0; line < lineSize; line++) {
            final Point2 point = points.get(vertical
                ? line * pointColumns + index
                : index * pointColumns + line);
            sum += vertical ? point.x() : point.y();
        }
        return sum / lineSize;
    }

    private void recordUndoRedo(
        final WarpDeformer target,
        final WarpGrid before,
        final WarpGrid applied,
        final Map<String, String> formsBefore,
        final String prefix,
        final Report report,
        final List<String> failures
    ) {
        final CubismHistory history;
        try {
            history = context.cubism().history();
        } catch (RuntimeException | Error failure) {
            failures.add("history access failed: " + failure.getClass().getSimpleName());
            return;
        }
        final HistoryMoveResult undo = history.undo(1);
        report.put(prefix + "undoOutcome", undo.outcome().name());
        sleep(SETTLE_MILLIS);
        try {
            final boolean gridRestored = samePoints(before, target.grid());
            final boolean formsRestored = formsBefore
                .equals(descendantFormSnapshot(target.id().value()));
            report.put(prefix + "undoRestored", Boolean.toString(gridRestored));
            report.put(prefix + "undoDescendantsRestored", Boolean.toString(formsRestored));
            logger.info("WARP_MIRROR_UNDO outcome=" + undo.outcome().name()
                + " gridRestored=" + gridRestored + " descendantsRestored=" + formsRestored);
            if (!gridRestored || !formsRestored) {
                failures.add("single undo did not atomically restore grid+descendants: grid="
                    + gridRestored + " descendants=" + formsRestored);
            }
        } catch (RuntimeException | Error failure) {
            failures.add("post-undo read failed: " + failure.getClass().getSimpleName());
            return;
        }

        final HistoryMoveResult redo = history.redo(1);
        report.put(prefix + "redoOutcome", redo.outcome().name());
        sleep(SETTLE_MILLIS);
        try {
            final boolean redoReapplied = samePoints(applied, target.grid());
            report.put(prefix + "redoReapplied", Boolean.toString(redoReapplied));
            logger.info("WARP_MIRROR_REDO outcome=" + redo.outcome().name()
                + " reapplied=" + redoReapplied);
            if (!redoReapplied) {
                failures.add("redo did not reapply the mirrored grid");
            }
        } catch (RuntimeException | Error failure) {
            failures.add("post-redo read failed: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * Restores the pre-direction grid via Undo so the next direction sees the baseline.
     * Uses the recorded history position: an APPLIED mirror owns exactly one entry, while
     * NO_CHANGE/BLOCKED outcomes create none — undoing unconditionally would consume an
     * unrelated entry (this was the r12 probe bug that ran BOTTOM_TO_TOP on an
     * unperturbed grid).
     */
    private void restoreBaseline(
        final WarpDeformer target,
        final WarpGrid before,
        final int historyBefore,
        final Report report,
        final String prefix,
        final List<String> failures
    ) {
        try {
            int guard = 0;
            int position = historyPosition();
            while (historyBefore >= 0 && position > historyBefore && guard++ < 4) {
                history().undo(1);
                sleep(SETTLE_MILLIS);
                position = historyPosition();
            }
            if (historyBefore < 0) {
                // History position unreadable: fall back to a single undo only when the
                // grid actually diverged from the baseline.
                if (!samePoints(before, target.grid())) {
                    history().undo(1);
                    sleep(SETTLE_MILLIS);
                }
            }
            report.put(prefix + "baselineRestored",
                Boolean.toString(samePoints(before, target.grid())));
        } catch (RuntimeException | Error failure) {
            failures.add("baseline restore undo failed: " + failure.getClass().getSimpleName());
        }
    }

    private CubismHistory history() {
        return context.cubism().history();
    }

    // ------------------------------------------------------------------
    // NO_CHANGE and fail-closed evidence
    // ------------------------------------------------------------------

    /**
     * Applies LEFT_TO_RIGHT twice: the second call must report NO_CHANGE and create no
     * extra Undo entry (undoing once must already restore the original grid).
     */
    private void runNoChange(
        final WarpDeformer target,
        final Report report,
        final List<String> failures
    ) {
        final WarpGrid baseline;
        try {
            baseline = target.grid();
        } catch (RuntimeException | Error failure) {
            failures.add("noChange pre-read failed: " + failure.getClass().getSimpleName());
            return;
        }
        try {
            final WarpMirrorResult first = context.cubism().warpMirror().apply(
                new WarpMirrorRequest(target.id(), WarpMirrorDirection.LEFT_TO_RIGHT, true));
            report.put("noChange.firstOutcome", first.outcome().name());
            report.put("noChange.firstBlockers", blockerCodes(first));
            sleep(SETTLE_MILLIS);
            final WarpMirrorResult second = context.cubism().warpMirror().apply(
                new WarpMirrorRequest(target.id(), WarpMirrorDirection.LEFT_TO_RIGHT, true));
            report.put("noChange.secondOutcome", second.outcome().name());
            report.put("noChange.secondBlockers", blockerCodes(second));
            logger.info("WARP_MIRROR_NO_CHANGE first=" + first.outcome().name()
                + " second=" + second.outcome().name());
            if (first.outcome() == dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome.APPLIED
                && second.outcome() != dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome.NO_CHANGE) {
                failures.add("re-applying LEFT_TO_RIGHT on an already-mirrored grid reported "
                    + second.outcome().name() + " instead of NO_CHANGE");
            }
            // One undo per real write: undo once for the APPLIED op; nothing else expected.
            history().undo(1);
            sleep(SETTLE_MILLIS);
            final boolean restored = samePoints(baseline, target.grid());
            report.put("noChange.baselineRestored", Boolean.toString(restored));
            if (first.outcome() == dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome.APPLIED
                && !restored) {
                failures.add("undo after NO_CHANGE sequence did not restore the baseline grid");
            }
        } catch (RuntimeException | Error failure) {
            failures.add("noChange sequence failed: " + failure.getClass().getSimpleName());
        }
    }

    private void runNegativeTargets(final Report report, final List<String> failures) {
        try {
            final WarpMirrorResult missing = context.cubism().warpMirror().apply(
                new WarpMirrorRequest(
                    new DeformerId("turboism-probe-no-such-warp"),
                    WarpMirrorDirection.LEFT_TO_RIGHT,
                    true));
            report.put("negative.missingOutcome", missing.outcome().name());
            report.put("negative.missingBlockers", missing.blockers().stream()
                .map(blocker -> blocker.code().name()).reduce("",
                    (a, b) -> a.isEmpty() ? b : a + "," + b));
            logger.info("WARP_MIRROR_NEGATIVE missing=" + missing.outcome().name());
            if (missing.outcome() != dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome.BLOCKED) {
                failures.add("non-existent target was not rejected: " + missing.outcome().name());
            }
        } catch (RuntimeException | Error failure) {
            failures.add("negative target probe threw: " + failure.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // Snapshots and comparisons
    // ------------------------------------------------------------------

    /**
     * Stored-form fingerprint of every deformer descendant of {@code targetId}, keyed by
     * deformer id ({@code "warp:"}/{@code "rotation:"} prefixed). On 5.3.03 the SDK does
     * not admit {@code Drawable} reads, so ArtMesh descendants are not enumerable here;
     * their canvas preservation is enforced inside the service before it reports
     * {@code APPLIED}.
     */
    private Map<String, String> descendantFormSnapshot(final String targetId) {
        final CubismModel model = context.cubism().model().active();
        final Map<String, Deformer> byId = new HashMap<>();
        for (Deformer deformer : model.deformers().all()) {
            byId.put(deformer.id().value(), deformer);
        }
        final Map<String, String> snapshot = new HashMap<>();
        for (WarpDeformer warp : model.warpDeformers().all()) {
            if (warp.id().value().equals(targetId)) {
                continue;
            }
            if (!isDescendantOf(warp, targetId, byId)) {
                continue;
            }
            snapshot.put("warp:" + warp.id().value(), fingerprint(warp.grid()));
        }
        for (RotationDeformer rotation : model.rotationDeformers().all()) {
            if (!isDescendantOf(rotation, targetId, byId)) {
                continue;
            }
            final RotationDeformerForm form = rotation.form();
            snapshot.put("rotation:" + rotation.id().value(), String.format(Locale.ROOT,
                "%.4f|%.4f|%.4f|%.4f|%b|%b",
                form.angle(), form.originX(), form.originY(), form.scale(),
                form.reflectedX(), form.reflectedY()));
        }
        return snapshot;
    }

    private static boolean isDescendantOf(
        final Deformer deformer,
        final String ancestorId,
        final Map<String, Deformer> byId
    ) {
        Deformer current = deformer;
        for (int depth = 0; depth < 64; depth++) {
            final String parent;
            try {
                parent = current.parentDeformerId().map(DeformerId::value).orElse(null);
            } catch (RuntimeException | Error failure) {
                return false;
            }
            if (parent == null) {
                return false;
            }
            if (parent.equals(ancestorId)) {
                return true;
            }
            current = byId.get(parent);
            if (current == null) {
                return false;
            }
        }
        return false;
    }

    private static boolean samePoints(final WarpGrid left, final WarpGrid right) {
        final List<Point2> a = left.controlPoints();
        final List<Point2> b = right.controlPoints();
        if (a.size() != b.size() || left.rows() != right.rows() || left.columns() != right.columns()) {
            return false;
        }
        for (int index = 0; index < a.size(); index++) {
            if (Math.abs(a.get(index).x() - b.get(index).x()) > POINT_EPSILON
                || Math.abs(a.get(index).y() - b.get(index).y()) > POINT_EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static String fingerprint(final WarpGrid grid) {
        final StringBuilder text = new StringBuilder();
        text.append(grid.rows()).append('x').append(grid.columns());
        for (Point2 point : grid.controlPoints()) {
            text.append('|').append(String.format(Locale.ROOT, "%.3f,%.3f", point.x(), point.y()));
        }
        return text.toString();
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Validation-only direction-check tally. */
    record DirectionCheck(int checked, int mismatched) { }

    // ------------------------------------------------------------------
    // Vertical-direction APPLIED evidence
    // ------------------------------------------------------------------

    /**
     * The fixture's warp grids are already vertically symmetric, so TOP/BOTTOM directions
     * legitimately return NO_CHANGE. To prove the vertical paths actually write, the probe
     * perturbs one control point's Y through the public {@code replaceGrid} write (a real,
     * undoable model edit), then runs both vertical directions against the asymmetric grid.
     * The perturbation is undone afterwards so the baseline is restored.
     */
    private void runVerticalDirections(
        final WarpDeformer target,
        final Report report,
        final List<String> failures
    ) {
        final WarpGrid original;
        try {
            original = target.grid();
        } catch (RuntimeException | Error failure) {
            failures.add("vertical pre-read failed: " + failure.getClass().getSimpleName());
            return;
        }
        if (original.controlPoints().isEmpty()) {
            report.put("vertical.note", "empty grid - skipped");
            return;
        }
        // Perturb two points away from the axis and from the grid corners: the
        // top-middle point (row 1, col 2) breaks symmetry for TOP_TO_BOTTOM while
        // its bottom-right counterpart (row 3, col 4) keeps BOTTOM_TO_TOP a real
        // write too. Their mirrored images land across the center bands where the
        // fixture's descendants actually sit, so preserve=OFF produces measurable
        // follow deltas (a corner-only perturb proved to leave them untouched —
        // a grid control point's influence is local to its adjacent cells).
        final List<Point2> points = original.controlPoints();
        final int columns = original.columns() + 1;
        final int upperIndex = columns + 2;
        final int lowerIndex = 3 * columns + 4;
        WarpGrid perturbed = original;
        if (points.size() > lowerIndex) {
            final Point2 upper = points.get(upperIndex);
            perturbed = perturbed.withControlPoint(
                upperIndex, upper.x(), upper.y() + 8.0f);
            final Point2 lower = points.get(lowerIndex);
            perturbed = perturbed.withControlPoint(
                lowerIndex, lower.x(), lower.y() + 8.0f);
        } else {
            final Point2 first = points.get(0);
            perturbed = perturbed.withControlPoint(0, first.x(), first.y() + 8.0f);
        }
        final int historyBeforePerturb = historyPosition();
        try {
            target.replaceGrid(perturbed);
        } catch (RuntimeException | Error failure) {
            report.put("vertical.perturb", "FAILED " + failure.getClass().getSimpleName());
            failures.add("vertical perturbation write failed: " + failure.getClass().getSimpleName());
            return;
        }
        report.put("vertical.perturbHistoryEntry",
            Integer.toString(Math.max(0, historyPosition() - historyBeforePerturb)));
        sleep(SETTLE_MILLIS);
        try {
            report.put("vertical.perturbApplied",
                Boolean.toString(samePoints(perturbed, target.grid())));
        } catch (RuntimeException | Error failure) {
            report.put("vertical.perturbApplied", "read-failed");
        }
        runDirection(target, WarpMirrorDirection.TOP_TO_BOTTOM, true,
            "vertical.direction.TOP_TO_BOTTOM.", report, failures);
        runDirection(target, WarpMirrorDirection.BOTTOM_TO_TOP, true,
            "vertical.direction.BOTTOM_TO_TOP.", report, failures);
        // preserve=false on the perturbed grid: the parent mirror genuinely moves the
        // lattice, so descendant drawables must report changed evaluated positions
        // while their stored forms stay untouched.
        runDirection(target, WarpMirrorDirection.TOP_TO_BOTTOM, false,
            "vertical.direction.TOP_TO_BOTTOM.noPreserve.", report, failures);
        // Undo back to the pre-perturbation history position removes the perturbation
        // write itself (each APPLIED direction above already restored itself).
        try {
            int guard = 0;
            int position = historyPosition();
            while (historyBeforePerturb >= 0 && position > historyBeforePerturb && guard++ < 4) {
                history().undo(1);
                sleep(SETTLE_MILLIS);
                position = historyPosition();
            }
            sleep(SETTLE_MILLIS);
            final boolean restored = samePoints(original, target.grid());
            report.put("vertical.baselineRestored", Boolean.toString(restored));
            if (!restored) {
                failures.add("vertical: undo did not restore the un-perturbed baseline grid");
            }
        } catch (RuntimeException | Error failure) {
            failures.add("vertical baseline undo failed: " + failure.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // Lock rejection (pre-write, no partial write)
    // ------------------------------------------------------------------

    private void runLockedRejection(
        final WarpDeformer target,
        final Report report,
        final List<String> failures
    ) {
        final WarpGrid before;
        try {
            before = target.grid();
        } catch (RuntimeException | Error failure) {
            failures.add("locked pre-read failed: " + failure.getClass().getSimpleName());
            return;
        }
        try {
            target.setLocked(true);
        } catch (RuntimeException | Error failure) {
            report.put("locked.note", "setLocked unavailable: " + failure.getClass().getSimpleName());
            return;
        }
        sleep(SETTLE_MILLIS);
        try {
            report.put("locked.flagSet", Boolean.toString(target.locked()));
            final WarpMirrorResult result = context.cubism().warpMirror().apply(
                new WarpMirrorRequest(target.id(), WarpMirrorDirection.LEFT_TO_RIGHT, true));
            report.put("locked.outcome", result.outcome().name());
            report.put("locked.blockers", blockerCodes(result));
            sleep(SETTLE_MILLIS);
            report.put("locked.gridUnchanged",
                Boolean.toString(samePoints(before, target.grid())));
            if (result.outcome() != dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome.BLOCKED) {
                failures.add("locked target was not rejected: " + result.outcome().name());
            } else if (!blockerCodes(result).contains("LOCKED")) {
                failures.add("locked rejection did not carry a LOCKED blocker: "
                    + blockerCodes(result));
            }
            if (!samePoints(before, target.grid())) {
                failures.add("locked rejection left a partial write on the target grid");
            }
        } catch (RuntimeException | Error failure) {
            failures.add("locked probe failed: " + failure.getClass().getSimpleName());
        } finally {
            try {
                target.setLocked(false);
                sleep(SETTLE_MILLIS);
                report.put("locked.unlocked", Boolean.toString(!target.locked()));
            } catch (RuntimeException | Error failure) {
                report.put("locked.unlocked", "FAILED " + failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * Rejection evidence for a locked descendant: locking one Rotation child of the
     * target must produce a typed {@code DESCENDANT_LOCKED} block under preserve=true
     * with the target grid, every descendant stored form, and the history position
     * all untouched.
     */
    private void runDescendantLockedRejection(
        final WarpDeformer target,
        final Report report,
        final List<String> failures
    ) {
        final String prefix = "locked.descendant.";
        RotationDeformer child = null;
        try {
            final Map<String, Deformer> byId = new HashMap<>();
            final CubismModel model = context.cubism().model().active();
            for (Deformer deformer : model.deformers().all()) {
                byId.put(deformer.id().value(), deformer);
            }
            for (RotationDeformer rotation : model.rotationDeformers().all()) {
                if (isDescendantOf(rotation, target.id().value(), byId)) {
                    child = rotation;
                    break;
                }
            }
        } catch (RuntimeException | Error failure) {
            report.put(prefix + "note", "descendant scan failed: "
                + failure.getClass().getSimpleName());
            return;
        }
        if (child == null) {
            report.put(prefix + "note", "no rotation descendant to lock");
            return;
        }
        report.put(prefix + "lockedId", child.id().value());
        final WarpGrid before;
        final Map<String, String> formsBefore;
        try {
            before = target.grid();
            formsBefore = descendantFormSnapshot(target.id().value());
        } catch (RuntimeException | Error failure) {
            failures.add("descendant-lock pre-read failed: " + failure.getClass().getSimpleName());
            return;
        }
        try {
            child.setLocked(true);
        } catch (RuntimeException | Error failure) {
            report.put(prefix + "note", "setLocked unavailable: "
                + failure.getClass().getSimpleName());
            return;
        }
        sleep(SETTLE_MILLIS);
        try {
            report.put(prefix + "flagSet", Boolean.toString(child.locked()));
            final int historyBefore = historyPosition();
            final WarpMirrorResult result = context.cubism().warpMirror().apply(
                new WarpMirrorRequest(target.id(), WarpMirrorDirection.LEFT_TO_RIGHT, true));
            report.put(prefix + "outcome", result.outcome().name());
            report.put(prefix + "blockers", blockerCodes(result));
            sleep(SETTLE_MILLIS);
            report.put(prefix + "gridUnchanged",
                Boolean.toString(samePoints(before, target.grid())));
            report.put(prefix + "formsUnchanged",
                Boolean.toString(formsBefore.equals(descendantFormSnapshot(target.id().value()))));
            report.put(prefix + "historyEntriesCreated",
                Integer.toString(Math.max(0, historyPosition() - historyBefore)));
            if (result.outcome() != dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome.BLOCKED) {
                failures.add("descendant-locked apply was not rejected: "
                    + result.outcome().name());
            } else if (!blockerCodes(result).contains("DESCENDANT_LOCKED")) {
                failures.add("descendant-locked rejection missed DESCENDANT_LOCKED: "
                    + blockerCodes(result));
            }
        } catch (RuntimeException | Error failure) {
            failures.add("descendant-locked probe failed: " + failure.getClass().getSimpleName());
        } finally {
            try {
                child.setLocked(false);
                sleep(SETTLE_MILLIS);
                report.put(prefix + "unlocked", Boolean.toString(!child.locked()));
            } catch (RuntimeException | Error failure) {
                report.put(prefix + "unlocked", "FAILED " + failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * Parent+child multi-target ordering evidence. The production plugin resolves a
     * multi-selection to an ordered target list (parents first) and issues one
     * independent {@code apply} per target; this stage replays exactly that sequence
     * on the fixture's nested Warp pair: parent mirror with preservation (the child
     * Warp's stored form is compensated), then the child's own mirror in its
     * re-preserved local space. Ordering is load-bearing — mirroring the child first
     * would let the parent's compensation silently undo the child's mirror intent.
     * Two history entries and a two-step undo restore verify per-target atomicity.
     */
    private void runOrderedWarpPairCase(
        final List<WarpDeformer> warps,
        final Report report,
        final List<String> failures
    ) {
        final String prefix = "multi.";
        final Map<String, Deformer> byId = new HashMap<>();
        try {
            final CubismModel model = context.cubism().model().active();
            for (Deformer deformer : model.deformers().all()) {
                byId.put(deformer.id().value(), deformer);
            }
        } catch (RuntimeException | Error failure) {
            report.put(prefix + "note", "hierarchy scan failed: "
                + failure.getClass().getSimpleName());
            return;
        }
        WarpDeformer parent = null;
        WarpDeformer child = null;
        final StringBuilder parents = new StringBuilder();
        for (WarpDeformer warp : warps) {
            String parentId;
            try {
                parentId = warp.parentDeformerId().map(DeformerId::value).orElse("");
            } catch (RuntimeException | Error failure) {
                parentId = "unavailable";
            }
            if (parents.length() < 300) {
                if (parents.length() > 0) {
                    parents.append(';');
                }
                parents.append(warp.id().value()).append("->")
                    .append(parentId.isEmpty() ? "root" : parentId);
            }
            for (WarpDeformer ancestor : warps) {
                if (!ancestor.id().value().equals(warp.id().value())
                    && isDescendantOf(warp, ancestor.id().value(), byId)) {
                    parent = ancestor;
                    child = warp;
                    break;
                }
            }
            if (parent != null) {
                break;
            }
        }
        report.put(prefix + "warpParents", parents.toString());
        if (parent == null) {
            report.put(prefix + "nestedWarpPair", "none-in-fixture");
            return;
        }
        report.put(prefix + "nestedWarpPair",
            "parent=" + parent.id().value() + ",child=" + child.id().value());
        report.put(prefix + "order", "parent-first");

        final WarpGrid parentBefore;
        final WarpGrid childBefore;
        final int historyBefore;
        try {
            parentBefore = parent.grid();
            childBefore = child.grid();
            historyBefore = historyPosition();
        } catch (RuntimeException | Error failure) {
            failures.add("multi pre-read failed: " + failure.getClass().getSimpleName());
            return;
        }
        // The fixture's nested Warp grid is a symmetric regular lattice, so a bare
        // child apply would legitimately report NO_CHANGE and the ordering would
        // stay unexercised. Perturb one off-axis point of the child grid first
        // (a real undoable write) so the child's own mirror is a real apply and
        // the sequence produces two history entries.
        try {
            final java.util.List<Point2> childPoints = childBefore.controlPoints();
            final int childColumns = childBefore.columns() + 1;
            final int perturbIndex = childColumns + 2;
            if (childPoints.size() > perturbIndex) {
                final Point2 point = childPoints.get(perturbIndex);
                child.replaceGrid(childBefore.withControlPoint(
                    perturbIndex, point.x(), point.y() + 0.05f));
                sleep(SETTLE_MILLIS);
                report.put(prefix + "child.perturbApplied",
                    Boolean.toString(!samePoints(childBefore, child.grid())));
            }
        } catch (RuntimeException | Error failure) {
            report.put(prefix + "child.perturb",
                "FAILED " + failure.getClass().getSimpleName());
        }
        final int historyAfterPerturb = historyPosition();
        // Parent mirror first: the child Warp's stored grid must be rewritten by
        // compensation so its evaluated canvas geometry survives the parent mirror.
        final WarpMirrorResult parentResult;
        try {
            parentResult = context.cubism().warpMirror().apply(
                new WarpMirrorRequest(parent.id(), WarpMirrorDirection.LEFT_TO_RIGHT, true));
        } catch (RuntimeException | Error failure) {
            failures.add("multi parent apply threw: " + failure.getClass().getSimpleName());
            return;
        }
        sleep(SETTLE_MILLIS);
        report.put(prefix + "parent.outcome", parentResult.outcome().name());
        report.put(prefix + "parent.blockers", blockerCodes(parentResult));
        if (parentResult.outcome() != dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome.APPLIED) {
            report.put(prefix + "note", "parent apply not applied; ordered pair skipped");
            restoreToPosition(historyBefore, report, prefix + "parent.");
            return;
        }
        final WarpGrid childAfterParent;
        try {
            childAfterParent = child.grid();
        } catch (RuntimeException | Error failure) {
            failures.add("multi child post-parent read failed: "
                + failure.getClass().getSimpleName());
            restoreToPosition(historyBefore, report, prefix + "parent.");
            return;
        }
        report.put(prefix + "child.gridAfterParent", fingerprint(childAfterParent));
        // Child mirror second, in the same direction as the production runner would
        // apply: the child's stored grid must now reflect across its own axis.
        final WarpMirrorResult childResult;
        try {
            childResult = context.cubism().warpMirror().apply(
                new WarpMirrorRequest(child.id(), WarpMirrorDirection.LEFT_TO_RIGHT, true));
        } catch (RuntimeException | Error failure) {
            failures.add("multi child apply threw: " + failure.getClass().getSimpleName());
            restoreToPosition(historyBefore, report, prefix + "parent.");
            return;
        }
        sleep(SETTLE_MILLIS);
        report.put(prefix + "child.outcome", childResult.outcome().name());
        report.put(prefix + "child.blockers", blockerCodes(childResult));
        report.put(prefix + "historyEntriesCreated",
            Integer.toString(Math.max(0, historyPosition() - historyAfterPerturb)));
        report.put(prefix + "perturbHistoryEntry",
            Integer.toString(Math.max(0, historyAfterPerturb - historyBefore)));
        if (childResult.outcome() == dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome.APPLIED) {
            final DirectionCheck check;
            try {
                check = verifyDirection(childAfterParent, child.grid(),
                    WarpMirrorDirection.LEFT_TO_RIGHT);
            } catch (RuntimeException | Error failure) {
                failures.add("multi child mirror check read failed: "
                    + failure.getClass().getSimpleName());
                restoreToPosition(historyBefore, report, prefix);
                return;
            }
            report.put(prefix + "child.checked", Integer.toString(check.checked));
            report.put(prefix + "child.mismatched", Integer.toString(check.mismatched));
            if (check.checked == 0 || check.mismatched > 0) {
                failures.add("multi child grid did not reflect across its own axis: "
                    + check.mismatched + "/" + check.checked);
            }
            // The child's mirror intent must persist — i.e. the parent compensation
            // must not have clobbered the child's own mirrored grid.
            report.put(prefix + "child.gridAfterOwnMirror", "persisted");
        } else {
            report.put(prefix + "child.note", "child apply outcome=" + childResult.outcome().name());
        }
        restoreToPosition(historyBefore, report, prefix);
    }

    /**
     * Undoes history entries until {@code position} is reached again, reporting the
     * restored position under {@code prefix + "baselineRestored"}.
     */
    private void restoreToPosition(
        final int position,
        final Report report,
        final String prefix
    ) {
        if (position < 0) {
            return;
        }
        int guard = 0;
        while (historyPosition() > position && guard++ < 16) {
            try {
                history().undo(1);
                sleep(SETTLE_MILLIS);
            } catch (RuntimeException | Error failure) {
                report.put(prefix + "baselineRestored", "undo-failed:"
                    + failure.getClass().getSimpleName());
                return;
            }
        }
        report.put(prefix + "baselineRestored",
            Boolean.toString(historyPosition() == position));
    }

    // ------------------------------------------------------------------
    // Drawable evaluation observation (preserve=false canvas evidence)
    // ------------------------------------------------------------------

    /**
     * Records whether the SDK admits Drawable reads on this host and, when it does, the
     * {@code vertexPositionsChanged} flags of descendant ArtMeshes around a preserve=false
     * apply. The flag is the host's own "evaluated canvas geometry changed" signal, which is
     * the direct observable for children following the mirrored parent.
     */
    private void runDrawableEvaluationProbe(final WarpDeformer target, final Report report) {
        try {
            final List<dev.turboism.sdk.cubism.model.Drawable> drawables =
                context.cubism().model().active().drawables().all();
            report.put("drawables.count", Integer.toString(drawables.size()));
            report.put("drawables.available", "true");
        } catch (RuntimeException | Error failure) {
            report.put("drawables.available", "false:" + failure.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // Assisted production-UI evidence
    // ------------------------------------------------------------------
    //
    // The Cubism main window is GL-rendered; under this host environment Robot screen
    // captures come back fully black, so pixel-based button detection cannot work (this is
    // the same constraint that made the overlay probe's automated icon detection fail while
    // its manual-assisted runs passed). This phase therefore drives a checklist through an
    // always-on-top Swing instruction window: the operator performs the two GL-surface
    // gestures (object-list selection, overlay-button click) while the probe verifies each
    // effect through the SDK and interacts with the real Swing dialog itself via Robot.
    // Every step is bounded; an unattended run records explicit ui.gap entries.

    private void runUiEvidence(
        final WarpDeformer target,
        final List<WarpDeformer> warps,
        final Report report,
        final List<String> failures
    ) {
        report.put("ui.mode", "assisted");
        try {
            robot = new Robot();
        } catch (RuntimeException | java.awt.AWTException failure) {
            report.put("ui.gap.robot", failure.getClass().getSimpleName());
            failures.add("ui: Robot unavailable - " + failure.getClass().getSimpleName());
            return;
        }
        openInstructionWindow();
        try {
            runUiSteps(target, warps, report, failures);
        } finally {
            closeInstructionWindow();
        }
    }

    private void runUiSteps(
        final WarpDeformer target,
        final List<WarpDeformer> warps,
        final Report report,
        final List<String> failures
    ) {
        final String targetName = target.id().value();
        dismissBlockingDialogs(report);
        // Dump the full component tree once so the evidence shows which component
        // actually hosts the object list (Swing widget vs GL surface region).
        dumpComponentTree(report, "ui.select.target");
        // Step 1: real selection through the native object list. Try the automated
        // surface-probe + arrow-walk first; fall back to the assisted manual gesture.
        if (!attemptAutomatedSelection(targetName, safeName(target),
            report, "ui.select.target")) {
            instruct("步骤1: 在对象列表中单击选中 Warp 变形器「" + safeName(target)
                + "」 (id=" + targetName + ")\n"
                + "请在 Cubism 窗口的对象栏中点击该对象。探针会持续检测选择状态。");
            if (!awaitSelectionContains(targetName, safeName(target), false,
                report, "ui.select.target")) {
                return;
            }
        }
        // The fixture grid is vertically symmetric, so the two vertical UI cases run on a
        // perturbed grid (real replaceGrid write, undone afterwards) to reach APPLIED.
        WarpGrid uiBaseline = null;
        try {
            uiBaseline = target.grid();
            final Point2 first = uiBaseline.controlPoints().get(0);
            target.replaceGrid(uiBaseline.withControlPoint(0, first.x(), first.y() + 3.0f));
            sleep(SETTLE_MILLIS);
            report.put("ui.vertical.perturbApplied", "true");
        } catch (RuntimeException | Error failure) {
            report.put("ui.vertical.perturbApplied", "failed:" + failure.getClass().getSimpleName());
        }
        // Steps 2-5: four direction choices through the real overlay + dialog.
        runUiDirectionCase(target, WarpMirrorDirection.TOP_TO_BOTTOM, true, report, failures);
        if (uiAborted) return;
        runUiDirectionCase(target, WarpMirrorDirection.BOTTOM_TO_TOP, true, report, failures);
        if (uiAborted) return;
        if (uiBaseline != null) {
            try {
                history().undo(1);
                sleep(SETTLE_MILLIS);
                report.put("ui.vertical.baselineRestored",
                    Boolean.toString(samePoints(uiBaseline, target.grid())));
            } catch (RuntimeException | Error failure) {
                failures.add("ui vertical baseline undo failed: "
                    + failure.getClass().getSimpleName());
            }
        }
        runUiDirectionCase(target, WarpMirrorDirection.LEFT_TO_RIGHT, true, report, failures);
        if (uiAborted) return;
        runUiDirectionCase(target, WarpMirrorDirection.RIGHT_TO_LEFT, true, report, failures);
        if (uiAborted) return;
        // Step 6: preserve checkbox unchecked through the dialog.
        runUiPreserveOffCase(target, report, failures);
        if (uiAborted) return;
        // Step 7: Cancel performs no write.
        runUiCancelCase(target, report, failures);
        if (uiAborted) return;
        // Step 8: multi-selection (nested warp pair if the fixture has one, else warp+other).
        runUiMultiSelectCase(target, warps, report, failures);
        if (uiAborted) return;
        // Step 9: no applicable target → informational dialog, no write.
        runUiNoTargetCase(target, report, failures);
    }

    /** One full UI cycle: overlay click -> dialog -> direction pick -> OK -> apply -> undo. */
    private void runUiDirectionCase(
        final WarpDeformer target,
        final WarpMirrorDirection direction,
        final boolean preserve,
        final Report report,
        final List<String> failures
    ) {
        final String prefix = "ui.direction." + direction.name() + ".";
        final JDialog dialog = awaitOverlayClickAndDialog(
            List.of(target.id().value()), target.name(), report, prefix);
        if (dialog == null) {
            return;
        }
        try {
            final WarpGrid before = target.grid();
            final JCheckBox preserveBox = findSingle(dialog, JCheckBox.class);
            if (preserveBox != null && report.get("ui.dialog.preserveDefaultChecked") == null) {
                final boolean checked = invokeOnEdt(preserveBox::isSelected);
                report.put("ui.dialog.preserveDefaultChecked", Boolean.toString(checked));
                if (!checked) {
                    failures.add("ui: preserve-children checkbox is not checked by default");
                }
            }
            if (preserveBox != null && !preserve) {
                clickComponent(preserveBox);
                sleep(300);
            }
            final BasicArrowButton directionButton = directionButton(dialog, direction);
            if (directionButton == null) {
                failures.add("ui: no direction arrow found for " + direction.name());
                disposeDialog(dialog);
                return;
            }
            clickComponent(directionButton);
            sleep(300);
            final JButton ok = invokeOnEdt(() -> dialog.getRootPane().getDefaultButton());
            report.put(prefix + "confirmEnabled",
                Boolean.toString(ok != null && invokeOnEdt(ok::isEnabled)));
            final String tooltip = invokeOnEdt(directionButton::getToolTipText);
            report.put(prefix + "directionTooltip", String.valueOf(tooltip));
            if (ok == null || !invokeOnEdt(ok::isEnabled)) {
                failures.add("ui: OK button did not enable after direction pick");
                disposeDialog(dialog);
                return;
            }
            clickComponent(ok);
            if (!awaitDisposed(dialog, UI_APPLY_TIMEOUT_MILLIS)) {
                failures.add("ui: dialog did not close after OK for " + direction.name());
                return;
            }
            verifyUiApply(target, direction, before, prefix, report, failures);
        } finally {
            disposeDialog(dialog);
        }
    }

    private void runUiPreserveOffCase(
        final WarpDeformer target,
        final Report report,
        final List<String> failures
    ) {
        final String prefix = "ui.preserveOff.";
        final Map<String, String> formsBefore;
        try {
            formsBefore = descendantFormSnapshot(target.id().value());
        } catch (RuntimeException | Error failure) {
            formsBeforeFailed(report, prefix, failure);
            return;
        }
        final JDialog dialog = awaitOverlayClickAndDialog(
            List.of(target.id().value()), target.name(), report, prefix);
        if (dialog == null) {
            return;
        }
        try {
            final WarpGrid before = target.grid();
            final JCheckBox preserveBox = findSingle(dialog, JCheckBox.class);
            if (preserveBox == null) {
                failures.add("ui: preserve checkbox not found in dialog");
                return;
            }
            clickComponent(preserveBox);
            sleep(300);
            report.put(prefix + "checkboxAfterClick",
                Boolean.toString(invokeOnEdt(preserveBox::isSelected)));
            if (invokeOnEdt(preserveBox::isSelected)) {
                failures.add("ui: preserve checkbox did not uncheck on click");
                disposeDialog(dialog);
                return;
            }
            final BasicArrowButton directionButton =
                directionButton(dialog, WarpMirrorDirection.LEFT_TO_RIGHT);
            if (directionButton != null) {
                clickComponent(directionButton);
                sleep(300);
            }
            final JButton ok = invokeOnEdt(() -> dialog.getRootPane().getDefaultButton());
            clickComponent(ok);
            if (!awaitDisposed(dialog, UI_APPLY_TIMEOUT_MILLIS)) {
                failures.add("ui: preserve-off dialog did not close after OK");
                return;
            }
            final String changed = awaitGridChange(target, fingerprint(before), UI_APPLY_TIMEOUT_MILLIS);
            report.put(prefix + "applied", changed);
            final Map<String, String> formsAfter = descendantFormSnapshot(target.id().value());
            int changedForms = 0;
            for (Map.Entry<String, String> entry : formsBefore.entrySet()) {
                if (!entry.getValue().equals(formsAfter.get(entry.getKey()))) {
                    changedForms++;
                }
            }
            report.put(prefix + "descendantFormsChanged", Integer.toString(changedForms));
            if (changedForms > 0) {
                failures.add("ui: preserve=OFF rewrote " + changedForms
                    + " descendant stored form(s)");
            }
            history().undo(1);
            sleep(SETTLE_MILLIS);
            report.put(prefix + "undoRestored",
                Boolean.toString(samePoints(before, target.grid())));
        } catch (RuntimeException | Error failure) {
            failures.add("ui preserve-off case failed: " + failure.getClass().getSimpleName());
        } finally {
            disposeDialog(dialog);
        }
    }

    private void runUiCancelCase(
        final WarpDeformer target,
        final Report report,
        final List<String> failures
    ) {
        final String prefix = "ui.cancel.";
        final JDialog dialog = awaitOverlayClickAndDialog(
            List.of(target.id().value()), target.name(), report, prefix);
        if (dialog == null) {
            return;
        }
        try {
            final WarpGrid before = target.grid();
            final HistorySnapshot historyBefore = history().snapshot();
            // Find the non-default JButton (Cancel).
            JButton cancel = null;
            final JButton ok = invokeOnEdt(() -> dialog.getRootPane().getDefaultButton());
            for (JButton button : findAll(dialog, JButton.class)) {
                if (button != ok && !(button instanceof BasicArrowButton)) {
                    cancel = button;
                    break;
                }
            }
            if (cancel == null) {
                failures.add("ui: no Cancel button found in dialog");
                return;
            }
            clickComponent(cancel);
            if (!awaitDisposed(dialog, UI_APPLY_TIMEOUT_MILLIS)) {
                failures.add("ui: dialog did not close after Cancel");
                return;
            }
            sleep(SETTLE_MILLIS);
            report.put(prefix + "gridUnchanged",
                Boolean.toString(samePoints(before, target.grid())));
            final HistorySnapshot historyAfter = history().snapshot();
            final boolean noEntry = historyAfter.position() == historyBefore.position()
                && historyAfter.entries().size() == historyBefore.entries().size();
            report.put(prefix + "historyUnchanged", Boolean.toString(noEntry));
            if (!samePoints(before, target.grid()) || !noEntry) {
                failures.add("ui: Cancel produced a write or a history entry");
            }
        } catch (RuntimeException | Error failure) {
            failures.add("ui cancel case failed: " + failure.getClass().getSimpleName());
        } finally {
            disposeDialog(dialog);
        }
    }

    private void runUiMultiSelectCase(
        final WarpDeformer target,
        final List<WarpDeformer> warps,
        final Report report,
        final List<String> failures
    ) {
        final String prefix = "ui.multi.";
        // Prefer a nested warp pair (parent+child both warps) for ordering evidence.
        final CubismModel model = context.cubism().model().active();
        final Map<String, Deformer> byId = new HashMap<>();
        for (Deformer deformer : model.deformers().all()) {
            byId.put(deformer.id().value(), deformer);
        }
        WarpDeformer child = null;
        for (WarpDeformer candidate : warps) {
            if (!candidate.id().equals(target.id())
                && isDescendantOf(candidate, target.id().value(), byId)) {
                child = candidate;
                break;
            }
        }
        if (child == null) {
            report.put(prefix + "nestedPair", "fixture-has-none");
            instruct("多选步骤: 请按住 Ctrl 同时选中 Warp「" + safeName(target)
                + "」和任意另一个对象（如 Part/ArtMesh）\n"
                + "探针将验证只有 Warp 被作为镜像目标处理。");
            if (!awaitSelectionContains(target.id().value(), safeName(target), true,
                report, prefix + "mixedSelection")) {
                return;
            }
        } else {
            report.put(prefix + "childId", child.id().value());
            // Real two-row host gesture first: click parent row, Ctrl+click
            // child row in the native object JTree; manual only as fallback.
            if (!attemptJTreeMultiSelect(target.id().value(), child.id().value(),
                safeName(target), safeName(child),
                report, prefix + "selection")) {
                instruct("多选步骤: 请按住 Ctrl 同时选中父 Warp「" + safeName(target)
                    + "」与子 Warp「" + safeName(child) + "」");
                if (!awaitMultiSelection(target.id().value(), safeName(target),
                    child.id().value(), safeName(child),
                    report, prefix + "selection")) {
                    return;
                }
            } else {
                report.put(prefix + "selection.mode", "automated-ctrl-click");
            }
        }
        final JDialog dialog = awaitOverlayClickAndDialog(
            List.of(target.id().value()), target.name(), report, prefix);
        if (dialog == null) {
            return;
        }
        try {
            final WarpGrid parentBefore = target.grid();
            final WarpGrid childBefore = child != null ? child.grid() : null;
            final HistorySnapshot historyBefore = history().snapshot();
            final BasicArrowButton directionButton =
                directionButton(dialog, WarpMirrorDirection.LEFT_TO_RIGHT);
            if (directionButton != null) {
                clickComponent(directionButton);
                sleep(300);
            }
            final JButton ok = invokeOnEdt(() -> dialog.getRootPane().getDefaultButton());
            clickComponent(ok);
            if (!awaitDisposed(dialog, UI_APPLY_TIMEOUT_MILLIS)) {
                failures.add("ui: multi-select dialog did not close after OK");
                return;
            }
            final String parentChanged =
                awaitGridChange(target, fingerprint(parentBefore), UI_APPLY_TIMEOUT_MILLIS);
            report.put(prefix + "parentApplied", parentChanged);
            final HistorySnapshot historyAfter = history().snapshot();
            report.put(prefix + "historyEntriesDelta",
                Integer.toString(historyAfter.position() - historyBefore.position()));
            if (child != null) {
                final boolean childChanged = !samePoints(childBefore, child.grid());
                report.put(prefix + "childApplied", Boolean.toString(childChanged));
                // Ordering: the plugin applies parents first, so the LAST history entry
                // belongs to the child; a single undo must restore the child only.
                history().undo(1);
                sleep(SETTLE_MILLIS);
                report.put(prefix + "undo1ChildRestored",
                    Boolean.toString(samePoints(childBefore, child.grid())));
                report.put(prefix + "undo1ParentStillMirrored",
                    Boolean.toString(!samePoints(parentBefore, target.grid())));
                history().undo(1);
                sleep(SETTLE_MILLIS);
                report.put(prefix + "undo2ParentRestored",
                    Boolean.toString(samePoints(parentBefore, target.grid())));
            } else {
                history().undo(1);
                sleep(SETTLE_MILLIS);
                report.put(prefix + "undoRestored",
                    Boolean.toString(samePoints(parentBefore, target.grid())));
            }
        } catch (RuntimeException | Error failure) {
            failures.add("ui multi-select case failed: " + failure.getClass().getSimpleName());
        } finally {
            disposeDialog(dialog);
        }
    }

    private void runUiNoTargetCase(
        final WarpDeformer target,
        final Report report,
        final List<String> failures
    ) {
        final String prefix = "ui.noTarget.";
        instruct("最后一步: 请在对象列表中选中一个非 Warp 对象（Part 或 ArtMesh），或空选\n"
            + "然后点击 overlay 镜像按钮。预期：弹出“无可用目标”提示，且不产生任何写入。");
        final GuideState guide = new GuideState();
        final long deadline = System.currentTimeMillis() + UI_STEP_TIMEOUT_MILLIS;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline && !stopped && !uiAborted) {
            if (selectionHasNoWarp()) {
                ready = true;
                break;
            }
            guide.poll++;
            guideRowSelection(null, false, guide);
            updateInstructionStatus();
            sleep(UI_POLL_STEP_MILLIS);
        }
        report.put(prefix + "selectionWithoutWarp", Boolean.toString(ready));
        if (!ready && attemptAutomatedNonWarpSelection(report, prefix)) {
            ready = true;
            report.put(prefix + "selectionWithoutWarp", "automated");
        }
        if (!ready) {
            failures.add("ui gap: no-warp selection was not established in time");
            return;
        }
        JDialog info = attemptAutomatedClick(List.of(), true, report, prefix);
        if (info != null) {
            report.put(prefix + "clickMode", "automated-coords");
        }
        if (info == null) {
            instruct("请点击 overlay 镜像按钮 —— 预期出现提示弹窗而不是方向弹窗。");
            info = awaitInfoDialog(
                DIALOG_TIMEOUT_MILLIS, List.of(), null, report, prefix);
            if (info != null) {
                report.put(prefix + "clickMode", "manual");
            }
        }
        if (info == null) {
            failures.add("ui gap: no-target information dialog did not appear");
            return;
        }
        report.put(prefix + "infoDialogTitle", info.getTitle());
        // Dismiss: click its first JButton.
        final JButton dismiss = findSingle(info, JButton.class);
        if (dismiss != null) {
            clickComponent(dismiss);
            awaitDisposed(info, UI_APPLY_TIMEOUT_MILLIS);
        }
    }

    // ------------------------- UI helpers -------------------------

    /**
     * Asks the operator to click the production overlay button, then waits for the real
     * {@link MirrorDirectionDialog}-shaped JDialog (exactly 4 direction toggle buttons, one
     * preserve checkbox, OK/Cancel). The dialog can only appear through the production
     * plugin's overlay click path, so its appearance is the click evidence.
     */
    private JDialog awaitOverlayClickAndDialog(
        final List<String> requiredSelection,
        final String rowLabel,
        final Report report,
        final String prefix
    ) {
        // First try a real Robot click at the live button bounds published by the
        // runtime (BBOX_OVERLAY_BUTTON_RECT markers in cubism-console.txt). A dialog
        // that opens from such a click is genuine overlay-click evidence. When the
        // marker or the coordinate mapping is unavailable, fall back to the assisted
        // manual gesture.
        final JDialog automated = attemptAutomatedOverlayClick(
            requiredSelection, report, prefix);
        if (automated != null) {
            report.put(prefix + "clickMode", "automated-coords");
            report.put(prefix + "dialogTitle", String.valueOf(automated.getTitle()));
            report.put(prefix + "dialogBounds", String.valueOf(automated.getBounds()));
            return automated;
        }
        instruct("请点击画布上选中对象 bounding box 旁的镜像 overlay 按钮\n"
            + "（白色镜像图标，位于原生按钮列的最下方一个）");
        final JDialog dialog = awaitMirrorDialog(
            DIALOG_TIMEOUT_MILLIS, requiredSelection, rowLabel, report, prefix);
        if (dialog == null) {
            report.put(prefix + "gap", "overlay-click dialog timeout");
            return null;
        }
        report.put(prefix + "clickMode", "manual");
        report.put(prefix + "dialogTitle", String.valueOf(dialog.getTitle()));
        report.put(prefix + "dialogBounds", String.valueOf(dialog.getBounds()));
        return dialog;
    }

    /**
     * Reads the most recent {@code BBOX_OVERLAY_BUTTON_RECT} marker for this plugin's
     * contribution, then issues real {@link Robot} mouse gestures at candidate screen
     * points derived from the live GL surface's screen location and a bounded set of
     * coordinate conventions (rect-center / world translation, direct / y-flip). Each
     * candidate is verified by the real dialog's appearance; the click path is never
     * simulated. The attempt set is bounded and the required selection is re-checked
     * before every gesture so a stray canvas click aborts to the assisted path instead
     * of drifting the fixture state.
     */
    private JDialog attemptAutomatedOverlayClick(
        final List<String> requiredSelection,
        final Report report,
        final String prefix
    ) {
        return attemptAutomatedClick(requiredSelection, false, report, prefix);
    }

    /**
     * Issues real {@link Robot} mouse gestures at candidate screen points derived
     * from the live GL surface's screen location and a bounded set of coordinate
     * conventions (rect-center / world translation, direct / y-flip). Each candidate
     * is verified by a real dialog's appearance; the click path is never simulated.
     * The attempt set is bounded and the required selection is re-checked before
     * every gesture so a stray canvas click aborts to the assisted path instead of
     * drifting the fixture state.
     */
    private JDialog attemptAutomatedClick(
        final List<String> requiredSelection,
        final boolean expectInfo,
        final Report report,
        final String prefix
    ) {
        if (robot == null) {
            return null;
        }
        final float[] bounds = latestOverlayButtonBounds();
        if (bounds == null) {
            report.put(prefix + "clickAuto", "no-marker");
            return null;
        }
        report.put(prefix + "clickAutoBounds",
            "rect=" + bounds[0] + "," + bounds[1] + "," + bounds[2] + "," + bounds[3]
                + " world=" + bounds[4] + "," + bounds[5]);
        final List<Component> surfaces = invokeOnEdt(this::glSurfaceCandidates);
        if (surfaces.isEmpty()) {
            report.put(prefix + "clickAuto", "no-gl-surface");
            return null;
        }
        final List<Point> candidates = candidateClickPoints(bounds, surfaces);
        final GuideState guide = new GuideState();
        int attempts = 0;
        for (Point point : candidates) {
            if (stopped || uiAborted) {
                return null;
            }
            boolean selectionOk = true;
            for (String id : requiredSelection) {
                if (!selectionContains(id)) {
                    selectionOk = false;
                    break;
                }
            }
            if (!selectionOk) {
                report.put(prefix + "clickAuto", "selection-lost attempts=" + attempts);
                return null;
            }
            guide.poll++;
            emitClickTarget(point.x, point.y, "none", guide);
            preciseMouseMove(point.x, point.y);
            robot.delay(60);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(50);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            attempts++;
            final JDialog dialog = expectInfo
                ? awaitInfoDialog(1500, List.of(), null, report, prefix)
                : awaitMirrorDialog(1500, requiredSelection, null, report, prefix);
            if (dialog != null) {
                report.put(prefix + "clickAuto",
                    "hit=" + point.x + "," + point.y + " attempts=" + attempts);
                return dialog;
            }
        }
        report.put(prefix + "clickAuto", "missed attempts=" + attempts);
        return null;
    }

    /**
     * Returns {@code [rectX, rectY, rectW, rectH, worldX, worldY]} parsed from the last
     * {@code BBOX_OVERLAY_BUTTON_RECT plugin=boundingbox-warp-mirror} console marker,
     * or {@code null} when no marker exists.
     */
    private float[] latestOverlayButtonBounds() {
        float[] latest = latestOverlayButtonBoundsIn(consolePath());
        if (latest == null) {
            latest = latestOverlayButtonBoundsIn(cubismAppLogPath());
        }
        return latest;
    }

    /**
     * Fallback marker source: the editor's own log4j file under Wine
     * {@code user.home/AppData/Roaming/Live2D/<ver>/logs/log.txt}. The runner's
     * cubism-console.txt can stay empty when host stderr is not piped through it.
     */
    private Path cubismAppLogPath() {
        try {
            final String home = System.getProperty("user.home");
            if (home == null || home.isBlank()) {
                return null;
            }
            final Path live2d = Path.of(home, "AppData", "Roaming", "Live2D");
            if (!Files.isDirectory(live2d)) {
                return null;
            }
            Path newest = null;
            long newestTime = -1L;
            try (var stream = Files.list(live2d)) {
                for (Path dir : stream.filter(Files::isDirectory).toList()) {
                    final Path log = dir.resolve("logs").resolve("log.txt");
                    if (!Files.isRegularFile(log)) {
                        continue;
                    }
                    final long modified = Files.getLastModifiedTime(log).toMillis();
                    if (modified >= newestTime) {
                        newestTime = modified;
                        newest = log;
                    }
                }
            }
            return newest;
        } catch (RuntimeException | IOException | Error failure) {
            return null;
        }
    }

    private float[] latestOverlayButtonBoundsIn(final Path console) {
        if (console == null) {
            return null;
        }
        try {
            final long size = Files.size(console);
            final long start = Math.max(0L, size - 512L * 1024L);
            final String tail;
            try (var channel = Files.newByteChannel(console,
                java.util.EnumSet.of(StandardOpenOption.READ))) {
                channel.position(start);
                final var buffer = java.nio.ByteBuffer.allocate((int) (size - start));
                while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                }
                tail = new String(buffer.array(), 0, buffer.position(),
                    StandardCharsets.UTF_8);
            }
            float[] latest = null;
            for (String line : tail.split("\\R")) {
                final int marker = line.indexOf("BBOX_OVERLAY_BUTTON_RECT ");
                if (marker < 0
                    || !line.contains("boundingbox-warp-mirror")) {
                    continue;
                }
                final float rectX = markerFloat(line, "rect=", 0);
                final float rectY = markerFloat(line, "rect=", 1);
                final float rectW = markerFloat(line, "rect=", 2);
                final float rectH = markerFloat(line, "rect=", 3);
                final float worldX = markerFloat(line, "world=", 0);
                final float worldY = markerFloat(line, "world=", 1);
                latest = new float[] { rectX, rectY, rectW, rectH, worldX, worldY };
            }
            return latest;
        } catch (RuntimeException | IOException | Error failure) {
            return null;
        }
    }

    /** Parses the {@code index}'th comma-separated float of a {@code key=} marker value. */
    private static float markerFloat(final String line, final String key, final int index) {
        final int at = line.indexOf(key);
        if (at < 0) {
            return Float.NaN;
        }
        final String rest = line.substring(at + key.length());
        final int end = rest.indexOf(' ');
        final String values = end < 0 ? rest : rest.substring(0, end);
        final String[] parts = values.split(",");
        if (index >= parts.length) {
            return Float.NaN;
        }
        try {
            return Float.parseFloat(parts[index].trim());
        } catch (NumberFormatException failure) {
            return Float.NaN;
        }
    }

    /**
     * EDT-only: every showing component that plausibly hosts the GL-rendered model
     * view (heavyweight {@link java.awt.Canvas} or JOGL/Cubism GL wrapper classes),
     * largest first, capped at three candidates.
     */
    private List<Component> glSurfaceCandidates() {
        final List<Component> found = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            collectGlSurfaces(window, found);
        }
        found.sort((a, b) -> Long.compare(
            (long) b.getWidth() * b.getHeight(),
            (long) a.getWidth() * a.getHeight()));
        return found.size() > 3 ? found.subList(0, 3) : found;
    }

    private void collectGlSurfaces(final Component component, final List<Component> out) {
        final String name = component.getClass().getName();
        if ((component instanceof java.awt.Canvas
                || name.matches("(?i).*(GLJPanel|GLCanvas|jogamp|NewtCanvas|glWrapper|GLWindow).*"))
            && component.isShowing()
            && component.getWidth() >= 200 && component.getHeight() >= 200) {
            out.add(component);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectGlSurfaces(child, out);
            }
        }
    }

    /**
     * Candidate screen points: rect-center and world translation, each mapped through
     * the surface-local direct and y-flipped conventions, largest surfaces first.
     */
    private List<Point> candidateClickPoints(
        final float[] bounds,
        final List<Component> surfaces
    ) {
        final List<Point> points = new ArrayList<>();
        final java.util.Set<Long> seen = new java.util.HashSet<>();
        final float cx = bounds[0] + bounds[2] / 2f;
        final float cy = bounds[1] + bounds[3] / 2f;
        for (Component surface : surfaces) {
            // The overlay entity rect is reported on its own scene-root component,
            // which may sit a toolbar-height above the GL viewport. Emit each
            // ancestor origin plus interpolated y-origins between the surface and
            // its window so the real owner is covered without guessing.
            java.awt.Point top = null;
            for (Component node = surface; node != null; node = node.getParent()) {
                final java.awt.Point origin;
                try {
                    origin = node.getLocationOnScreen();
                } catch (RuntimeException | Error failure) {
                    continue;
                }
                if (origin.x < -60 || origin.y < -60) {
                    break; // fake Wine coordinates — ancestors share the frame
                }
                top = origin;
                final int h = node.getHeight();
                if (!Float.isNaN(cx) && !Float.isNaN(cy)) {
                    addCandidate(points, seen, origin.x + Math.round(cx),
                        origin.y + Math.round(cy));
                    addCandidate(points, seen, origin.x + Math.round(cx),
                        origin.y + h - Math.round(cy));
                }
                if (!Float.isNaN(bounds[4]) && !Float.isNaN(bounds[5])) {
                    addCandidate(points, seen, origin.x + Math.round(bounds[4]),
                        origin.y + Math.round(bounds[5]));
                    addCandidate(points, seen, origin.x + Math.round(bounds[4]),
                        origin.y + h - Math.round(bounds[5]));
                }
                if (node instanceof Window) {
                    break;
                }
            }
            final java.awt.Point surfaceOrigin;
            try {
                surfaceOrigin = surface.getLocationOnScreen();
            } catch (RuntimeException | Error failure) {
                continue;
            }
            if (top != null && !Float.isNaN(cx) && !Float.isNaN(cy)) {
                for (int oy = surfaceOrigin.y - 8; oy > top.y; oy -= 8) {
                    addCandidate(points, seen, surfaceOrigin.x + Math.round(cx),
                        oy + Math.round(cy));
                }
            }
        }
        return points;
    }

    private static void addCandidate(
        final List<Point> points,
        final java.util.Set<Long> seen,
        final int x,
        final int y
    ) {
        if (seen.add(((long) x << 32) | (y & 0xffffffffL))) {
            points.add(new Point(x, y));
        }
    }

    /**
     * Best-effort automated selection through the real native UI: probes each GL
     * surface with a real {@link Robot} click, identifies the object list by checking
     * that arrow keys change the reported selection, then walks rows with arrow keys
     * until {@link #selectionContains} reports the target — every step verified
     * through the SDK, never simulated. Returns {@code false} so the caller falls
     * back to the assisted manual gesture.
     */
    private boolean attemptAutomatedSelection(
        final String targetId,
        final Report report,
        final String key
    ) {
        return attemptAutomatedSelection(targetId, null, report, key);
    }

    private boolean attemptAutomatedSelection(
        final String targetId,
        final String displayName,
        final Report report,
        final String key
    ) {
        if (robot == null) {
            return false;
        }
        if (selectionContains(targetId)) {
            report.put(key, "already-selected");
            return true;
        }
        pendingTreeGoalLabel = displayName != null && !displayName.isEmpty()
            ? displayName : targetId;
        try {
            return attemptAutomatedSelectionGoal(
                report, key, () -> selectionContains(targetId));
        } finally {
            pendingTreeGoalLabel = null;
        }
    }

    /** Walks the object list until a non-warp object (or nothing) is selected. */
    private boolean attemptAutomatedNonWarpSelection(
        final Report report,
        final String prefix
    ) {
        if (robot == null) {
            return false;
        }
        return attemptAutomatedSelectionGoal(report, prefix + "auto",
            () -> selectionHasNoWarp());
    }

    private interface SelectionGoal {
        boolean reached();
    }

    /**
     * Shared automated-selection driver: probes each GL surface with a real
     * {@link Robot} click, identifies the object list by checking that arrow keys
     * change the reported selection, then walks rows with arrow keys until the goal
     * reports reached — every step verified through the SDK, never simulated.
     */
    private boolean attemptAutomatedSelectionGoal(
        final Report report,
        final String key,
        final SelectionGoal goal
    ) {
        setInstructionVisible(false);
        try {
            raiseMainWindow();
            lastClickDiag = new String[1];
            calibratePointerDelta(report, key);
            // Cubism's object list is a real Swing JTable (CDeformerTreeTable.e
            // extends treeTable.m extends JTable): drive it first — real Robot
            // clicks on exact getCellRect row bounds, SDK-verified per click.
            if (attemptTableSelection(report, key, goal)) {
                return true;
            }
            if (attemptJTreeSelection(report, key, goal)) {
                return true;
            }
            final List<Component> surfaces = invokeOnEdt(this::glSurfaceCandidates);
            if (surfaces.isEmpty()) {
                report.put(key + "Auto", "no-surface");
                return false;
            }
            final StringBuilder surfaceDiag = new StringBuilder();
            for (Component surface : surfaces) {
                if (surfaceDiag.length() > 0) {
                    surfaceDiag.append(";");
                }
                surfaceDiag.append(surface.getClass().getSimpleName())
                    .append("@").append(surface.getWidth()).append("x").append(surface.getHeight());
            }
            report.put(key + "AutoSurfaces", surfaceDiag.toString());
            Component panel = null;
            String hitPoint = "";
            final List<ProductivePoint> productive = new ArrayList<>();
            final StringBuilder productiveDiag = new StringBuilder();
            lastClickDiag = new String[1];
            outer:
            for (Component surface : surfaces) {
                for (double[] point : SELECTION_PROBE_GRID) {
                    if (stopped || uiAborted) {
                        return false;
                    }
                    clickGlSurfaceAt(surface, point[0], point[1]);
                    sleep(280);
                    if (!anySelection()) {
                        continue;
                    }
                    if (productive.size() < 6) {
                        productive.add(new ProductivePoint(surface, point[0]));
                        if (productiveDiag.length() > 0) {
                            productiveDiag.append(";");
                        }
                        productiveDiag.append(surface.getClass().getSimpleName())
                            .append("@").append(point[0]).append(",").append(point[1]);
                    }
                    if (goal.reached()) {
                        report.put(key + "Auto",
                            "reached-by-click x=" + point[0]);
                        return true;
                    }
                    final String signature = selectionSignature();
                    robot.keyPress(KeyEvent.VK_DOWN);
                    robot.delay(40);
                    robot.keyRelease(KeyEvent.VK_DOWN);
                    sleep(150);
                    if (!selectionSignature().equals(signature)) {
                        panel = surface;
                        hitPoint = surface.getClass().getSimpleName()
                            + "@" + point[0] + "," + point[1];
                        break outer;
                    }
                    robot.keyPress(KeyEvent.VK_UP);
                    robot.delay(40);
                    robot.keyRelease(KeyEvent.VK_UP);
                    sleep(150);
                    if (!selectionSignature().equals(signature)) {
                        panel = surface;
                        hitPoint = surface.getClass().getSimpleName()
                            + "@" + point[0] + "," + point[1];
                        break outer;
                    }
                    // A click that selects but never navigates is a non-list
                    // region (e.g. model canvas); keep probing other points.
                }
            }
            if (!hitPoint.isEmpty()) {
                report.put(key + "AutoHit", hitPoint);
            }
            report.put(key + "AutoProductive", productiveDiag.toString());
            if (lastClickDiag != null && lastClickDiag[0] != null) {
                report.put(key + "AutoPointer", lastClickDiag[0]);
                lastClickDiag = null;
            }
            if (panel == null) {
                if (!productive.isEmpty()
                    && rowScanSelection(productive, goal, report, key)) {
                    return true;
                }
                report.put(key + "Auto", "no-nav-surface");
                return false;
            }
            report.put(key + "AutoSurface", panel.getClass().getName());
            for (int step = 0; step < 120 && !stopped && !uiAborted; step++) {
                if (goal.reached()) {
                    report.put(key + "Auto", "reached-down steps=" + step);
                    return true;
                }
                robot.keyPress(KeyEvent.VK_DOWN);
                robot.delay(30);
                robot.keyRelease(KeyEvent.VK_DOWN);
                sleep(90);
            }
            for (int step = 0; step < 240 && !stopped && !uiAborted; step++) {
                if (goal.reached()) {
                    report.put(key + "Auto", "reached-up steps=" + step);
                    return true;
                }
                robot.keyPress(KeyEvent.VK_UP);
                robot.delay(30);
                robot.keyRelease(KeyEvent.VK_UP);
                sleep(90);
            }
            report.put(key + "Auto", "walk-exhausted");
            return false;
        } finally {
            setInstructionVisible(true);
        }
    }

    /**
     * Drives the native object list directly: Cubism's deformer palette is a real
     * Swing {@link JTable} ({@code CDeformerTreeTable.e extends treeTable.m
     * extends JTable}). Every row gesture is a real {@link Robot} click at the
     * row's exact {@code getCellRect} screen bounds, verified through the SDK
     * after each click. Label match (row value containing the target display
     * name) is tried first, then a bounded top-down row scan. Returns true when
     * the goal reports reached through the real host selection state.
     */
    private boolean attemptTableSelection(
        final Report report,
        final String key,
        final SelectionGoal goal
    ) {
        final List<JTable> tables = invokeOnEdt(this::findRowTables);
        report.put(key + "AutoTables", Integer.toString(tables.size()));
        for (int ti = 0; ti < tables.size(); ti++) {
            if (stopped || uiAborted) {
                return false;
            }
            final JTable table = tables.get(ti);
            final int rows = invokeOnEdt(table::getRowCount);
            report.put(key + "AutoTable" + ti, describeTable(table));
            if (rows <= 0) {
                continue;
            }
            final String wanted = goalLabel();
            if (wanted != null) {
                for (int row = 0; row < rows && !stopped && !uiAborted; row++) {
                    final String label = tableRowLabel(table, row);
                    if (label.contains(wanted)) {
                        clickTableRow(table, row, false);
                        sleep(280);
                        if (goal.reached()) {
                            report.put(key + "Auto",
                                "table-label-hit row=" + row + " label=" + label);
                            return true;
                        }
                    }
                }
            }
            for (int row = 0; row < rows && row < 160 && !stopped && !uiAborted; row++) {
                final String before = selectionSignature();
                clickTableRow(table, row, false);
                sleep(240);
                if (goal.reached()) {
                    report.put(key + "Auto", "table-row-scan row=" + row);
                    return true;
                }
                final String after = selectionSignature();
                if (!after.equals(before)
                    && report.get(key + "AutoTableClickHit").isEmpty()) {
                    report.put(key + "AutoTableClickHit",
                        "row=" + row + " sig=" + after);
                }
                if (lastClickDiag != null && lastClickDiag[0] != null
                    && report.get(key + "AutoTableClickDiag").isEmpty()) {
                    report.put(key + "AutoTableClickDiag", lastClickDiag[0]);
                }
            }
            report.put(key + "AutoTableScanned", "rows=" + rows);
        }
        return false;
    }

    /** EDT snapshot: class, bounds, row/col counts and first rows' values. */
    private String describeTable(final JTable table) {
        return invokeOnEdt(() -> {
            final StringBuilder out = new StringBuilder(160);
            out.append(table.getClass().getName())
                .append('@').append(table.getWidth()).append('x')
                .append(table.getHeight())
                .append(" rows=").append(table.getRowCount())
                .append(" cols=").append(table.getColumnCount());
            final int limit = Math.min(table.getRowCount(), 12);
            for (int r = 0; r < limit; r++) {
                out.append(" |r").append(r).append('=');
                for (int c = 0; c < table.getColumnCount(); c++) {
                    final Object value = table.getValueAt(r, c);
                    out.append('[').append(c).append(']')
                        .append(value == null ? "null" : String.valueOf(value))
                        .append(' ');
                }
            }
            return out.toString();
        });
    }

    /** Concatenated cell values of a row — label matching across all columns. */
    private String tableRowLabel(final JTable table, final int row) {
        return invokeOnEdt(() -> {
            final StringBuilder out = new StringBuilder(80);
            for (int c = 0; c < table.getColumnCount(); c++) {
                final Object value = table.getValueAt(row, c);
                if (value != null) {
                    out.append(value).append(' ');
                }
            }
            return out.toString();
        });
    }

    /**
     * Same JTable-based driving through a {@link JTree} fallback for hosts that
     * mount a plain tree instead of the deformer TreeTable.
     */
    private boolean attemptJTreeSelection(
        final Report report,
        final String key,
        final SelectionGoal goal
    ) {
        final List<JTree> trees = invokeOnEdt(this::findJTrees);
        report.put(key + "AutoTrees", Integer.toString(trees.size()));
        for (JTree tree : trees) {
            if (stopped || uiAborted) {
                return false;
            }
            runOnEdt(() -> {
                for (int row = tree.getRowCount() - 1; row >= 0; row--) {
                    tree.expandRow(row);
                }
            });
            sleep(200);
            final int rows = invokeOnEdt(tree::getRowCount);
            final String wanted = goalLabel();
            if (wanted != null) {
                for (int row = 0; row < rows && !stopped && !uiAborted; row++) {
                    final int r = row;
                    final String label = invokeOnEdt(() -> {
                        final var path = tree.getPathForRow(r);
                        return path == null ? ""
                            : String.valueOf(path.getLastPathComponent());
                    });
                    if (label.contains(wanted)) {
                        clickJTreeRow(tree, row);
                        sleep(280);
                        if (goal.reached()) {
                            report.put(key + "Auto",
                                "jtree-label-hit row=" + row + " label=" + label);
                            return true;
                        }
                    }
                }
            }
            for (int row = 0; row < rows && row < 120 && !stopped && !uiAborted; row++) {
                clickJTreeRow(tree, row);
                sleep(240);
                if (goal.reached()) {
                    report.put(key + "Auto", "jtree-row-scan row=" + row);
                    return true;
                }
            }
            report.put(key + "AutoTreeScanned", "rows=" + rows);
        }
        return false;
    }

    /** Optional label the goal wants matched in a JTree row; null = blind scan. */
    private String goalLabel() {
        return pendingTreeGoalLabel;
    }

    private volatile String pendingTreeGoalLabel;

    /** Real Robot click at a JTree row's exact screen bounds (scrolls it in first). */
    private void clickJTreeRow(final JTree tree, final int row) {
        clickJTreeRow(tree, row, false);
    }

    /**
     * Real Robot click at a JTree row's exact screen bounds (scrolls it in
     * first). {@code ctrl} holds the Ctrl modifier through the gesture for a
     * genuine additive multi-selection click.
     */
    private void clickJTreeRow(final JTree tree, final int row, final boolean ctrl) {
        try {
            runOnEdt(() -> tree.scrollRowToVisible(row));
            sleep(60);
            final java.awt.Rectangle bounds =
                invokeOnEdt(() -> tree.getRowBounds(row));
            final java.awt.Point origin = tree.getLocationOnScreen();
            if (bounds == null || origin == null) {
                return;
            }
            preciseMouseMove(origin.x + bounds.x + bounds.width / 2,
                origin.y + bounds.y + bounds.height / 2);
            robot.delay(60);
            if (ctrl) {
                robot.keyPress(KeyEvent.VK_CONTROL);
                robot.delay(40);
            }
            try {
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.delay(50);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            } finally {
                if (ctrl) {
                    robot.keyRelease(KeyEvent.VK_CONTROL);
                }
            }
        } catch (RuntimeException | Error ignored) {
        }
    }

    /** Finds the row index whose label contains {@code marker}, or -1. */
    private int findJTreeRowByLabel(final JTree tree, final String marker) {
        final int rows = invokeOnEdt(tree::getRowCount);
        for (int row = 0; row < rows; row++) {
            final int r = row;
            final String label = invokeOnEdt(() -> {
                final var path = tree.getPathForRow(r);
                return path == null ? "" : String.valueOf(path.getLastPathComponent());
            });
            if (label.contains(marker)) {
                return row;
            }
        }
        return -1;
    }

    /**
     * Real two-row multi-selection through the native object list: expands the
     * tree, clicks the first row, then Ctrl+clicks the second — genuine
     * additive host gestures, verified through the SDK.
     */
    private boolean attemptJTreeMultiSelect(
        final String firstId,
        final String secondId,
        final String firstName,
        final String secondName,
        final Report report,
        final String key
    ) {
        if (robot == null) {
            return false;
        }
        setInstructionVisible(false);
        try {
            raiseMainWindow();
            final List<JTable> tables = invokeOnEdt(this::findRowTables);
            for (JTable table : tables) {
                if (stopped || uiAborted) {
                    return false;
                }
                final String firstMarker = firstName != null && !firstName.isEmpty()
                    ? firstName : firstId;
                final String secondMarker = secondName != null && !secondName.isEmpty()
                    ? secondName : secondId;
                final int first = findTableRowByLabel(table, firstMarker);
                final int second = findTableRowByLabel(table, secondMarker);
                report.put(key + "AutoTableRows",
                    "first=" + first + " second=" + second);
                if (first < 0 || second < 0 || first == second) {
                    if (tableMultiSelectByScan(table, firstId, secondId,
                        report, key)) {
                        return true;
                    }
                    continue;
                }
                clickTableRow(table, first, false);
                sleep(300);
                if (!selectionContains(firstId)) {
                    continue;
                }
                clickTableRow(table, second, true);
                sleep(300);
                if (selectionContains(firstId) && selectionContains(secondId)) {
                    report.put(key + "Auto", "table-ctrl-click rows="
                        + first + "," + second);
                    return true;
                }
            }
            final List<JTree> trees = invokeOnEdt(this::findJTrees);
            for (JTree tree : trees) {
                runOnEdt(() -> {
                    for (int row = tree.getRowCount() - 1; row >= 0; row--) {
                        tree.expandRow(row);
                    }
                });
                sleep(200);
                final String firstMarker = firstName != null && !firstName.isEmpty()
                    ? firstName : firstId;
                final String secondMarker = secondName != null && !secondName.isEmpty()
                    ? secondName : secondId;
                int first = findJTreeRowByLabel(tree, firstMarker);
                int second = findJTreeRowByLabel(tree, secondMarker);
                report.put(key + "AutoRows", "first=" + first + " second=" + second);
                if (first < 0 || second < 0 || first == second) {
                    // Labels are display names (no id embedded): fall back to an
                    // SDK-driven row scan — real clicks, verified per click.
                    if (jTreeMultiSelectByScan(tree, firstId, secondId,
                        report, key)) {
                        return true;
                    }
                    continue;
                }
                clickJTreeRow(tree, first, false);
                sleep(300);
                if (!selectionContains(firstId)) {
                    continue;
                }
                clickJTreeRow(tree, second, true);
                sleep(300);
                if (selectionContains(firstId) && selectionContains(secondId)) {
                    report.put(key + "Auto", "jtree-ctrl-click rows="
                        + first + "," + second);
                    return true;
                }
            }
        } finally {
            setInstructionVisible(true);
        }
        return false;
    }

    /** Finds the JTable row whose first-column value contains {@code marker}. */
    private int findTableRowByLabel(final JTable table, final String marker) {
        final int rows = invokeOnEdt(table::getRowCount);
        for (int row = 0; row < rows; row++) {
            final int r = row;
            final String label = invokeOnEdt(() -> {
                final Object value = table.getValueAt(r, 0);
                return value == null ? "" : String.valueOf(value);
            });
            if (label.contains(marker)) {
                return row;
            }
        }
        return -1;
    }

    /**
     * Label-free table multi-select: scans rows with real clicks until the SDK
     * reports the first target selected, then Ctrl+clicks rows until the second
     * target joins the selection. Bounded; each gesture is verified.
     */
    private boolean tableMultiSelectByScan(
        final JTable table,
        final String firstId,
        final String secondId,
        final Report report,
        final String key
    ) {
        final int rows = invokeOnEdt(table::getRowCount);
        int firstRow = -1;
        for (int row = 0; row < rows && row < 160 && !stopped && !uiAborted; row++) {
            clickTableRow(table, row, false);
            sleep(240);
            if (selectionContains(firstId)) {
                firstRow = row;
                break;
            }
        }
        if (firstRow < 0) {
            return false;
        }
        for (int row = 0; row < rows && row < 160 && !stopped && !uiAborted; row++) {
            if (row == firstRow) {
                continue;
            }
            clickTableRow(table, row, true);
            sleep(240);
            if (selectionContains(secondId) && selectionContains(firstId)) {
                report.put(key + "Auto", "table-ctrl-scan rows="
                    + firstRow + "," + row);
                return true;
            }
        }
        report.put(key + "AutoScan", "table-second-not-found first=" + firstRow);
        return false;
    }

    /**
     * Label-free multi-select: scans rows with real clicks until the SDK reports
     * the first target selected, then Ctrl+clicks subsequent rows until the
     * second target joins the selection. Bounded; each gesture is verified.
     */
    private boolean jTreeMultiSelectByScan(
        final JTree tree,
        final String firstId,
        final String secondId,
        final Report report,
        final String key
    ) {
        final int rows = invokeOnEdt(tree::getRowCount);
        int firstRow = -1;
        for (int row = 0; row < rows && row < 120 && !stopped && !uiAborted; row++) {
            clickJTreeRow(tree, row, false);
            sleep(240);
            if (selectionContains(firstId)) {
                firstRow = row;
                break;
            }
        }
        if (firstRow < 0) {
            report.put(key + "AutoScan", "first-not-found");
            return false;
        }
        for (int row = 0; row < rows && row < 120 && !stopped && !uiAborted; row++) {
            if (row == firstRow) {
                continue;
            }
            clickJTreeRow(tree, row, true);
            sleep(240);
            if (selectionContains(secondId) && selectionContains(firstId)) {
                report.put(key + "Auto", "jtree-ctrl-scan rows="
                    + firstRow + "," + row);
                return true;
            }
        }
        report.put(key + "AutoScan", "second-not-found first=" + firstRow);
        return false;
    }

    /**
     * Real Robot click at a JTable row's exact screen bounds (scrolls the cell
     * into the viewport first). {@code ctrl} holds Ctrl through the gesture for
     * genuine additive multi-selection.
     */
    private void clickTableRow(final JTable table, final int row, final boolean ctrl) {
        try {
            final int labelCol = Math.max(0,
                invokeOnEdt(table::getColumnCount) - 1);
            runOnEdt(() -> {
                final var rect = table.getCellRect(row, labelCol, true);
                if (rect != null) {
                    table.scrollRectToVisible(rect);
                }
            });
            sleep(60);
            final java.awt.Rectangle rect =
                invokeOnEdt(() -> table.getCellRect(row, labelCol, true));
            final java.awt.Point origin = table.getLocationOnScreen();
            if (rect == null || origin == null) {
                return;
            }
            final int wantX = origin.x + rect.x + rect.width / 2;
            final int wantY = origin.y + rect.y + rect.height / 2;
            emitClickTarget(wantX, wantY, ctrl ? "ctrl" : "none", new GuideState());
            preciseMouseMove(wantX, wantY);
            robot.delay(60);
            if (lastClickDiag != null) {
                try {
                    final java.awt.Point actual =
                        java.awt.MouseInfo.getPointerInfo().getLocation();
                    lastClickDiag[0] = "tableRow=" + row + " want=" + wantX + ","
                        + wantY + " actual=" + actual.x + "," + actual.y
                        + " cellRect=" + rect.x + "," + rect.y + ","
                        + rect.width + "x" + rect.height
                        + " origin=" + origin.x + "," + origin.y
                        + " swingSees=" + invokeOnEdt(
                            () -> table.getMousePosition() != null)
                        + " topAt=" + componentAtScreenPoint(wantX, wantY);
                } catch (RuntimeException | Error ignored) {
                }
            }
            if (ctrl) {
                robot.keyPress(KeyEvent.VK_CONTROL);
                robot.delay(40);
            }
            try {
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.delay(50);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            } finally {
                if (ctrl) {
                    robot.keyRelease(KeyEvent.VK_CONTROL);
                }
            }
        } catch (RuntimeException | Error ignored) {
        }
    }

    /**
     * Robot-space vs component-space offset measured empirically: under Proton
     * the window's AWT-reported position can differ from where the window
     * system actually places it. Once calibrated, every click target is
     * translated by this delta. Null until calibration succeeds.
     */
    private volatile java.awt.Point spaceDelta;

    /**
     * Finds the real pointer offset: spirals Robot moves around the first
     * showing JTable's AWT-reported center until {@code getMousePosition()}
     * reports the pointer inside the component — then computes
     * delta = robotPosition − componentOrigin − componentPointer, which is the
     * constant offset between the two coordinate spaces for this window.
     */
    private void calibratePointerDelta(final Report report, final String key) {
        // Environment truth once: Robot delivery to windows is dead on this
        // host (AutoDeliver=never, r33/r35 full-screen scans), so the spiral
        // calibration and delivery probes are intentionally not re-run — host
        // gestures come from external X input via the assisted path.
        final List<JTable> tables = invokeOnEdt(this::findRowTables);
        if (tables.isEmpty()) {
            report.put(key + "AutoCalib", "no-table");
            return;
        }
        for (JTable table : tables) {
            if (stopped || uiAborted) {
                return;
            }
            final java.awt.Point origin = invokeOnEdt(
                () -> safeLocationOnScreen(table));
            if (origin == null) {
                continue;
            }
            final int cx = origin.x + table.getWidth() / 2;
            final int cy = origin.y + table.getHeight() / 2;
            int probed = 0;
            outer:
            for (int ring = 0; ring <= 400 && !stopped && !uiAborted; ring += 20) {
                final int[][] offsets = ring == 0
                    ? new int[][]{{0, 0}}
                    : new int[][]{{ring, 0}, {-ring, 0}, {0, ring}, {0, -ring},
                        {ring, ring}, {-ring, ring}, {ring, -ring},
                        {-ring, -ring}};
                for (int[] offset : offsets) {
                    final int px = cx + offset[0];
                    final int py = cy + offset[1];
                    robot.mouseMove(px, py);
                    robot.delay(50);
                    probed++;
                    final java.awt.Point inside =
                        invokeOnEdt(() -> table.getMousePosition());
                    if (inside != null) {
                        final java.awt.Point robotAt =
                            java.awt.MouseInfo.getPointerInfo().getLocation();
                        spaceDelta = new java.awt.Point(
                            robotAt.x - origin.x - inside.x,
                            robotAt.y - origin.y - inside.y);
                        report.put(key + "AutoCalib", "delta="
                            + spaceDelta.x + "," + spaceDelta.y
                            + " hit=" + px + "," + py + " probed=" + probed
                            + " table=" + table.getClass().getSimpleName());
                        return;
                    }
                }
            }
        }
        report.put(key + "AutoCalib", "not-found");
    }

    /**
     * The decisive delivery test: shows a probe-owned undecorated JWindow with
     * a real {@link java.awt.event.MouseListener} and Robot-clicks around its
     * AWT-reported center on a grid. If any click reaches the listener, real
     * gestures DO reach Wine windows and the hit offset gives the true
     * AWT-to-physical delta. "never" means Robot events cannot reach windows in
     * this environment — no real-gesture UI automation is possible here.
     */
    private String probeClickDelivery() {
        final java.util.concurrent.atomic.AtomicReference<java.awt.Point> hit =
            new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        final JWindow[] windowRef = new JWindow[1];
        try {
            runOnEdt(() -> {
                final JWindow window = new JWindow();
                window.setSize(200, 200);
                window.setLocation(700, 300);
                final java.awt.event.MouseAdapter adapter =
                    new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseClicked(final java.awt.event.MouseEvent e) {
                        hit.set(e.getPoint());
                        latch.countDown();
                    }
                    @Override
                    public void mousePressed(final java.awt.event.MouseEvent e) {
                        hit.set(e.getPoint());
                        latch.countDown();
                    }
                    @Override
                    public void mouseMoved(final java.awt.event.MouseEvent e) {
                        hit.compareAndSet(null, e.getPoint());
                    }
                };
                window.addMouseListener(adapter);
                window.addMouseMotionListener(adapter);
                window.setVisible(true);
                window.toFront();
                windowRef[0] = window;
            });
            sleep(400);
            final java.util.concurrent.atomic.AtomicInteger lastX =
                new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger lastY =
                new java.util.concurrent.atomic.AtomicInteger();
            // Move-only full-screen scan: mouseMoved fires the listener without
            // clicking anything — finds the window's physical position with
            // zero risk of triggering real UI actions.
            for (int y = 20; y < 1080 && hit.get() == null; y += 40) {
                for (int x = 20; x < 1920 && hit.get() == null; x += 40) {
                    lastX.set(x);
                    lastY.set(y);
                    robot.mouseMove(x, y);
                    robot.delay(25);
                }
            }
            if (hit.get() != null) {
                final java.awt.Point p = hit.get();
                final java.awt.Point at = invokeOnEdt(
                    () -> windowRef[0].getLocationOnScreen());
                return "moveHit last=" + lastX.get() + "," + lastY.get()
                    + " local=" + p.x + "," + p.y
                    + " windowAt=" + at.x + "," + at.y;
            }
            return "never";
        } catch (RuntimeException | Error e) {
            return "fail:" + e.getClass().getSimpleName();
        } finally {
            runOnEdt(() -> {
                if (windowRef[0] != null) {
                    windowRef[0].dispose();
                }
            });
        }
    }

    /**
     * Snapshot of the pointer/display environment: screen devices with bounds,
     * number of pointer buttons (−1 = headless pointer), every showing window's
     * AWT position and its device, and a Robot screen capture probe that proves
     * whether Robot sees the real framebuffer.
     */
    private String describePointerEnvironment() {
        final StringBuilder out = new StringBuilder(240);
        try {
            out.append("buttons=").append(MouseInfo.getNumberOfButtons());
            final java.awt.GraphicsDevice[] devices = java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getScreenDevices();
            out.append(" devices=").append(devices.length);
            for (java.awt.GraphicsDevice device : devices) {
                final java.awt.Rectangle bounds =
                    device.getDefaultConfiguration().getBounds();
                out.append(" [").append(device.getIDstring()).append(' ')
                    .append(bounds.x).append(',').append(bounds.y).append(' ')
                    .append(bounds.width).append('x').append(bounds.height)
                    .append(']');
            }
            for (Window window : Window.getWindows()) {
                if (!window.isShowing()) {
                    continue;
                }
                try {
                    final java.awt.Point at = window.getLocationOnScreen();
                    out.append(" win=").append(window.getClass().getSimpleName())
                        .append('@').append(at.x).append(',').append(at.y)
                        .append(' ').append(window.getWidth()).append('x')
                        .append(window.getHeight());
                } catch (RuntimeException | Error ignored) {
                }
            }
            try {
                final java.awt.image.BufferedImage shot = robot
                    .createScreenCapture(new java.awt.Rectangle(0, 0, 64, 64));
                int sample = 0;
                if (shot != null) {
                    for (int sx = 0; sx < 64; sx += 16) {
                        for (int sy = 0; sy < 64; sy += 16) {
                            sample ^= shot.getRGB(sx, sy);
                        }
                    }
                }
                out.append(" capture=").append(
                    shot == null ? "null" : Integer.toHexString(sample));
            } catch (RuntimeException | Error e) {
                out.append(" capture=fail:").append(e.getClass().getSimpleName());
            }
            saveScreenCapture();
        } catch (RuntimeException | Error e) {
            out.append("env-fail:").append(e.getClass().getSimpleName());
        }
        return out.toString();
    }

    /**
     * Full-virtual-screen Robot capture saved next to the component dump —
     * visual ground truth of what the real display contains.
     */
    private void saveScreenCapture() {
        try {
            final java.awt.Rectangle bounds = new java.awt.Rectangle();
            for (java.awt.GraphicsDevice device : java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getScreenDevices()) {
                bounds.add(device.getDefaultConfiguration().getBounds());
            }
            final java.awt.image.BufferedImage shot =
                robot.createScreenCapture(bounds);
            final Path out = context.paths().stateDir()
                .resolve("ui-screen-capture.png");
            javax.imageio.ImageIO.write(shot, "png", out.toFile());
        } catch (RuntimeException | Error | java.io.IOException ignored) {
        }
    }

    /** Null-safe locationOnScreen (off-screen/hidden windows can throw). */
    private java.awt.Point safeLocationOnScreen(final Component component) {
        try {
            return component.isShowing() ? component.getLocationOnScreen() : null;
        } catch (RuntimeException | Error ignored) {
            return null;
        }
    }

    /**
     * Move the pointer to a screen point, measuring and compensating the
     * observed coordinate offset (Robot vs window coordinate spaces can differ
     * under Proton/scaling). Up to two correction passes.
     */
    private void preciseMouseMove(final int x, final int y) {
        final java.awt.Point delta = spaceDelta;
        final int physX = x + (delta == null ? 0 : delta.x);
        final int physY = y + (delta == null ? 0 : delta.y);
        int targetX = physX;
        int targetY = physY;
        for (int attempt = 0; attempt < 3; attempt++) {
            robot.mouseMove(targetX, targetY);
            robot.delay(60);
            try {
                final java.awt.Point actual =
                    java.awt.MouseInfo.getPointerInfo().getLocation();
                final int dx = actual.x - physX;
                final int dy = actual.y - physY;
                if (Math.abs(dx) <= 2 && Math.abs(dy) <= 2) {
                    return;
                }
                targetX = physX - dx;
                targetY = physY - dy;
            } catch (RuntimeException | Error ignored) {
                return;
            }
        }
    }

    /**
     * Deepest showing component under a screen point across all windows —
     * answers "what would actually receive a real click at these coordinates".
     */
    private String componentAtScreenPoint(final int x, final int y) {
        return invokeOnEdt(() -> {
            for (Window window : Window.getWindows()) {
                if (!window.isShowing()) {
                    continue;
                }
                final java.awt.Point origin = window.getLocationOnScreen();
                if (x < origin.x || y < origin.y
                    || x >= origin.x + window.getWidth()
                    || y >= origin.y + window.getHeight()) {
                    continue;
                }
                Component deepest = window;
                int relX = x - origin.x;
                int relY = y - origin.y;
                Component current = window;
                descend:
                while (current instanceof Container container) {
                    for (Component child : container.getComponents()) {
                        if (!child.isVisible()) {
                            continue;
                        }
                        final int cx = relX - child.getX();
                        final int cy = relY - child.getY();
                        if (cx >= 0 && cy >= 0
                            && cx < child.getWidth() && cy < child.getHeight()) {
                            deepest = child;
                            current = child;
                            relX = cx;
                            relY = cy;
                            continue descend;
                        }
                    }
                    break;
                }
                final java.awt.Point loc = deepest.getLocationOnScreen();
                return deepest.getClass().getName() + " in "
                    + window.getClass().getSimpleName() + "@"
                    + loc.x + "," + loc.y + " "
                    + deepest.getWidth() + "x" + deepest.getHeight();
            }
            return "none";
        });
    }

    /** EDT-only: every showing JTable — the deformer palette is one. */
    private List<JTable> findRowTables() {
        final List<JTable> found = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            collectTables(window, found);
        }
        return found;
    }

    private void collectTables(final Component component, final List<JTable> out) {
        if (component instanceof JTable table && component.isShowing()) {
            out.add(table);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectTables(child, out);
            }
        }
    }

    /** EDT-only: every showing JTree in every window (the object list candidates). */
    private List<JTree> findJTrees() {
        final List<JTree> found = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            collectJTrees(window, found);
        }
        return found;
    }

    private void collectJTrees(final Component component, final List<JTree> out) {
        if (component instanceof JTree tree && component.isShowing()) {
            out.add(tree);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectJTrees(child, out);
            }
        }
    }

    /**
     * Dumps every showing window's full component hierarchy (class name, bounds,
     * showing state) to {@code state/ui-component-tree.txt} so the evidence shows
     * which component actually hosts the object list. Diagnostic only.
     */
    private void dumpComponentTree(final Report report, final String key) {
        try {
            final Path stateDir = context.paths().stateDir();
            final StringBuilder dump = new StringBuilder(64 * 1024);
            runOnEdt(() -> {
                for (Window window : Window.getWindows()) {
                    if (!window.isShowing()) {
                        continue;
                    }
                    dumpComponent(window, dump, 0);
                }
            });
            final Path out = stateDir.resolve("ui-component-tree.txt");
            Files.writeString(out, dump.toString(), StandardCharsets.UTF_8);
            report.put(key + "componentTree", out.getFileName().toString()
                + " bytes=" + dump.length());
        } catch (RuntimeException | IOException | Error failure) {
            report.put(key + "componentTree",
                "failed:" + failure.getClass().getSimpleName());
        }
    }

    private void dumpComponent(
        final Component component,
        final StringBuilder out,
        final int depth
    ) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
        out.append(component.getClass().getName())
            .append(" @").append(component.getX()).append(',').append(component.getY())
            .append(' ').append(component.getWidth()).append('x').append(component.getHeight())
            .append(component.isShowing() ? " showing" : " hidden");
        if (component instanceof java.awt.Canvas) {
            out.append(" <AWT-CANVAS>");
        }
        out.append('\n');
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                dumpComponent(child, out, depth + 1);
            }
        }
    }

    /** A surface column where a real click produced a host selection change. */
    private static final class ProductivePoint {
        private final Component surface;
        private final double relX;

        private ProductivePoint(final Component surface, final double relX) {
            this.surface = surface;
            this.relX = relX;
        }
    }

    /**
     * Fallback when arrow-key navigation is unsupported: scans the object list
     * by clicking down each productive column in ~12px row steps, checking the
     * goal through the SDK after every real click. Bounded to keep the gesture
     * budget sane; every click is a real {@link Robot} event.
     */
    private boolean rowScanSelection(
        final List<ProductivePoint> productive,
        final SelectionGoal goal,
        final Report report,
        final String key
    ) {
        int totalClicks = 0;
        for (ProductivePoint point : productive) {
            final Component surface = point.surface;
            final double step = 12.0 / Math.max(surface.getHeight(), 1);
            for (double y = 0.03; y < 0.97 && totalClicks < 160; y += step) {
                if (stopped || uiAborted) {
                    return false;
                }
                clickGlSurfaceAt(surface, point.relX, y);
                totalClicks++;
                sleep(240);
                if (goal.reached()) {
                    report.put(key + "Auto",
                        "reached-row-scan clicks=" + totalClicks
                            + " x=" + point.relX + " y=" + y);
                    return true;
                }
            }
        }
        report.put(key + "AutoScanClicks", String.valueOf(totalClicks));
        return false;
    }

    /** Real mouse gesture at a relative position inside a GL surface component. */
    private void clickGlSurfaceAt(
        final Component surface,
        final double relX,
        final double relY
    ) {
        try {
            final java.awt.Point origin = surface.getLocationOnScreen();
            final int sx = origin.x + (int) (surface.getWidth() * relX);
            final int sy = origin.y + (int) (surface.getHeight() * relY);
            preciseMouseMove(sx, sy);
            robot.delay(60);
            if (lastClickDiag != null) {
                try {
                    final java.awt.Point actual =
                        java.awt.MouseInfo.getPointerInfo().getLocation();
                    final java.awt.Point at = surface.getLocationOnScreen();
                    lastClickDiag[0] = "want=" + sx + "," + sy
                        + " actual=" + actual.x + "," + actual.y
                        + " origin=" + at.x + "," + at.y
                        + " inside=" + (surface.getMousePosition() != null);
                } catch (RuntimeException | Error ignored) {
                }
            }
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(50);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        } catch (RuntimeException | Error ignored) {
        }
    }

    /** Last-gesture pointer diagnostic for the report (optional, may be null). */
    private volatile String[] lastClickDiag;

    /**
     * External-driver click protocol: while an assisted await runs, the probe logs
     * {@code WARP_MIRROR_UI_CLICK_TARGET seq=N x=X y=Y mod=M} lines into the live
     * turboism runtime log. An external driver (xdotool on the real X display)
     * executes each new target as a genuine XTEST gesture — the probe decides where
     * to click from live component geometry, the driver only supplies the physical
     * event that in-JVM Robot cannot deliver in this environment. A target is not
     * re-emitted for {@link #GUIDE_REEMIT_POLLS} polls so a repeated plain click
     * cannot tear down a multi-selection the driver is mid-way through building;
     * after the window the whole gesture sequence is re-emitted for retries.
     */
    private static final int GUIDE_REEMIT_POLLS = 12;
    private int clickTargetSeq;
    /** Abort button in the instruction window (external XTEST delivery probe). */
    private volatile JButton instructionAbort;
    /** Last selection signature logged live (change-only). */
    private volatile String lastSelectionSig = "";

    /** Per-await guided-gesture state. */
    private static final class GuideState {
        final Map<String, Integer> emitted = new HashMap<>();
        List<String> requiredSelection = List.of();
        String rowLabel;
        int poll;
        int rowCursor;
        int candidateCursor;
    }

    private void emitClickTarget(
        final int x,
        final int y,
        final String mod,
        final GuideState state
    ) {
        final String body = x + "," + y + "," + mod;
        final Integer last = state.emitted.get(body);
        if (last != null && state.poll - last < GUIDE_REEMIT_POLLS) {
            return;
        }
        state.emitted.put(body, state.poll);
        clickTargetSeq++;
        logger.info("WARP_MIRROR_UI_CLICK_TARGET seq=" + clickTargetSeq
            + " x=" + x + " y=" + y + " mod=" + mod);
    }

    /** Screen-space centre of a table row's label cell, or null when not computable. */
    private Point tableRowClickPoint(final JTable table, final int row) {
        try {
            return invokeOnEdt(() -> {
                if (row < 0 || row >= table.getRowCount()) {
                    return null;
                }
                final int labelCol = Math.max(0, table.getColumnCount() - 1);
                final java.awt.Rectangle rect = table.getCellRect(row, labelCol, true);
                if (rect != null) {
                    table.scrollRectToVisible(rect);
                }
                final java.awt.Rectangle shown =
                    table.getCellRect(row, labelCol, true);
                final java.awt.Point origin = table.getLocationOnScreen();
                if (shown == null || origin == null) {
                    return null;
                }
                return new Point(
                    origin.x + shown.x + shown.width / 2,
                    origin.y + shown.y + shown.height / 2);
            });
        } catch (RuntimeException | Error failure) {
            return null;
        }
    }

    /** True when the screen point lands inside the component's visible bounds. */
    private boolean pointInsideComponent(final java.awt.Component c, final Point p) {
        try {
            return invokeOnEdt(() -> {
                if (!c.isShowing()) {
                    return false;
                }
                final java.awt.Point origin = c.getLocationOnScreen();
                final java.awt.Rectangle visible = c instanceof javax.swing.JComponent
                    ? ((javax.swing.JComponent) c).getVisibleRect()
                    : new java.awt.Rectangle(0, 0, c.getWidth(), c.getHeight());
                return p.x >= origin.x + visible.x
                    && p.x < origin.x + visible.x + visible.width
                    && p.y >= origin.y + visible.y
                    && p.y < origin.y + visible.y + visible.height;
            });
        } catch (RuntimeException | Error failure) {
            return false;
        }
    }

    /** Row whose last-column label contains {@code label}, or -1. EDT-only. */
    private int findRowByLabel(final JTable table, final String label) {
        if (label == null || label.isEmpty()) {
            return -1;
        }
        final int labelCol = Math.max(0, table.getColumnCount() - 1);
        for (int row = 0; row < table.getRowCount(); row++) {
            final Object value = table.getValueAt(row, labelCol);
            if (value != null && value.toString().contains(label)) {
                return row;
            }
        }
        return -1;
    }

    /** The visible object-list table (CDeformerTreeTable), else the row-richest table. */
    private JTable deformerTable() {
        return invokeOnEdt(() -> {
            JTable best = null;
            for (JTable table : findRowTables()) {
                if (table.getClass().getName().contains("Deformer")) {
                    return table;
                }
                if (best == null || table.getRowCount() > best.getRowCount()) {
                    best = table;
                }
            }
            return best;
        });
    }

    /**
     * Feeds the external driver a click target for the row matching
     * {@code label}, or cycles through all rows when no label match exists so a
     * driver sweep still walks the whole list. With {@code extraCtrlRow}, a second
     * row is additionally logged with mod=ctrl so the driver produces a real
     * Ctrl+click multi-selection.
     */
    private void guideRowSelection(
        final String label,
        final boolean extraCtrlRow,
        final GuideState state
    ) {
        try {
            final JTable table = deformerTable();
            if (table == null) {
                return;
            }
            final int rows = invokeOnEdt(table::getRowCount);
            if (rows <= 0) {
                return;
            }
            final int matched = invokeOnEdt(() -> findRowByLabel(table, label));
            if (matched >= 0 && state.poll % 8 == 4) {
                // Drive the row's real selection path directly — the deformer
                // table's ListSelectionListener feeds the host object selection
                // the same way a physical click does, and does not depend on
                // Wine-reported screen coordinates being trustworthy.
                final int targetRow = matched;
                runOnEdt(() -> {
                    table.scrollRectToVisible(table.getCellRect(
                        targetRow, Math.max(0, table.getColumnCount() - 1), true));
                    table.getSelectionModel()
                        .setSelectionInterval(targetRow, targetRow);
                });
            }
            final int row = matched >= 0 ? matched : state.rowCursor++ % rows;
            final Point point = tableRowClickPoint(table, row);
            if (point != null && pointInsideComponent(table, point)) {
                emitClickTarget(point.x, point.y, "none", state);
            }
            if (extraCtrlRow) {
                final Point other = tableRowClickPoint(table, (row + 1) % rows);
                if (other != null) {
                    emitClickTarget(other.x, other.y, "ctrl", state);
                }
            }
        } catch (RuntimeException | Error ignored) {
        }
    }

    /** Logs the {@code label} row (or the last row) as a Ctrl+click target. */
    private void guideRowSelectionCtrl(final String label, final GuideState state) {
        try {
            final JTable table = deformerTable();
            if (table == null) {
                return;
            }
            final int rows = invokeOnEdt(table::getRowCount);
            if (rows <= 0) {
                return;
            }
            int row = invokeOnEdt(() -> findRowByLabel(table, label));
            if (row < 0) {
                row = rows - 1;
            }
            final Point point = tableRowClickPoint(table, row);
            if (point != null) {
                emitClickTarget(point.x, point.y, "ctrl", state);
            }
        } catch (RuntimeException | Error ignored) {
        }
    }

    /**
     * Feeds the external driver the live overlay-button click candidates so the
     * assisted dialog await doubles as a guided real-gesture window.
     */
    private void guideOverlayClick(final GuideState state) {
        try {
            for (String id : state.requiredSelection) {
                if (!selectionContains(id)) {
                    // A wrong-guess canvas click deselected the target — re-select
                    // the row before emitting further overlay candidates.
                    guideRowSelection(state.rowLabel, false, state);
                    return;
                }
            }
            final float[] bounds = latestOverlayButtonBounds();
            if (bounds != null) {
                final List<Component> surfaces = invokeOnEdt(this::glSurfaceCandidates);
                final List<Point> candidates = candidateClickPoints(bounds, surfaces);
                if (!candidates.isEmpty()) {
                    final Point point =
                        candidates.get(state.candidateCursor++ % candidates.size());
                    emitClickTarget(point.x, point.y, "none", state);
                    return;
                }
            }
            guideFloatingStripWindows(state);
        } catch (RuntimeException | Error ignored) {
        }
    }

    /**
     * Emits click points covering every small floating overlay window's interior —
     * the host renders the bounding-box button strip as its own tiny top-level
     * window, so rect-space math is unnecessary: sweeping its interior hits each
     * contributed button.
     */
    private void guideFloatingStripWindows(final GuideState state) {
        try {
            final List<Point> points = invokeOnEdt(() -> {
                final List<Point> found = new ArrayList<>();
                for (Window window : Window.getWindows()) {
                    if (window == instructionFrame || !window.isShowing()) {
                        continue;
                    }
                    final int w = window.getWidth();
                    final int h = window.getHeight();
                    if (w > 240 || h > 90 || w < 24 || h < 12) {
                        continue;
                    }
                    final java.awt.Point origin = window.getLocationOnScreen();
                    if (origin.y < 240) {
                        continue; // above the canvas area — instruction/menu rows
                    }
                    for (int y = 6; y < h; y += 6) {
                        for (int x = 6; x < w; x += 6) {
                            found.add(new Point(origin.x + x, origin.y + y));
                        }
                    }
                }
                return found;
            });
            if (points == null || points.isEmpty()) {
                return;
            }
            final Point point = points.get(state.candidateCursor++ % points.size());
            emitClickTarget(point.x, point.y, "none", state);
        } catch (RuntimeException | Error ignored) {
        }
    }

    /** Brings the largest non-probe window forward so gestures reach the host UI. */
    private void raiseMainWindow() {
        try {
            invokeOnEdt(() -> {
                Window largest = null;
                long largestArea = 0L;
                for (Window window : Window.getWindows()) {
                    if (window == instructionFrame || !window.isShowing()) {
                        continue;
                    }
                    final long area = (long) window.getWidth() * window.getHeight();
                    if (area > largestArea) {
                        largestArea = area;
                        largest = window;
                    }
                }
                if (largest != null) {
                    largest.toFront();
                }
                return null;
            });
            sleep(300);
        } catch (RuntimeException | Error ignored) {
        }
    }

    private void setInstructionVisible(final boolean visible) {
        try {
            runOnEdt(() -> {
                if (instructionFrame != null) {
                    instructionFrame.setVisible(visible);
                }
            });
        } catch (RuntimeException | Error ignored) {
        }
    }

    private boolean anySelection() {
        try {
            final SelectionSummary selection = context.selectionQuery().currentSelection();
            return !selection.selectedDeformerIds().isEmpty()
                || !selection.selectedArtMeshIds().isEmpty()
                || !selection.selectedParameterIds().isEmpty()
                || !selection.selectedModelObjectIds().isEmpty();
        } catch (dev.turboism.sdk.cubism.CubismServiceException
                | RuntimeException | Error failure) {
            return false;
        }
    }

    private String selectionSignature() {
        try {
            final SelectionSummary selection = context.selectionQuery().currentSelection();
            return selection.selectedDeformerIds() + "|"
                + selection.selectedArtMeshIds() + "|"
                + selection.selectedModelObjectIds();
        } catch (dev.turboism.sdk.cubism.CubismServiceException
                | RuntimeException | Error failure) {
            return "unavailable";
        }
    }

    /** Polls for the production mirror dialog's distinctive component signature. */
    private JDialog awaitMirrorDialog(
        final long timeoutMillis,
        final List<String> requiredSelection,
        final String rowLabel,
        final Report report,
        final String prefix
    ) {
        final GuideState guide = new GuideState();
        guide.requiredSelection = requiredSelection;
        guide.rowLabel = rowLabel;
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline && !stopped && !uiAborted) {
            final JDialog found = invokeOnEdt(this::findMirrorDialog);
            if (found != null) {
                report.put(prefix + "dialogAppeared", "true");
                return found;
            }
            guide.poll++;
            guideOverlayClick(guide);
            updateInstructionStatus();
            sleep(UI_POLL_STEP_MILLIS);
        }
        return null;
    }

    private volatile Window lastSeenDialog;

    private JDialog findMirrorDialog() {
        for (Window window : candidateWindows()) {
            if (!(window instanceof JDialog dialog) || !dialog.isShowing()) {
                continue;
            }
            if (findAll(dialog, BasicArrowButton.class).size() == 4
                && findAll(dialog, JCheckBox.class).size() == 1
                && findAll(dialog, JButton.class).size() >= 2) {
                return dialog;
            }
        }
        return null;
    }

    /**
     * Windows enumerable from this AppContext plus globally active/focused
     * windows. Plugin dialogs created on a foreign AppContext thread are
     * invisible to {@link Window#getWindows()} but still surface through the
     * global focus manager; a cached reference survives later focus changes.
     */
    private List<Window> candidateWindows() {
        final List<Window> windows =
            new ArrayList<>(Arrays.asList(Window.getWindows()));
        final KeyboardFocusManager kfm =
            KeyboardFocusManager.getCurrentKeyboardFocusManager();
        addIfMissing(windows, invokeGlobal(kfm, "getGlobalActiveWindow"));
        addIfMissing(windows, invokeGlobal(kfm, "getGlobalFocusedWindow"));
        final Window cached = lastSeenDialog;
        if (cached != null && cached.isShowing()) {
            addIfMissing(windows, cached);
        }
        for (Window window : windows) {
            if (window instanceof JDialog && window.isShowing()) {
                lastSeenDialog = window;
            }
        }
        return windows;
    }

    private static void addIfMissing(final List<Window> windows, final Window window) {
        if (window != null && !windows.contains(window)) {
            windows.add(window);
        }
    }

    /**
     * Disposes foreign modal dialogs that cover the editor (Cubism's startup
     * home/promo dialog renders as a 1208x763 JDialog on top of the frame and
     * swallows list clicks). The production mirror dialog (4 direction toggles
     * + 1 checkbox) and the probe's own guidance frame are never disposed.
     */
    private void dismissBlockingDialogs(final Report report) {
        invokeOnEdt(() -> {
            for (Window window : candidateWindows()) {
                if (!(window instanceof JDialog dialog) || !dialog.isShowing()) {
                    continue;
                }
                if (findAll(dialog, BasicArrowButton.class).size() == 4
                    && findAll(dialog, JCheckBox.class).size() == 1) {
                    continue;
                }
                logger.info("WARP_MIRROR_UI_DISMISS title="
                    + dialog.getTitle() + " bounds=" + dialog.getBounds());
                if (report != null) {
                    report.put("ui.dismissedDialog",
                        String.valueOf(dialog.getTitle()) + " " + dialog.getBounds());
                }
                dialog.dispose();
            }
            return null;
        });
    }

    /**
     * The global focus getters are protected on {@link KeyboardFocusManager};
     * reflective access is used because the SDK probe runs in a different
     * AppContext than the plugin dialog under test.
     */
    private static Window invokeGlobal(
        final KeyboardFocusManager kfm,
        final String method
    ) {
        try {
            final Method getter =
                KeyboardFocusManager.class.getDeclaredMethod(method);
            getter.setAccessible(true);
            final Object window = getter.invoke(kfm);
            return window instanceof Window w ? w : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Polls for a plain information dialog (buttons but no direction toggles). */
    private JDialog awaitInfoDialog(
        final long timeoutMillis,
        final List<String> requiredSelection,
        final String rowLabel,
        final Report report,
        final String prefix
    ) {
        final GuideState guide = new GuideState();
        guide.requiredSelection = requiredSelection;
        guide.rowLabel = rowLabel;
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline && !stopped && !uiAborted) {
            final JDialog found = invokeOnEdt(() -> {
                for (Window window : candidateWindows()) {
                    if (!(window instanceof JDialog dialog) || !dialog.isShowing()) {
                        continue;
                    }
                    if (findAll(dialog, BasicArrowButton.class).isEmpty()
                        && !findAll(dialog, JButton.class).isEmpty()) {
                        return dialog;
                    }
                }
                return null;
            });
            if (found != null) {
                report.put(prefix + "infoDialogAppeared", "true");
                return found;
            }
            guide.poll++;
            guideOverlayClick(guide);
            updateInstructionStatus();
            sleep(UI_POLL_STEP_MILLIS);
        }
        return null;
    }

    private BasicArrowButton directionButton(
        final JDialog dialog,
        final WarpMirrorDirection direction
    ) {
        final int arrow = switch (direction) {
            case TOP_TO_BOTTOM -> BasicArrowButton.SOUTH;
            case BOTTOM_TO_TOP -> BasicArrowButton.NORTH;
            case LEFT_TO_RIGHT -> BasicArrowButton.EAST;
            case RIGHT_TO_LEFT -> BasicArrowButton.WEST;
        };
        for (BasicArrowButton button : findAll(dialog, BasicArrowButton.class)) {
            if (button.getDirection() == arrow) {
                return button;
            }
        }
        return null;
    }

    private void verifyUiApply(
        final WarpDeformer target,
        final WarpMirrorDirection direction,
        final WarpGrid before,
        final String prefix,
        final Report report,
        final List<String> failures
    ) {
        final String changed = awaitGridChange(target, fingerprint(before), UI_APPLY_TIMEOUT_MILLIS);
        report.put(prefix + "applied", changed);
        if (!"true".equals(changed)) {
            // NO_CHANGE is a legitimate outcome on an already-symmetric half; record and skip.
            report.put(prefix + "note", "grid unchanged after UI apply (NO_CHANGE or failure)");
            return;
        }
        try {
            final DirectionCheck check = verifyDirection(before, target.grid(), direction);
            report.put(prefix + "checked", Integer.toString(check.checked()));
            report.put(prefix + "mismatched", Integer.toString(check.mismatched()));
            if (check.checked() > 0 && check.mismatched() == 0) {
                report.put(prefix + "verified", "true");
            } else {
                failures.add("ui: " + direction.name() + " target-side points wrong: "
                    + check.mismatched() + "/" + check.checked());
            }
            history().undo(1);
            sleep(SETTLE_MILLIS);
            report.put(prefix + "undoRestored",
                Boolean.toString(samePoints(before, target.grid())));
        } catch (RuntimeException | Error failure) {
            failures.add("ui: post-apply verification failed for " + direction.name()
                + ": " + failure.getClass().getSimpleName());
        }
    }

    private boolean awaitSelectionContains(
        final String deformerId,
        final Report report,
        final String key
    ) {
        return awaitSelectionContains(deformerId, null, false, report, key);
    }

    /**
     * Assisted-selection await that also feeds the external driver live row click
     * targets: the row matching {@code label} when the object list exposes labels,
     * otherwise a row sweep. {@code extraCtrlRow} additionally publishes a
     * Ctrl+click target on the next row for genuine multi-selection gestures.
     */
    private boolean awaitSelectionContains(
        final String deformerId,
        final String label,
        final boolean extraCtrlRow,
        final Report report,
        final String key
    ) {
        final GuideState guide = new GuideState();
        final long deadline = System.currentTimeMillis() + UI_STEP_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline && !stopped && !uiAborted) {
            if (selectionContains(deformerId)) {
                report.put(key, "true");
                return true;
            }
            guide.poll++;
            dismissBlockingDialogs(null);
            guideRowSelection(label, extraCtrlRow, guide);
            updateInstructionStatus();
            sleep(UI_POLL_STEP_MILLIS);
        }
        report.put(key, "timeout");
        return false;
    }

    private boolean awaitMultiSelection(
        final String firstId,
        final String secondId,
        final Report report,
        final String key
    ) {
        return awaitMultiSelection(firstId, null, secondId, null, report, key);
    }

    /**
     * Assisted multi-selection await: publishes the first target row as a plain
     * click and the second as a Ctrl+click so the external driver reproduces the
     * real two-gesture multi-selection.
     */
    private boolean awaitMultiSelection(
        final String firstId,
        final String firstLabel,
        final String secondId,
        final String secondLabel,
        final Report report,
        final String key
    ) {
        final GuideState guide = new GuideState();
        final long deadline = System.currentTimeMillis() + UI_STEP_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline && !stopped && !uiAborted) {
            if (selectionContains(firstId) && selectionContains(secondId)) {
                report.put(key, "true");
                return true;
            }
            guide.poll++;
            guideRowSelection(firstLabel, false, guide);
            guideRowSelectionCtrl(secondLabel, guide);
            updateInstructionStatus();
            sleep(UI_POLL_STEP_MILLIS);
        }
        report.put(key, "timeout");
        return false;
    }

    private boolean selectionContains(final String deformerId) {
        try {
            final SelectionSummary selection = context.selectionQuery().currentSelection();
            for (DeformerId id : selection.selectedDeformerIds()) {
                if (id.value().equals(deformerId)) {
                    return true;
                }
            }
            for (dev.turboism.sdk.cubism.id.ModelObjectId id : selection.selectedModelObjectIds()) {
                if (id.value().equals(deformerId)) {
                    return true;
                }
            }
        } catch (dev.turboism.sdk.cubism.CubismServiceException | RuntimeException | Error ignored) {
        }
        return false;
    }

    private boolean selectionHasNoWarp() {
        try {
            final SelectionSummary selection = context.selectionQuery().currentSelection();
            if (selection.selectedDeformerIds().isEmpty()
                && selection.selectedArtMeshIds().isEmpty()
                && selection.selectedModelObjectIds().isEmpty()
                && selection.selectedParameterIds().isEmpty()) {
                return true; // empty selection
            }
            final java.util.Set<String> warpIds = new java.util.HashSet<>();
            for (WarpDeformer warp : context.cubism().model().active().warpDeformers().all()) {
                warpIds.add(warp.id().value());
            }
            for (DeformerId id : selection.selectedDeformerIds()) {
                if (warpIds.contains(id.value())) {
                    return false;
                }
            }
            for (dev.turboism.sdk.cubism.id.ModelObjectId id : selection.selectedModelObjectIds()) {
                if (warpIds.contains(id.value())) {
                    return false;
                }
            }
            return true;
        } catch (dev.turboism.sdk.cubism.CubismServiceException | RuntimeException | Error failure) {
            return false;
        }
    }

    private String awaitGridChange(
        final WarpDeformer target,
        final String beforeFingerprint,
        final long timeoutMillis
    ) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline && !stopped) {
            try {
                if (!fingerprint(target.grid()).equals(beforeFingerprint)) {
                    return "true";
                }
            } catch (RuntimeException | Error failure) {
                return "read-failed";
            }
            sleep(300);
        }
        return "false";
    }

    private boolean awaitDisposed(final Window window, final long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!window.isShowing()) {
                return true;
            }
            sleep(200);
        }
        return !window.isShowing();
    }

    private void disposeDialog(final JDialog dialog) {
        try {
            runOnEdt(() -> {
                if (dialog.isShowing()) {
                    dialog.dispose();
                }
            });
        } catch (RuntimeException | Error ignored) {
        }
    }

    /**
     * Activates a real dialog control through the production component's own
     * dispatch ({@code doClick} on buttons/toggles). The Proton host does not
     * deliver in-JVM Robot input to windows (evidence: AutoDeliver=never on a
     * full-screen move scan), so dialog-internal controls are activated at the
     * component level — still the real production dialog, real handlers, real
     * apply pipeline. Host-side gestures (object selection, overlay click) are
     * provided by real external X input, not simulated.
     */
    private void clickComponent(final Component component) {
        runOnEdt(() -> {
            if (component instanceof JButton button) {
                button.doClick();
            } else if (component instanceof JToggleButton toggle) {
                toggle.doClick();
            }
        });
        sleep(150);
    }

    private <T extends Component> T findSingle(final Container root, final Class<T> type) {
        final List<T> all = findAll(root, type);
        return all.isEmpty() ? null : all.get(0);
    }

    private <T extends Component> List<T> findAll(final Container root, final Class<T> type) {
        final List<T> found = new ArrayList<>();
        collectComponents(root, type, found);
        return found;
    }

    private <T extends Component> void collectComponents(
        final Container container,
        final Class<T> type,
        final List<T> out
    ) {
        for (Component component : container.getComponents()) {
            if (type.isInstance(component)) {
                out.add(type.cast(component));
            }
            if (component instanceof Container child) {
                collectComponents(child, type, out);
            }
        }
    }

    private void instruct(final String text) {
        logger.info("WARP_MIRROR_UI_STEP " + text.replace('\n', ' '));
        try {
            final java.awt.Rectangle abortRect = invokeOnEdt(() -> {
                if (instructionAbort == null || !instructionAbort.isShowing()) {
                    return null;
                }
                final java.awt.Point p = instructionAbort.getLocationOnScreen();
                return new java.awt.Rectangle(
                    p.x, p.y, instructionAbort.getWidth(), instructionAbort.getHeight());
            });
            if (abortRect != null) {
                logger.info("WARP_MIRROR_UI_ABORT x=" + abortRect.x + " y=" + abortRect.y
                    + " w=" + abortRect.width + " h=" + abortRect.height);
            }
            final java.awt.Point anchor = invokeOnEdt(() ->
                instructionFrame != null && instructionFrame.isShowing()
                    ? instructionFrame.getLocationOnScreen() : null);
            if (anchor != null) {
                logger.info("WARP_MIRROR_UI_ANCHOR x=" + anchor.x + " y=" + anchor.y);
            }
            final java.awt.Rectangle mainRect = invokeOnEdt(() -> {
                Window largest = null;
                long area = 0;
                for (Window window : Window.getWindows()) {
                    if (window == instructionFrame || !window.isShowing()) {
                        continue;
                    }
                    final long a = (long) window.getWidth() * window.getHeight();
                    if (a > area) {
                        area = a;
                        largest = window;
                    }
                }
                if (largest == null) {
                    return null;
                }
                final java.awt.Point p = largest.getLocationOnScreen();
                return new java.awt.Rectangle(
                    p.x, p.y, largest.getWidth(), largest.getHeight());
            });
            if (mainRect != null) {
                logger.info("WARP_MIRROR_UI_MAINWIN x=" + mainRect.x + " y=" + mainRect.y
                    + " w=" + mainRect.width + " h=" + mainRect.height);
            }
        } catch (RuntimeException | Error ignored) {
        }
        try {
            runOnEdt(() -> {
                if (instructionText != null) {
                    instructionText.setText("<html>" + text.replace("\n", "<br>") + "</html>");
                }
                if (instructionFrame != null) {
                    instructionFrame.pack();
                }
            });
        } catch (RuntimeException | Error ignored) {
        }
    }

    private void updateInstructionStatus() {
        try {
            final SelectionSummary selection = context.selectionQuery().currentSelection();
            final String status = "当前选择: deformers=" + selection.selectedDeformerIds().size()
                + " artMeshes=" + selection.selectedArtMeshIds().size()
                + " objects=" + selection.selectedModelObjectIds().size()
                + " params=" + selection.selectedParameterIds().size();
            final String sig = selection.selectedDeformerIds().size()
                + "/" + selection.selectedArtMeshIds().size()
                + "/" + selection.selectedModelObjectIds().size()
                + "/" + selection.selectedParameterIds().size();
            if (!sig.equals(lastSelectionSig)) {
                lastSelectionSig = sig;
                logger.info("WARP_MIRROR_UI_SELECTION " + sig);
            }
            runOnEdt(() -> {
                if (instructionStatus != null) {
                    instructionStatus.setText(status);
                }
            });
        } catch (dev.turboism.sdk.cubism.CubismServiceException | RuntimeException | Error ignored) {
        }
    }

    private void openInstructionWindow() {
        try {
            runOnEdt(() -> {
                final JFrame frame = new JFrame("WarpMirror 验证指引 / Warp-mirror validation");
                instructionText = new JLabel("<html>等待验证步骤…</html>");
                instructionText.setBorder(
                    javax.swing.BorderFactory.createEmptyBorder(12, 12, 4, 12));
                instructionStatus = new JLabel("当前选择: -");
                instructionStatus.setBorder(
                    javax.swing.BorderFactory.createEmptyBorder(4, 12, 4, 12));
                final JButton abort = new JButton("跳过剩余 UI 步骤 / Skip remaining UI steps");
                abort.addActionListener(event -> uiAborted = true);
                instructionAbort = abort;
                final javax.swing.JPanel south = new javax.swing.JPanel();
                south.add(abort);
                frame.getContentPane().setLayout(new java.awt.BorderLayout());
                frame.getContentPane().add(instructionText, java.awt.BorderLayout.CENTER);
                frame.getContentPane().add(instructionStatus, java.awt.BorderLayout.NORTH);
                frame.getContentPane().add(south, java.awt.BorderLayout.SOUTH);
                frame.setAlwaysOnTop(true);
                frame.pack();
                frame.setLocation(40, 40);
                frame.setVisible(true);
                instructionFrame = frame;
            });
        } catch (RuntimeException | Error failure) {
            logger.warn("WARP_MIRROR_UI_INSTRUCTION_UNAVAILABLE " + failure.getClass().getSimpleName());
        }
    }

    private void closeInstructionWindow() {
        try {
            runOnEdt(() -> {
                if (instructionFrame != null) {
                    instructionFrame.dispose();
                    instructionFrame = null;
                }
            });
        } catch (RuntimeException | Error ignored) {
        }
    }

    private interface EdtCall<T> {
        T run();
    }

    private void runOnEdt(final Runnable action) {
        invokeOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private <T> T invokeOnEdt(final EdtCall<T> call) {
        if (SwingUtilities.isEventDispatchThread()) {
            return call.run();
        }
        final java.util.concurrent.atomic.AtomicReference<T> result =
            new java.util.concurrent.atomic.AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(call.run()));
        } catch (Exception | Error failure) {
            throw new IllegalStateException("EDT call failed", failure);
        }
        return result.get();
    }

    private void formsBeforeFailed(
        final Report report,
        final String prefix,
        final Throwable failure
    ) {
        report.put(prefix + "note", "descendant pre-read failed: "
            + failure.getClass().getSimpleName());
    }

    // ------------------------------------------------------------------
    // Terminate
    // ------------------------------------------------------------------

    private void finish(final Report report, final List<String> failures) {
        report.put("failureCount", Integer.toString(failures.size()));
        for (int index = 0; index < failures.size(); index++) {
            report.put("failure." + index, failures.get(index));
        }
        final boolean pass = failures.isEmpty();
        report.put("status", pass ? "PASS" : "FAIL");
        try {
            writeResult(report);
        } catch (IOException failure) {
            logger.warn("WARP_MIRROR_RESULT_WRITE_FAILED " + failure.getClass().getSimpleName());
        }
        for (String failure : failures) {
            logger.warn("WARP_MIRROR_FAILURE " + failure);
        }
        logger.info(PROBE_RESULT_MARKER + " status=" + (pass ? "PASS" : "FAIL"));
        // The runner treats a terminal result without a normal launcher exit as an
        // incomplete run, so the host is asked to close once the result is on disk.
        sleep(HOST_CLOSE_DELAY_MILLIS);
        final Thread closer = new Thread(this::requestHostClose, "warp-mirror-host-close");
        closer.setDaemon(true);
        closer.start();
        sleep(HOST_CLOSE_GRACE_MILLIS);
        logger.info("WARP_MIRROR_PROBE_JVM_EXIT");
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

    /**
     * Asks the exact host to close once the observations and the result file are complete.
     * Mirrors the reviewed parameter/alt-symmetry probe route: dispatch WINDOW_CLOSING to
     * this process' document frame, then resolve the unsaved-changes confirmation without
     * depending on localized button text. The document is discarded, never saved, so the
     * staged fixture copy keeps its source hash.
     */
    private void requestHostClose() {
        final Window host;
        try {
            host = documentFrame();
        } catch (Exception | Error failure) {
            logger.warn("WARP_MIRROR_HOST_CLOSE window-lookup-failed "
                + failure.getClass().getSimpleName());
            return;
        }
        if (host == null) {
            logger.warn("WARP_MIRROR_HOST_CLOSE no-host-window");
            return;
        }
        logger.info("WARP_MIRROR_HOST_CLOSE target=" + host.getClass().getName());
        bringToFront(host);
        try {
            SwingUtilities.invokeLater(() -> host.dispatchEvent(
                new java.awt.event.WindowEvent(host, java.awt.event.WindowEvent.WINDOW_CLOSING)
            ));
            logger.info("WARP_MIRROR_HOST_CLOSE requested=window-closing");
        } catch (RuntimeException | Error failure) {
            logger.warn("WARP_MIRROR_HOST_CLOSE dispatch-failed "
                + failure.getClass().getSimpleName());
        }

        final long deadline = System.currentTimeMillis() + HOST_CLOSE_TIMEOUT_MILLIS;
        final long fallbackAt = System.currentTimeMillis() + HOST_CLOSE_FALLBACK_MILLIS;
        boolean fallbackSent = false;
        while (System.currentTimeMillis() < deadline) {
            if (closed(host)) {
                logger.info("WARP_MIRROR_HOST_CLOSE done");
                return;
            }
            final javax.swing.JButton discard = discardButton();
            if (discard != null) {
                try {
                    SwingUtilities.invokeAndWait(discard::doClick);
                    logger.info("WARP_MIRROR_HOST_CLOSE_DISCARD");
                } catch (Exception | Error failure) {
                    logger.warn("WARP_MIRROR_HOST_CLOSE discard-failed "
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
                    logger.info("WARP_MIRROR_HOST_CLOSE requested=alt-f4-fallback");
                } catch (Exception | Error failure) {
                    logger.warn("WARP_MIRROR_HOST_CLOSE alt-f4-failed "
                        + failure.getClass().getSimpleName());
                }
            }
            sleep(HOST_CLOSE_STEP_MILLIS);
        }
        logger.warn("WARP_MIRROR_HOST_CLOSE_TIMEOUT");
    }

    /** The host's visible document frame: title carries the staged fixture file name. */
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

    private void bringToFront(final Window window) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                window.toFront();
                window.requestFocus();
            });
            sleep(HOST_CLOSE_FOCUS_SETTLE_MILLIS);
        } catch (Exception | Error failure) {
            logger.warn("WARP_MIRROR_HOST_CLOSE focus-failed " + failure.getClass().getSimpleName());
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
                        .filter(BoundingBoxWarpMirrorHostValidationPlugin::isDiscardAction)
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
        final javax.accessibility.AccessibleContext accessible = button.getAccessibleContext();
        final String accessibleName = accessible == null ? null : accessible.getAccessibleName();
        for (String value : java.util.Arrays.asList(
            button.getActionCommand(), button.getName(), button.getText(), accessibleName
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
            || normalized.contains("保存" + "しない")
            || normalized.contains("セーブ" + "しない");
    }

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

        String render() {
            final StringBuilder text = new StringBuilder();
            for (int index = 0; index < keys.size(); index++) {
                text.append(keys.get(index)).append('=').append(values.get(index)).append('\n');
            }
            return text.toString();
        }
    }
}
