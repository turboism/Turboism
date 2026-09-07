package dev.turboism.validation.boundingbox;

import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;

import javax.imageio.ImageIO;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Task-local exerciser for native red-box overlay buttons on an exact Cubism
 * host. The probe registers exactly two public SDK
 * {@link BoundingBoxOverlayButton} contributions with distinct IDs, stable
 * different orders and harmless evidence-recording callbacks, waits for a
 * runner-created task-local trigger, then verifies on the real screen that both
 * distinctive icons are installed (exactly one unambiguous candidate each,
 * bounded size/proximity/alignment), that they stay stably bounded across
 * frames, that real {@link Robot} mouse press/release gestures at the detected
 * centers reach both independent SDK callbacks, and that both icon candidates
 * disappear after the two SDK registrations close while the surrounding
 * pre-click screen context stays stable (occlusion cannot satisfy detach).
 *
 * <p>A test-only diagnostic additionally observes the loader-neutral setup
 * bridge property and identity-safely wraps its JDK {@code BiFunction} to
 * count invocations and delegated-result classes; this evidence is recorded
 * but never decides PASS/FAIL and never alters the detection timeout or
 * result status logic.</p>
 *
 * <p>Everything runs through the public SDK and JDK APIs only; the probe never
 * imports or reflects {@code com.live2d.*} types, never reads or mutates the
 * model, never saves, and never creates Undo. All result files are task-local
 * and atomically published, with structured READY/PASS/FAIL log markers; every
 * screen-capture failure, timeout, ambiguity, occlusion/context change, or
 * other error records a failure, publishes {@code status=FAIL} with a reason,
 * and never claims readiness.</p>
 *
 * <p>Before any bridge observation, {@link Robot} creation, AWT window
 * enumeration/focus or UI input, the probe polls the public SDK
 * {@link CubismRuntimeSnapshot} until {@code model()} is present on the probe
 * worker (bounded 60 s monotonic deadline, 500 ms polling step; one present
 * snapshot suffices and the model is never mutated). Timeout, stop, interrupt
 * and throwing snapshots fail closed with a structured
 * {@code ready|timeout|stopped|interrupted|snapshot-failed} outcome recorded
 * in log and result properties, and those paths never create/use a
 * {@link Robot}, inspect/focus a window, or send input.</p>
 *
 * <p>Because no public SDK selection-write operation exists, the probe first
 * establishes the native selection precondition in-process: after the bridge
 * telemetry wrapper is installed, the bridge evidence counters are observed;
 * if selection is not already active, the task JVM's own unique visible Cubism
 * main window is located on the EDT and brought to front/focus, then a real
 * {@link Robot} double click is delivered at the established window-relative
 * object-tree reference point scaled by the focused task-owned window's Swing
 * content-pane screen bounds (content pane {@code getLocationOnScreen()} and
 * {@code getSize()} read on the EDT; decorated outer bounds and reported
 * {@link Window} insets remain diagnostic-only), so the frozen {@code (110,720)}
 * reference maps into authoritative content coordinates, never across
 * decorated outer bounds. Bounded bridge activation evidence (invocation
 * count &gt; 0
 * returning a non-empty array) is required before any visual detection;
 * absence, ambiguity, focus failure, or missing evidence fails closed to
 * {@code status=FAIL}.</p>
 */
public final class BoundingBoxOverlayHostValidationPlugin implements TurboismPlugin {

    private static final String FLAG = "exerciser.flag";
    private static final String RESULT_FILE_NAME = "bounding-box-overlay-host-validation-result.properties";
    private static final String MANUAL_MODE_PROPERTY = "turboism.validation.boundingBoxManual";
    private static final String MANUAL_CLOSE_FLAG = "manual-close.flag";
    private static final String MANUAL_ABSENCE_FLAG = "manual-absence-confirmed.flag";

    private static final long FLAG_TIMEOUT_MILLIS = 240_000L;
    private static final long DETECTION_TIMEOUT_MILLIS = 240_000L;
    private static final long DETECTION_STEP_MILLIS = 500L;
    private static final long STABILITY_GAP_MILLIS = 800L;
    private static final long STABILITY_MAX_CENTER_DRIFT = 2;
    private static final long CLICK_EVIDENCE_TIMEOUT_MILLIS = 10_000L;
    private static final long ABSENCE_TIMEOUT_MILLIS = 90_000L;
    private static final long PASS_SETTLE_MILLIS = 3_000L;
    private static final long MANUAL_STEP_MILLIS = 250L;
    private static final long MANUAL_TIMEOUT_MILLIS = 1_800_000L;
    private static final int ABSENCE_CONFIRM_CAPTURES = 2;

    private static final String BUTTON_A_ID = "boundingbox.validation.button.a";
    private static final String BUTTON_B_ID = "boundingbox.validation.button.b";
    private static final String ICON_A = "icons/icon-a.png";
    private static final String ICON_B = "icons/icon-b.png";

    // In-process selection precondition (bounded, fail-closed).
    private static final long SELECTION_WINDOW_TIMEOUT_MILLIS = 20_000L;
    private static final long SELECTION_WINDOW_STEP_MILLIS = 500L;
    private static final long SELECTION_FOCUS_TIMEOUT_MILLIS = 5_000L;
    private static final long SELECTION_FOCUS_STEP_MILLIS = 250L;
    private static final long SELECTION_EVIDENCE_TIMEOUT_MILLIS = 30_000L;
    private static final long SELECTION_EVIDENCE_STEP_MILLIS = 100L;
    private static final long EDT_CALL_TIMEOUT_MILLIS = 10_000L;
    /** Active-model readiness gate: bounded 60 s monotonic, one present snapshot suffices. */
    static final long MODEL_READY_TIMEOUT_MILLIS = 60_000L;
    /** Short bounded polling step for the active-model readiness gate. */
    static final long MODEL_READY_STEP_MILLIS = 500L;
    /** Main-window plausibility: any showing non-dialog window at least this large per side. */
    static final int MIN_MAIN_WINDOW_SIDE = 480;
    /** Established 5.2.03 reference content extent used as the click reference. */
    static final int REF_WINDOW_WIDTH = 942;
    static final int REF_WINDOW_HEIGHT = 1012;
    /** Established fixture-specific object-tree double-click reference point (content-relative). */
    static final int REF_SELECTION_X = 110;
    static final int REF_SELECTION_Y = 720;

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;

    private final AtomicBoolean clickedA = new AtomicBoolean();
    private final AtomicBoolean clickedB = new AtomicBoolean();
    private final AtomicReference<Registration> registrationA = new AtomicReference<>();
    private final AtomicReference<Registration> registrationB = new AtomicReference<>();
    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile String centerAX = "none";
    private volatile String centerAY = "none";
    private volatile String centerBX = "none";
    private volatile String centerBY = "none";
    private volatile int stabilityFrames = 0;
    private volatile BoundingBoxIconDetector.ContextRegion absenceContext;
    private volatile boolean manualClicksObserved;
    private volatile boolean manualCloseRequested;
    private volatile boolean manualAbsenceConfirmed;

    private volatile String selectionGesture = "not-run";
    private volatile String selectionTarget = "none";
    private volatile String selectionPoint = "none";
    private volatile String selectionOuterBounds = "none";
    private volatile String selectionInsets = "none";
    private volatile String selectionClientBounds = "none";
    private volatile String selectionGeometrySource = "none";
    private volatile long selectionBridgeInvocations = 0;
    private volatile long selectionBridgeNonEmptyArrays = 0;
    private volatile String readinessOutcome = "not-run";
    private volatile int readinessAttempts = 0;
    private volatile long readinessDurationMillis = 0;
    private volatile String readinessFailureType = "none";

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenFlagged, "bounding-box-overlay-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
        logger.info("BOUNDING_BOX_PROBE_READY stateDir=" + stateDir);
    }

    @Override
    public void enable() {
        logger.info("BOUNDING_BOX_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        stopped.set(true);
        closeRegistrations();
        logger.info("BOUNDING_BOX_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        stopped.set(true);
        closeRegistrations();
        logger.info("BOUNDING_BOX_PROBE_SHUTDOWN");
    }

    private void runWhenFlagged() {
        final Path flag = stateDir.resolve(FLAG);
        final long deadline = System.currentTimeMillis() + FLAG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(flag)) {
                logger.info("BOUNDING_BOX_PROBE_TRIGGER_RECEIVED flag=" + flag);
                runMatrix();
                return;
            }
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warn("BOUNDING_BOX_PROBE_FLAG_TIMEOUT flag=" + flag);
        Runtime.getRuntime().halt(2);
    }

    // ------------------------------------------------------------------
    // Matrix
    // ------------------------------------------------------------------

    private void runMatrix() {
        final long startedNanos = System.nanoTime();
        final List<String> failures = new ArrayList<>();
        final BridgeTelemetry telemetry = new BridgeTelemetry();
        try {
            if (stopped.get()) {
                failures.add("probe stopped before the click matrix");
            } else {
                runClickMatrix(failures, telemetry);
            }
        } catch (Exception failure) {
            failures.add("matrix failed safely: " + singleLine(failure));
        } finally {
            telemetry.restore();
        }
        final boolean pass = failures.isEmpty();
        writeResultFile(startedNanos, pass, failures, telemetry);
        logger.info("BOUNDING_BOX_PROBE_RESULT status=" + (pass ? "PASS" : "FAIL")
            + " buttonA=" + BUTTON_A_ID
            + " buttonB=" + BUTTON_B_ID
            + " centerA=(" + centerAX + "," + centerAY + ")"
            + " centerB=(" + centerBX + "," + centerBY + ")"
            + " stabilityFrames=" + stabilityFrames
            + " clickedA=" + clickedA.get()
            + " clickedB=" + clickedB.get()
            + " selectionGesture=" + selectionGesture
            + " selectionPoint=" + selectionPoint
            + " failures=" + failures.size()
            + " durationMillis=" + ((System.nanoTime() - startedNanos) / 1_000_000L));
        logger.info("BOUNDING_BOX_BRIDGE_TELEMETRY"
            + " bridgeObserved=" + telemetry.bridgeObserved()
            + " wrapperInstalled=" + telemetry.wrapperInstalled()
            + " callbackInvocations=" + telemetry.invocations()
            + " callbackNullResults=" + telemetry.nullResults()
            + " callbackNonArrayResults=" + telemetry.nonArrayResults()
            + " callbackEmptyArrays=" + telemetry.emptyArrays()
            + " callbackNonEmptyArrays=" + telemetry.nonEmptyArrays()
            + " callbackMaxArrayLength=" + telemetry.maxArrayLength()
            + " callbackThrowables=" + telemetry.throwables()
            + " wrapperRestored=" + telemetry.wrapperRestored());
        for (String failure : failures) {
            logger.warn("BOUNDING_BOX_PROBE_FAILURE " + failure);
        }
        try {
            Thread.sleep(PASS_SETTLE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(pass ? 0 : 2);
    }

    private void runClickMatrix(final List<String> failures, final BridgeTelemetry telemetry) {
        registerButtons(failures);
        if (!failures.isEmpty()) {
            return;
        }
        emitSelectionTelemetry();
        if (!awaitModelReady(failures)) {
            return;
        }
        telemetry.observeAndWrap(stopped, logger);
        if (Boolean.getBoolean(MANUAL_MODE_PROPERTY)) {
            runManualMatrix(failures, telemetry);
            return;
        }
        final Robot robot;
        final Rectangle screen;
        try {
            final GraphicsDevice device = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice();
            robot = new Robot(device);
            screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        } catch (Exception | Error failure) {
            failures.add("robot unavailable: " + failure.getClass().getName());
            return;
        }
        final BufferedImage beforeSelection = capture(robot, screen, failures);
        if (beforeSelection == null) {
            return;
        }
        writeDiagnosticCapture("before-selection.png", beforeSelection);
        ensureSelectionActive(robot, failures, telemetry);
        final BufferedImage afterSelection = capture(robot, screen, failures);
        if (afterSelection != null) {
            writeDiagnosticCapture("after-selection.png", afterSelection);
        }
        if (!failures.isEmpty()) {
            return;
        }
        final BoundingBoxIconDetector.Pair pair = awaitUniquePair(robot, screen, failures);
        if (pair == null) {
            return;
        }
        if (!awaitStable(robot, screen, pair, failures)) {
            return;
        }
        click(robot, pair.a(), BUTTON_A_ID, clickedA, failures);
        click(robot, pair.b(), BUTTON_B_ID, clickedB, failures);
        if (!failures.isEmpty()) {
            return;
        }
        if (!clickedA.get() || !clickedB.get()) {
            failures.add("both independent click callbacks must be observed");
            return;
        }
        closeRegistrations(failures);
        if (!failures.isEmpty()) {
            return;
        }
        awaitAbsence(robot, screen, failures);
    }

    private void runManualMatrix(final List<String> failures, final BridgeTelemetry telemetry) {
        selectionGesture = "manual";
        logger.info("BOUNDING_BOX_MANUAL_READY"
            + " action=select-bounding-box-and-click-buttons-a-and-b"
            + " closeFlag=" + stateDir.resolve(MANUAL_CLOSE_FLAG));
        final long clickDeadline = System.currentTimeMillis() + MANUAL_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < clickDeadline && !stopped.get()) {
            if (clickedA.get() && clickedB.get() && telemetry.nonEmptyArrays() > 0) {
                manualClicksObserved = true;
                logger.info("BOUNDING_BOX_MANUAL_CLICKS_OK"
                    + " callbackInvocations=" + telemetry.invocations()
                    + " nonEmptyArrays=" + telemetry.nonEmptyArrays());
                break;
            }
            if (!sleepManualStep()) {
                failures.add("manual interaction interrupted while waiting for button clicks");
                return;
            }
        }
        if (!manualClicksObserved) {
            failures.add("manual interaction timeout: both button callbacks were not observed");
            return;
        }
        if (!awaitManualFlag(MANUAL_CLOSE_FLAG)) {
            failures.add("manual interaction timeout: close confirmation was not received");
            return;
        }
        manualCloseRequested = true;
        closeRegistrations(failures);
        if (!failures.isEmpty()) {
            return;
        }
        logger.info("BOUNDING_BOX_MANUAL_REGISTRATIONS_CLOSED"
            + " action=confirm-buttons-absent-and-native-buttons-intact"
            + " absenceFlag=" + stateDir.resolve(MANUAL_ABSENCE_FLAG));
        if (!awaitManualFlag(MANUAL_ABSENCE_FLAG)) {
            failures.add("manual interaction timeout: absence confirmation was not received");
            return;
        }
        manualAbsenceConfirmed = true;
        logger.info("BOUNDING_BOX_MANUAL_ABSENCE_CONFIRMED");
    }

    private boolean awaitManualFlag(final String fileName) {
        final Path flag = stateDir.resolve(fileName);
        final long deadline = System.currentTimeMillis() + MANUAL_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline && !stopped.get()) {
            if (Files.isRegularFile(flag)) {
                return true;
            }
            if (!sleepManualStep()) {
                return false;
            }
        }
        return false;
    }

    private static boolean sleepManualStep() {
        try {
            Thread.sleep(MANUAL_STEP_MILLIS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Diagnostic-only selection-state telemetry emitted after the two SDK
     * contributions register. Reads only the public Turboism runtime snapshot
     * and reports booleans/counts derived from it (no host classes, object
     * identities, IDs or paths); a failing SDK read emits an UNAVAILABLE marker
     * with the failure class simple name. This is evidence only: it never adds
     * a probe failure, never decides PASS/FAIL, and never alters the detection
     * timeout or result status logic.
     */
    private void emitSelectionTelemetry() {
        try {
            final CubismRuntimeSnapshot snapshot = context.cubism().runtime();
            logger.info("BOUNDING_BOX_SELECTION_TELEMETRY status=AVAILABLE"
                + " modelPresent=" + snapshot.model().isPresent()
                + " selectedCount=" + snapshot.selection().selectedObjectIds().size()
                + " activeParameterPresent=" + snapshot.selection().activeParameterId().isPresent()
                + " activeArtMeshPresent=" + snapshot.selection().activeArtMeshId().isPresent()
                + " activeDeformerPresent=" + snapshot.selection().activeDeformerId().isPresent());
        } catch (RuntimeException failure) {
            logger.info("BOUNDING_BOX_SELECTION_TELEMETRY status=UNAVAILABLE"
                + " failureType=" + failure.getClass().getSimpleName());
        }
    }

    /**
     * Validation-only active-model readiness gate: before bridge observation,
     * {@link Robot} creation, AWT window enumeration/focus or any UI input,
     * polls the public SDK snapshot until {@code model()} is present, bounded
     * to {@value #MODEL_READY_TIMEOUT_MILLIS} ms with a
     * {@value #MODEL_READY_STEP_MILLIS} ms step on a monotonic deadline. One
     * present snapshot suffices; the model is never mutated or saved. Timeout,
     * stop, interrupt and throwing snapshots fail closed with a structured
     * outcome recorded in log and result properties and never touch
     * Robot/AWT/input.
     */
    private boolean awaitModelReady(final List<String> failures) {
        final long deadlineNanos = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(MODEL_READY_TIMEOUT_MILLIS);
        final ModelReadiness readiness = pollModelReadiness(
            () -> context.cubism().runtime().model().isPresent(),
            stopped::get,
            System::nanoTime,
            deadlineNanos,
            MODEL_READY_STEP_MILLIS
        );
        readinessOutcome = readiness.outcome().name().toLowerCase(Locale.ROOT);
        readinessAttempts = readiness.attempts();
        readinessDurationMillis = readiness.durationMillis();
        readinessFailureType = readiness.failureType();
        logger.info("BOUNDING_BOX_MODEL_READY outcome=" + readinessOutcome
            + " attempts=" + readinessAttempts
            + " durationMillis=" + readinessDurationMillis
            + " failureType=" + readinessFailureType);
        if (readiness.ready()) {
            return true;
        }
        failures.add("active-model readiness failed: outcome=" + readinessOutcome
            + " attempts=" + readinessAttempts
            + " durationMillis=" + readinessDurationMillis
            + " failureType=" + readinessFailureType);
        logger.warn("BOUNDING_BOX_MODEL_READY_FAILED outcome=" + readinessOutcome
            + " attempts=" + readinessAttempts
            + " durationMillis=" + readinessDurationMillis
            + " failureType=" + readinessFailureType);
        return false;
    }

    /**
     * Deterministic bounded readiness poll shared by the probe and its
     * self-check: samples {@code present} until one present sample, the
     * monotonic {@code deadlineNanos} (read from {@code clockNanos}) is
     * reached, {@code stopped} flips, the calling thread is interrupted, or
     * the snapshot throws. A zero/negative {@code stepMillis} disables the
     * polling sleep so the self-check stays deterministic without
     * production-scale waits; the probe always passes the bounded
     * {@link #MODEL_READY_STEP_MILLIS}.
     */
    static ModelReadiness pollModelReadiness(
        final BooleanSupplier present,
        final BooleanSupplier stopped,
        final LongSupplier clockNanos,
        final long deadlineNanos,
        final long stepMillis
    ) {
        final long startedNanos = clockNanos.getAsLong();
        int attempts = 0;
        while (true) {
            if (stopped.getAsBoolean()) {
                return new ModelReadiness(ReadinessOutcome.STOPPED, attempts,
                    elapsedMillis(startedNanos, clockNanos.getAsLong()), "none");
            }
            attempts++;
            final boolean presentNow;
            try {
                presentNow = present.getAsBoolean();
            } catch (RuntimeException | Error failure) {
                return new ModelReadiness(ReadinessOutcome.SNAPSHOT_FAILED, attempts,
                    elapsedMillis(startedNanos, clockNanos.getAsLong()),
                    failure.getClass().getSimpleName());
            }
            if (presentNow) {
                return new ModelReadiness(ReadinessOutcome.READY, attempts,
                    elapsedMillis(startedNanos, clockNanos.getAsLong()), "none");
            }
            if (clockNanos.getAsLong() >= deadlineNanos) {
                return new ModelReadiness(ReadinessOutcome.TIMEOUT, attempts,
                    elapsedMillis(startedNanos, clockNanos.getAsLong()), "none");
            }
            if (stepMillis > 0) {
                try {
                    Thread.sleep(stepMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new ModelReadiness(ReadinessOutcome.INTERRUPTED, attempts,
                        elapsedMillis(startedNanos, clockNanos.getAsLong()), "none");
                }
            }
        }
    }

    private static long elapsedMillis(final long startedNanos, final long nowNanos) {
        return (nowNanos - startedNanos) / 1_000_000L;
    }

    /** Validation-only structured outcome of the bounded active-model readiness poll. */
    enum ReadinessOutcome { READY, TIMEOUT, STOPPED, INTERRUPTED, SNAPSHOT_FAILED }

    /** Structured readiness evidence: outcome, exact attempts, monotonic duration, failure type. */
    record ModelReadiness(ReadinessOutcome outcome, int attempts, long durationMillis, String failureType) {
        boolean ready() {
            return outcome == ReadinessOutcome.READY;
        }
    }

    private void registerButtons(final List<String> failures) {
        try {
            final Registration a = context.uiHost().contributeBoundingBoxOverlayButton(
                new BoundingBoxOverlayButton(
                    BUTTON_A_ID,
                    "Turboism validation overlay button A",
                    BoundingBoxOverlayButton.IconVariants.normal(ICON_A),
                    10,
                    () -> {
                        clickedA.set(true);
                        logger.info("BOUNDING_BOX_CLICK_EVIDENCE id=" + BUTTON_A_ID);
                    }
                )
            );
            registrationA.set(a);
            final Registration b = context.uiHost().contributeBoundingBoxOverlayButton(
                new BoundingBoxOverlayButton(
                    BUTTON_B_ID,
                    "Turboism validation overlay button B",
                    BoundingBoxOverlayButton.IconVariants.normal(ICON_B),
                    20,
                    () -> {
                        clickedB.set(true);
                        logger.info("BOUNDING_BOX_CLICK_EVIDENCE id=" + BUTTON_B_ID);
                    }
                )
            );
            registrationB.set(b);
            logger.info("BOUNDING_BOX_CONTRIBUTIONS_REGISTERED"
                + " buttonA=" + BUTTON_A_ID + " orderA=10"
                + " buttonB=" + BUTTON_B_ID + " orderB=20");
        } catch (RuntimeException | Error failure) {
            failures.add("button registration failed: " + failure.getClass().getName());
            closeRegistrations();
        }
    }

    /**
     * Polls screen captures until the detector finds exactly one unambiguous
     * candidate per icon with the bounded pair checks. Missing, duplicated or
     * ambiguous candidates never produce a click; the last fail-closed reason
     * is recorded. The last positive frame also becomes the absence context
     * baseline.
     */
    private BoundingBoxIconDetector.Pair awaitUniquePair(
        final Robot robot,
        final Rectangle screen,
        final List<String> failures
    ) {
        final long deadline = System.currentTimeMillis() + DETECTION_TIMEOUT_MILLIS;
        String lastReason = "no-capture-yet";
        BufferedImage lastShot = null;
        boolean firstDiagnosticWritten = false;
        while (System.currentTimeMillis() < deadline) {
            if (stopped.get()) {
                failures.add("probe stopped during detection");
                return null;
            }
            final BufferedImage shot = capture(robot, screen, failures);
            if (shot == null) {
                return null;
            }
            lastShot = shot;
            if (!firstDiagnosticWritten) {
                writeDiagnosticCapture("detection-initial.png", shot);
                firstDiagnosticWritten = true;
            }
            final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(shot);
            if (result.pair() != null) {
                recordCenters(result.pair());
                absenceContext = BoundingBoxIconDetector.captureContext(shot, result.pair());
                logger.info("BOUNDING_BOX_DETECTION_OK"
                    + " centerA=(" + centerAX + "," + centerAY + ")"
                    + " centerB=(" + centerBX + "," + centerBY + ")");
                return result.pair();
            }
            lastReason = result.reason();
            try {
                Thread.sleep(DETECTION_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.add("detection interrupted");
                return null;
            }
        }
        if (lastShot != null) {
            writeDiagnosticCapture("detection-last.png", lastShot);
        }
        failures.add("detection timeout: " + lastReason);
        logger.warn("BOUNDING_BOX_DETECTION_TIMEOUT reason=" + lastReason);
        return null;
    }

    /**
     * Two further captures, {@value #STABILITY_GAP_MILLIS} ms apart, must each
     * produce the same unique pair within {@value #STABILITY_MAX_CENTER_DRIFT}
     * px: the buttons are stably bounded and not flickering. The freshest
     * positive frame stays the absence context baseline.
     */
    private boolean awaitStable(
        final Robot robot,
        final Rectangle screen,
        final BoundingBoxIconDetector.Pair first,
        final List<String> failures
    ) {
        BoundingBoxIconDetector.Pair previous = first;
        for (int frame = 1; frame <= 2; frame++) {
            try {
                Thread.sleep(STABILITY_GAP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.add("stability check interrupted");
                return false;
            }
            final BufferedImage shot = capture(robot, screen, failures);
            if (shot == null) {
                return false;
            }
            final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(shot);
            if (result.pair() == null) {
                failures.add("stability frame " + frame + " lost detection: " + result.reason());
                return false;
            }
            if (drift(previous.a(), result.pair().a()) > STABILITY_MAX_CENTER_DRIFT
                || drift(previous.b(), result.pair().b()) > STABILITY_MAX_CENTER_DRIFT) {
                failures.add("stability frame " + frame + " centers drifted");
                return false;
            }
            previous = result.pair();
            absenceContext = BoundingBoxIconDetector.captureContext(shot, result.pair());
        }
        stabilityFrames = 2;
        logger.info("BOUNDING_BOX_STABILITY_OK frames=" + stabilityFrames
            + " centerA=(" + centerAX + "," + centerAY + ")"
            + " centerB=(" + centerBX + "," + centerBY + ")");
        return true;
    }

    /**
     * Real mouse gesture at the detected center, then bounded wait for the
     * independent SDK callback evidence. Coordinates are never synthesized:
     * only detected centers are clicked.
     */
    private void click(
        final Robot robot,
        final BoundingBoxIconDetector.Center center,
        final String id,
        final AtomicBoolean evidence,
        final List<String> failures
    ) {
        try {
            robot.mouseMove(center.x(), center.y());
            robot.delay(80);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(60);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            final long deadline = System.currentTimeMillis() + CLICK_EVIDENCE_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline && !evidence.get() && !stopped.get()) {
                robot.delay(100);
            }
            if (!evidence.get()) {
                failures.add("click evidence missing id=" + id
                    + " center=(" + center.x() + "," + center.y() + ")");
            } else {
                logger.info("BOUNDING_BOX_CLICK_OK id=" + id
                    + " center=(" + center.x() + "," + center.y() + ")");
            }
        } catch (RuntimeException | Error failure) {
            failures.add("click failed id=" + id + ": " + failure.getClass().getName());
        }
    }

    private void closeRegistrations(final List<String> failures) {
        closeIfPresent(registrationA, BUTTON_A_ID, failures);
        closeIfPresent(registrationB, BUTTON_B_ID, failures);
    }

    private void closeIfPresent(
        final AtomicReference<Registration> slot,
        final String id,
        final List<String> failures
    ) {
        final Registration registration = slot.getAndSet(null);
        if (registration == null) {
            return;
        }
        try {
            registration.close();
            logger.info("BOUNDING_BOX_REGISTRATION_CLOSED id=" + id);
        } catch (RuntimeException | Error failure) {
            failures.add("registration close failed id=" + id + ": " + failure.getClass().getName());
        }
    }

    private void closeRegistrations() {
        final List<String> discarded = new ArrayList<>();
        closeIfPresent(registrationA, BUTTON_A_ID, discarded);
        closeIfPresent(registrationB, BUTTON_B_ID, discarded);
        for (String failure : discarded) {
            logger.warn("BOUNDING_BOX_CLOSE_FAILURE " + failure);
        }
    }

    /**
     * After both registrations close, the next native update cycle must detach
     * both custom entities; polls until two consecutive captures show zero
     * accepted candidates for both icon colors while the bounded surrounding
     * context from the last positive frame stays within tolerance. Occlusion or
     * any context change fails closed because detach cannot be proven.
     */
    private void awaitAbsence(final Robot robot, final Rectangle screen, final List<String> failures) {
        final long deadline = System.currentTimeMillis() + ABSENCE_TIMEOUT_MILLIS;
        String lastReason = "no-capture-yet";
        int confirmed = 0;
        while (System.currentTimeMillis() < deadline) {
            if (stopped.get()) {
                failures.add("probe stopped during absence check");
                return;
            }
            parkCursor(robot);
            final BufferedImage shot = capture(robot, screen, failures);
            if (shot == null) {
                return;
            }
            final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(shot);
            if (absenceContext != null
                && !BoundingBoxIconDetector.contextMatches(shot, absenceContext)) {
                failures.add("absence context changed; detach cannot be proven");
                logger.warn("BOUNDING_BOX_ABSENCE_CONTEXT_CHANGED");
                return;
            }
            if (result.aCount() == 0 && result.bCount() == 0) {
                confirmed++;
                if (confirmed >= ABSENCE_CONFIRM_CAPTURES) {
                    logger.info("BOUNDING_BOX_ABSENCE_OK captures=" + confirmed);
                    return;
                }
            } else {
                confirmed = 0;
                lastReason = result.reason();
            }
            try {
                Thread.sleep(DETECTION_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.add("absence check interrupted");
                return;
            }
        }
        failures.add("absence timeout: " + lastReason);
        logger.warn("BOUNDING_BOX_ABSENCE_TIMEOUT reason=" + lastReason);
    }

    private void parkCursor(final Robot robot) {
        robot.mouseMove(8, 8);
        robot.delay(50);
    }

    // ------------------------------------------------------------------
    // In-process task-window selection precondition
    // ------------------------------------------------------------------

    /**
     * Establishes the native selection state the overlay buttons attach to.
     * The identity-safe bridge wrapper is already installed by
     * {@link BridgeTelemetry#observeAndWrap(AtomicBoolean, PluginLogger)}; if the
     * bridge already fired with a non-empty array the selection is active and no
     * redundant gesture is sent. Otherwise the task JVM's own unique visible
     * Cubism main window is waited for on the EDT, brought to front/focus, and a
     * real Robot double click is delivered at the established window-relative
     * object-tree reference point scaled into the window's client area (decorated
     * outer bounds minus EDT-captured insets). Bounded bridge evidence
     * (invocation count &gt; 0 with a non-empty array) is required before visual
     * detection; any absence, ambiguity, focus failure, or missing evidence fails
     * closed with {@code status=FAIL}.
     */
    private void ensureSelectionActive(
        final Robot robot,
        final List<String> failures,
        final BridgeTelemetry telemetry
    ) {
        if (telemetry.invocations() > 0 && telemetry.nonEmptyArrays() > 0) {
            selectionGesture = "skipped";
            selectionBridgeInvocations = telemetry.invocations();
            selectionBridgeNonEmptyArrays = telemetry.nonEmptyArrays();
            logger.info("BOUNDING_BOX_SELECTION_GESTURE result=skipped reason=already-active"
                + " invocations=" + telemetry.invocations()
                + " nonEmptyArrays=" + telemetry.nonEmptyArrays());
            return;
        }
        final WindowTarget target = awaitSelectionWindow(failures);
        if (target == null || !target.found()) {
            return;
        }
        final Point point = focusSelectionWindow(target, failures);
        if (point == null) {
            return;
        }
        selectionGesture = "performed";
        selectionTarget = target.facts().width + "x" + target.facts().height;
        selectionPoint = "(" + point.x + "," + point.y + ")";
        logger.info("BOUNDING_BOX_SELECTION_GESTURE result=performed"
            + " outer=(" + selectionOuterBounds + ")"
            + " insets=(" + selectionInsets + ")"
            + " client=(" + selectionClientBounds + ")"
            + " source=" + selectionGeometrySource
            + " point=" + selectionPoint
            + " ref=(" + REF_SELECTION_X + "," + REF_SELECTION_Y + ")"
            + " refClient=" + REF_WINDOW_WIDTH + "x" + REF_WINDOW_HEIGHT);
        doubleClick(robot, point, failures);
        if (!failures.isEmpty()) {
            selectionGesture = "failed";
            return;
        }
        if (!awaitSelectionEvidence(telemetry)) {
            selectionGesture = "failed";
            selectionBridgeInvocations = telemetry.invocations();
            selectionBridgeNonEmptyArrays = telemetry.nonEmptyArrays();
            failures.add("selection precondition failed: bridge evidence missing after gesture"
                + " invocations=" + telemetry.invocations()
                + " nonEmptyArrays=" + telemetry.nonEmptyArrays());
            logger.warn("BOUNDING_BOX_SELECTION_PRECONDITION_FAILED reason=bridge-evidence-missing"
                + " invocations=" + telemetry.invocations()
                + " nonEmptyArrays=" + telemetry.nonEmptyArrays());
            return;
        }
        selectionBridgeInvocations = telemetry.invocations();
        selectionBridgeNonEmptyArrays = telemetry.nonEmptyArrays();
        logger.info("BOUNDING_BOX_SELECTION_EVIDENCE_OK invocations=" + telemetry.invocations()
            + " nonEmptyArrays=" + telemetry.nonEmptyArrays());
    }

    /**
     * Bounded wait (all AWT inspection on the EDT) for exactly one unique
     * visible, showing, non-dialog, plausibly main-window-sized window owned by
     * this task JVM; zero or several candidates fail closed. Other processes are
     * never inspected or touched.
     */
    private WindowTarget awaitSelectionWindow(final List<String> failures) {
        final long deadline = System.currentTimeMillis() + SELECTION_WINDOW_TIMEOUT_MILLIS;
        String lastReason = "no-visible-main-window";
        while (System.currentTimeMillis() < deadline) {
            if (stopped.get()) {
                failures.add("selection precondition failed: probe stopped during window wait");
                return WindowTarget.missing("stopped");
            }
            final WindowTarget target;
            try {
                target = onEdt(() -> {
                    final List<Window> windows = Arrays.asList(Window.getWindows());
                    final List<WindowFacts> facts = new ArrayList<>(windows.size());
                    for (Window window : windows) {
                        facts.add(WindowFacts.of(window));
                    }
                    final String selectionFailure = mainWindowSelectionFailure(facts);
                    if (selectionFailure != null) {
                        return WindowTarget.missing(selectionFailure);
                    }
                    for (int index = 0; index < windows.size(); index++) {
                        if (facts.get(index).isMainCandidate()) {
                            return WindowTarget.found(windows.get(index), facts.get(index));
                        }
                    }
                    return WindowTarget.missing("no-visible-main-window");
                });
            } catch (Exception | Error failure) {
                lastReason = "window-inspection-failed:" + failure.getClass().getSimpleName();
                try {
                    Thread.sleep(SELECTION_WINDOW_STEP_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failures.add("selection precondition failed: window wait interrupted");
                    return WindowTarget.missing("interrupted");
                }
                continue;
            }
            if (target.found()) {
                logger.info("BOUNDING_BOX_SELECTION_WINDOW window="
                    + target.facts().width + "x" + target.facts().height);
                return target;
            }
            lastReason = target.reason();
            try {
                Thread.sleep(SELECTION_WINDOW_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.add("selection precondition failed: window wait interrupted");
                return WindowTarget.missing("interrupted");
            }
        }
        failures.add("selection precondition failed: window timeout: " + lastReason);
        logger.warn("BOUNDING_BOX_SELECTION_WINDOW_TIMEOUT reason=" + lastReason);
        return WindowTarget.missing("timeout:" + lastReason);
    }

    /**
     * Brings the exact selected in-process window to front/focus on the EDT,
     * waits boundedly for focus to land, then captures bounds and insets on the
     * EDT, derives the client origin and extent fail-closed, and computes the
     * scaled object-tree click point against the client area only. Focus
     * failure, invalid client extents and out-of-client scaled targets fail
     * closed. The structured outer-bounds/insets/client evidence is recorded
     * for the single gesture line.
     */
    private Point focusSelectionWindow(final WindowTarget target, final List<String> failures) {
        try {
            onEdt(() -> {
                target.window().toFront();
                target.window().requestFocus();
                return null;
            });
        } catch (Exception | Error failure) {
            failures.add("selection precondition failed: focus request failed: "
                + failure.getClass().getSimpleName());
            return null;
        }
        final long deadline = System.currentTimeMillis() + SELECTION_FOCUS_TIMEOUT_MILLIS;
        boolean focused = false;
        while (System.currentTimeMillis() < deadline && !focused) {
            if (stopped.get()) {
                failures.add("selection precondition failed: probe stopped during focus wait");
                return null;
            }
            try {
                focused = Boolean.TRUE.equals(
                    onEdt(() -> target.window().isFocused() && target.window().isShowing()));
            } catch (Exception | Error failure) {
                failures.add("selection precondition failed: focus inspection failed: "
                    + failure.getClass().getSimpleName());
                return null;
            }
            if (!focused) {
                try {
                    Thread.sleep(SELECTION_FOCUS_STEP_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failures.add("selection precondition failed: focus wait interrupted");
                    return null;
                }
            }
        }
        if (!focused) {
            failures.add("selection precondition failed: main window focus not acquired");
            logger.warn("BOUNDING_BOX_SELECTION_FOCUS_FAILED");
            return null;
        }
        try {
            return onEdt(() -> {
                final Rectangle bounds = target.window().getBounds();
                final Insets insets = target.window().getInsets();
                final ClientGeometry content = contentGeometry(target.window());
                final Point scaled = clientPoint(content, REF_SELECTION_X, REF_SELECTION_Y,
                    REF_WINDOW_WIDTH, REF_WINDOW_HEIGHT);
                selectionOuterBounds = bounds.x + "," + bounds.y + ","
                    + bounds.width + "x" + bounds.height;
                selectionInsets = insets.left + "," + insets.top + ","
                    + insets.right + "," + insets.bottom;
                selectionClientBounds = content.x + "," + content.y + ","
                    + content.width + "x" + content.height;
                selectionGeometrySource = "root-pane-content";
                return scaled;
            });
        } catch (Exception | Error failure) {
            failures.add("selection precondition failed: selection point invalid: " + singleLine(failure));
            return null;
        }
    }

    /**
     * Real double-click gesture at the computed screen coordinate. Never
     * hardcoded absolute positions: only the established reference point scaled
     * by the live client geometry is used.
     */
    private void doubleClick(final Robot robot, final Point point, final List<String> failures) {
        try {
            robot.mouseMove(point.x, point.y);
            robot.delay(80);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(50);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(50);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(50);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            logger.info("BOUNDING_BOX_SELECTION_DOUBLE_CLICK point=(" + point.x + "," + point.y + ")");
        } catch (RuntimeException | Error failure) {
            failures.add("selection precondition failed: double click failed: "
                + failure.getClass().getName());
        }
    }

    /**
     * Bounded wait for the required post-gesture bridge activation evidence:
     * invocation count &gt; 0 returning a non-empty array. If the bridge property
     * appears only after the gesture, it is wrapped identity-safely here too;
     * another owner's value is never overwritten.
     */
    private boolean awaitSelectionEvidence(final BridgeTelemetry telemetry) {
        final Properties properties = System.getProperties();
        final long deadline = System.currentTimeMillis() + SELECTION_EVIDENCE_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (stopped.get()) {
                return false;
            }
            if (telemetry.invocations() > 0 && telemetry.nonEmptyArrays() > 0) {
                return true;
            }
            if (!telemetry.wrapperInstalled()) {
                final Object observed = properties.get(BridgeTelemetry.SETUP_PROPERTY);
                if (observed instanceof BiFunction<?, ?, ?> original) {
                    if (telemetry.installWrapper(properties, original) != null) {
                        logger.info("BOUNDING_BOX_WRAPPER_INSTALLED key=" + BridgeTelemetry.SETUP_PROPERTY);
                    }
                }
            }
            try {
                Thread.sleep(SELECTION_EVIDENCE_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Runs a task on the EDT with a bounded wait: a stuck or deadlocked EDT
     * fails closed instead of hanging the probe. When already on the EDT the
     * task runs directly.
     */
    private static <T> T onEdt(final Callable<T> task) throws Exception {
        if (EventQueue.isDispatchThread()) {
            return task.call();
        }
        final FutureTask<T> future = new FutureTask<>(task);
        EventQueue.invokeLater(future);
        try {
            return future.get(EDT_CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("EDT call timed out after " + EDT_CALL_TIMEOUT_MILLIS + "ms");
        }
    }

    /**
     * Pure snapshot of the AWT properties that decide main-window candidacy,
     * captured on the EDT. Kept free of {@link Window} references so the
     * selection rule is deterministically self-checkable without real windows.
     */
    static final class WindowFacts {

        final boolean visible;
        final boolean showing;
        final boolean dialog;
        final int width;
        final int height;

        WindowFacts(final boolean visible, final boolean showing, final boolean dialog,
            final int width, final int height) {
            this.visible = visible;
            this.showing = showing;
            this.dialog = dialog;
            this.width = width;
            this.height = height;
        }

        static WindowFacts of(final Window window) {
            return new WindowFacts(
                window.isVisible(),
                window.isShowing(),
                window instanceof Dialog,
                window.getWidth(),
                window.getHeight()
            );
        }

        boolean isMainCandidate() {
            return visible && showing && !dialog
                && width >= MIN_MAIN_WINDOW_SIDE && height >= MIN_MAIN_WINDOW_SIDE;
        }
    }

    /** Found unique main window, or the fail-closed reason when none/ambiguous. */
    record WindowTarget(Window window, WindowFacts facts, String reason) {

        static WindowTarget found(final Window window, final WindowFacts facts) {
            return new WindowTarget(window, facts, null);
        }

        static WindowTarget missing(final String reason) {
            return new WindowTarget(null, null, reason);
        }

        boolean found() {
            return window != null;
        }
    }

    /**
     * Pure unique-main-window rule: exactly one main-window candidate required;
     * zero candidates fail with {@code no-visible-main-window}, several fail
     * closed as ambiguous.
     */
    static String mainWindowSelectionFailure(final List<WindowFacts> facts) {
        int main = 0;
        for (WindowFacts fact : facts) {
            if (fact.isMainCandidate()) {
                main++;
            }
        }
        if (main == 0) {
            return "no-visible-main-window";
        }
        if (main > 1) {
            return "ambiguous-main-window count=" + main;
        }
        return null;
    }

    /**
     * Scales an established window-relative reference coordinate to a target
     * window extent. Rejects invalid references and non-positive extents; the
     * caller additionally verifies the scaled point stays inside the client
     * area.
     */
    static int scaleRelative(final int reference, final int referenceExtent, final int targetExtent) {
        if (reference < 0 || referenceExtent <= 0 || targetExtent <= 0 || reference >= referenceExtent) {
            throw new IllegalArgumentException("invalid relative coordinate reference=" + reference
                + " referenceExtent=" + referenceExtent + " targetExtent=" + targetExtent);
        }
        return (int) Math.round((double) reference * targetExtent / referenceExtent);
    }

    /**
     * Pure content geometry from authoritative screen-space content-pane
     * values: non-positive extents and implausibly small extents (either side
     * below the main-window minimum side) fail closed. The content origin and
     * extent are the only geometry the frozen selection reference is scaled
     * against; decorated outer bounds and reported {@link Window} insets never
     * participate.
     */
    static ClientGeometry contentGeometry(final int x, final int y, final int width, final int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("non-positive content extent " + width + "x" + height);
        }
        if (width < MIN_MAIN_WINDOW_SIDE || height < MIN_MAIN_WINDOW_SIDE) {
            throw new IllegalArgumentException("implausible content extent " + width + "x" + height
                + " below " + MIN_MAIN_WINDOW_SIDE);
        }
        return new ClientGeometry(x, y, width, height);
    }

    /**
     * Authoritative screen-space content geometry of the focused task-owned
     * window: the selected {@link Window} must be a
     * {@code javax.swing.RootPaneContainer} whose content pane is non-null and
     * showing; only the content pane's {@code getLocationOnScreen()} and
     * {@code getSize()} form the click geometry. Unavailable location and
     * invalid extents fail closed; decorated outer bounds and {@link Window}
     * insets are diagnostic-only and never influence the point.
     */
    static ClientGeometry contentGeometry(final Window window) {
        if (!(window instanceof javax.swing.RootPaneContainer)) {
            throw new IllegalArgumentException("selection window is not a RootPaneContainer");
        }
        final java.awt.Container content = ((javax.swing.RootPaneContainer) window).getContentPane();
        if (content == null || !content.isShowing()) {
            throw new IllegalArgumentException("selection content pane is null or not showing");
        }
        final Point location;
        final java.awt.Dimension size;
        try {
            location = content.getLocationOnScreen();
            size = content.getSize();
        } catch (Exception | Error failure) {
            throw new IllegalArgumentException("selection content geometry unavailable: "
                + failure.getClass().getSimpleName());
        }
        return contentGeometry(location.x, location.y, size.width, size.height);
    }

    /**
     * Scales the frozen reference point from the reference client extent into
     * the observed client extent and offsets it by the client screen origin.
     * A scaled target outside the client area fails closed.
     */
    static Point clientPoint(final ClientGeometry client, final int refX, final int refY,
        final int refWidth, final int refHeight) {
        final int px = client.x + scaleRelative(refX, refWidth, client.width);
        final int py = client.y + scaleRelative(refY, refHeight, client.height);
        if (px < client.x || px >= client.x + client.width
            || py < client.y || py >= client.y + client.height) {
            throw new IllegalArgumentException("scaled target outside client area (" + px + "," + py
                + ") client=" + client);
        }
        return new Point(px, py);
    }

    /** Pure client-area origin and extent derived from decorated bounds + insets. */
    record ClientGeometry(int x, int y, int width, int height) {
    }

    /**
     * Robot screen capture of the whole display. Every failure records a
     * failure reason (so any caller aborts to {@code status=FAIL}) and emits
     * the {@code BOUNDING_BOX_SCREEN_CAPTURE_FAILED} log marker the wrapper
     * treats as a failure marker.
     */
    private BufferedImage capture(
        final Robot robot,
        final Rectangle screen,
        final List<String> failures
    ) {
        try {
            return robot.createScreenCapture(screen);
        } catch (RuntimeException | Error failure) {
            failures.add("screen capture failed: " + failure.getClass().getName());
            logger.error("BOUNDING_BOX_SCREEN_CAPTURE_FAILED " + failure.getClass().getName());
            return null;
        }
    }

    private void writeDiagnosticCapture(final String fileName, final BufferedImage image) {
        try {
            Files.createDirectories(stateDir);
            ImageIO.write(image, "png", stateDir.resolve(fileName).toFile());
        } catch (Exception | Error failure) {
            logger.warn("BOUNDING_BOX_DIAGNOSTIC_CAPTURE_FAILED file=" + fileName
                + " failure=" + failure.getClass().getName());
        }
    }

    private void recordCenters(final BoundingBoxIconDetector.Pair pair) {
        centerAX = Integer.toString(pair.a().x());
        centerAY = Integer.toString(pair.a().y());
        centerBX = Integer.toString(pair.b().x());
        centerBY = Integer.toString(pair.b().y());
    }

    private static long drift(final BoundingBoxIconDetector.Center first, final BoundingBoxIconDetector.Center second) {
        return Math.max(Math.abs(first.x() - second.x()), Math.abs(first.y() - second.y()));
    }

    // ------------------------------------------------------------------
    // Evidence helpers
    // ------------------------------------------------------------------

    private void writeResultFile(
        final long startedNanos,
        final boolean pass,
        final List<String> failures,
        final BridgeTelemetry telemetry
    ) {
        final Path directory = stateDir.getParent();
        final Path result = directory.resolve(RESULT_FILE_NAME);
        final Path temporary = directory.resolve(RESULT_FILE_NAME + ".tmp");
        final StringBuilder report = new StringBuilder()
            .append("schemaVersion=1\n")
            .append("runId=").append(System.getProperty("turboism.validation.runId", "unknown")).append('\n')
            .append("pluginId=dev.turboism.validation.boundingbox\n")
            .append("hostVersion=").append(System.getProperty("turboism.validation.hostVersion", "unknown")).append('\n')
            .append("buttonAId=").append(BUTTON_A_ID).append('\n')
            .append("buttonBId=").append(BUTTON_B_ID).append('\n')
            .append("centerAX=").append(centerAX).append('\n')
            .append("centerAY=").append(centerAY).append('\n')
            .append("centerBX=").append(centerBX).append('\n')
            .append("centerBY=").append(centerBY).append('\n')
            .append("stabilityFrames=").append(stabilityFrames).append('\n')
            .append("clickA=").append(clickedA.get() ? "observed" : "missing").append('\n')
            .append("clickB=").append(clickedB.get() ? "observed" : "missing").append('\n')
            .append("failures=").append(failures.size()).append('\n');
        for (int index = 0; index < failures.size(); index++) {
            report.append("failure.").append(index).append('=')
                .append(singleLine(failures.get(index))).append('\n');
        }
        report.append("durationMillis=").append((System.nanoTime() - startedNanos) / 1_000_000L).append('\n');
        report.append("bridgeObserved=").append(telemetry.bridgeObserved()).append('\n');
        report.append("bridgeValueNotBiFunction=").append(telemetry.bridgeValueNotBiFunction()).append('\n');
        report.append("wrapperInstalled=").append(telemetry.wrapperInstalled()).append('\n');
        report.append("callbackInvocations=").append(telemetry.invocations()).append('\n');
        report.append("callbackNullResults=").append(telemetry.nullResults()).append('\n');
        report.append("callbackNonArrayResults=").append(telemetry.nonArrayResults()).append('\n');
        report.append("callbackEmptyArrays=").append(telemetry.emptyArrays()).append('\n');
        report.append("callbackNonEmptyArrays=").append(telemetry.nonEmptyArrays()).append('\n');
        report.append("callbackMaxArrayLength=").append(telemetry.maxArrayLength()).append('\n');
        report.append("callbackThrowables=").append(telemetry.throwables()).append('\n');
        report.append("transformFailureCounter=")
            .append(String.valueOf(System.getProperties().get("turboism.bounding-box-overlay.failures")))
            .append('\n');
        report.append("wrapperRestored=").append(telemetry.wrapperRestored()).append('\n');
        report.append("selectionGesture=").append(selectionGesture).append('\n');
        report.append("selectionTarget=").append(selectionTarget).append('\n');
        report.append("selectionPoint=").append(selectionPoint).append('\n');
        report.append("selectionOuterBounds=").append(selectionOuterBounds).append('\n');
        report.append("selectionInsets=").append(selectionInsets).append('\n');
        report.append("selectionClientBounds=").append(selectionClientBounds).append('\n');
        report.append("selectionGeometrySource=").append(selectionGeometrySource).append('\n');
        report.append("selectionBridgeInvocations=").append(selectionBridgeInvocations).append('\n');
        report.append("selectionBridgeNonEmptyArrays=").append(selectionBridgeNonEmptyArrays).append('\n');
        report.append("observationMode=")
            .append(Boolean.getBoolean(MANUAL_MODE_PROPERTY) ? "manual" : "automated").append('\n');
        report.append("manualClicksObserved=").append(manualClicksObserved).append('\n');
        report.append("manualCloseRequested=").append(manualCloseRequested).append('\n');
        report.append("manualAbsenceConfirmed=").append(manualAbsenceConfirmed).append('\n');
        report.append("modelReadinessOutcome=").append(readinessOutcome).append('\n');
        report.append("modelReadinessAttempts=").append(readinessAttempts).append('\n');
        report.append("modelReadinessDurationMillis=").append(readinessDurationMillis).append('\n');
        report.append("modelReadinessFailureType=").append(readinessFailureType).append('\n');
        report.append("status=").append(pass ? "PASS" : "FAIL").append('\n');
        try {
            Files.createDirectories(directory);
            Files.writeString(
                temporary,
                report.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            Files.move(
                temporary,
                result,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
            logger.info("BOUNDING_BOX_RESULT_WRITTEN result=" + result
                + " status=" + (pass ? "PASS" : "FAIL")
                + " failures=" + failures.size());
        } catch (Exception failure) {
            logger.error("BOUNDING_BOX_RESULT_WRITE_FAILED result=" + result
                + " " + singleLine(failure), failure);
            logger.error("BOUNDING_BOX_PROBE_RESULT status=FAIL reason=result-write-failed");
        }
    }

    private static String singleLine(final Object value) {
        if (value == null) {
            return "null";
        }
        final String text = value.toString().replace('\n', ' ').replace('\r', ' ');
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    /**
     * Test-only diagnostic telemetry for the loader-neutral bounding-box overlay
     * setup bridge. After the SDK contributions register, this observes the exact
     * diagnostic key {@value #SETUP_PROPERTY} for a JDK {@link BiFunction} and
     * wraps it with an identity-safe {@link Properties#replace(Object,Object,Object)},
     * so the original production callback is never removed or clobbered. Every
     * delegated invocation is counted and its result classified (null, non-array,
     * empty array, non-empty array with the maximum observed length); delegated
     * failures are rethrown unchanged so production transformed-bytecode failure
     * handling stays authoritative. The original value is identity-restored before
     * probe completion. This is evidence only: it never decides PASS/FAIL and never
     * alters the visual detection timeout or result status logic.
     */
    static final class BridgeTelemetry {

        /** Exact diagnostic key of the loader-neutral setup bridge; test-only detail, not a public contract. */
        static final String SETUP_PROPERTY = "turboism.bounding-box-overlay.buttons";

        // The bridge is installed when the host UI lifecycle reaches its ready
        // transition (readiness-gated reconcile), not bounded by contribution
        // registration return; the exact failed 5.2.03 run shows registration at
        // 17:09:02 vs host=ACTIVE at 17:09:44 (~42 s lag). The 60 s spec-ceiling
        // (≤20 s window wait + ≤5 s focus + ≤30 s gesture evidence) with the
        // D5 active-model readiness gate (≤60 s) the worst pre-detection bound
        // is 60+60+20+5+30 = 175 s; 175 s plus the ~240 s visual detection
        // window stays inside the wrapper's 600 s result phase (ready 300 /
        // result 600 / exit 120; there is no single 300 s total deadline).
        private static final long OBSERVE_TIMEOUT_MILLIS = 60_000L;
        private static final long OBSERVE_STEP_MILLIS = 100L;

        private final AtomicLong invocations = new AtomicLong();
        private final AtomicLong nullResults = new AtomicLong();
        private final AtomicLong nonArrayResults = new AtomicLong();
        private final AtomicLong emptyArrays = new AtomicLong();
        private final AtomicLong nonEmptyArrays = new AtomicLong();
        private final AtomicLong throwables = new AtomicLong();
        private final AtomicInteger maxArrayLength = new AtomicInteger();

        private boolean bridgeObserved;
        private boolean bridgeValueNotBiFunction;
        private boolean wrapperInstalled;
        private boolean wrapperRestored;
        private BiFunction<Object, Object, Object> wrapper;
        private Object original;

        /**
         * Bounded observation window (60 seconds, honoring {@code stopped}) for an
         * object implementing JDK {@link BiFunction} at {@value #SETUP_PROPERTY}; the
         * first observed BiFunction is wrapped identity-safely. Exits without
         * observation emit the structured {@code BOUNDING_BOX_BRIDGE_NOT_OBSERVED}
         * marker with {@code reason=timeout|stopped|interrupted}; diagnostic only,
         * never adds failures and never alters the detection timeout or result
         * status logic.
         */
        void observeAndWrap(final AtomicBoolean stopped, final PluginLogger logger) {
            final Properties properties = System.getProperties();
            final long deadline = System.currentTimeMillis() + OBSERVE_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                if (stopped.get()) {
                    logger.info(notObservedMarker("stopped"));
                    return;
                }
                final Object observed = properties.get(SETUP_PROPERTY);
                if (observed == null) {
                    try {
                        Thread.sleep(OBSERVE_STEP_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        logger.info(notObservedMarker("interrupted"));
                        return;
                    }
                    continue;
                }
                bridgeObserved = true;
                logger.info("BOUNDING_BOX_BRIDGE_OBSERVED valueType=" + observed.getClass().getName()
                    + " biFunction=" + (observed instanceof BiFunction));
                if (observed instanceof BiFunction<?, ?, ?> original) {
                    if (installWrapper(properties, original) != null) {
                        logger.info("BOUNDING_BOX_WRAPPER_INSTALLED key=" + SETUP_PROPERTY);
                    } else {
                        logger.warn("BOUNDING_BOX_WRAPPER_NOT_INSTALLED key=" + SETUP_PROPERTY
                            + " reason=another-owner-replaced-the-value");
                    }
                } else {
                    bridgeValueNotBiFunction = true;
                    logger.warn("BOUNDING_BOX_BRIDGE_NOT_BIFUNCTION key=" + SETUP_PROPERTY);
                }
                return;
            }
            logger.info(notObservedMarker("timeout"));
        }

        /** Structured positive marker for observation exits without the bridge. */
        static String notObservedMarker(final String reason) {
            return "BOUNDING_BOX_BRIDGE_NOT_OBSERVED reason=" + reason + " bridgeObserved=false";
        }

        /**
         * Wraps the observed original with a counting delegate using the identity-safe
         * {@link Properties#replace(Object,Object,Object)}. Returns the wrapper, or
         * {@code null} when the property no longer maps to {@code observed} (another
         * owner's value is never overwritten or removed).
         */
        public BiFunction<Object, Object, Object> installWrapper(
            final Properties properties,
            final BiFunction<?, ?, ?> observed
        ) {
            @SuppressWarnings("unchecked")
            final BiFunction<Object, Object, Object> delegated =
                (BiFunction<Object, Object, Object>) observed;
            final BiFunction<Object, Object, Object> installed = (overlay, scene) -> {
                invocations.incrementAndGet();
                try {
                    final Object result = delegated.apply(overlay, scene);
                    record(result);
                    return result;
                } catch (RuntimeException | Error failure) {
                    throwables.incrementAndGet();
                    throw failure;
                }
            };
            if (!properties.replace(SETUP_PROPERTY, observed, installed)) {
                return null;
            }
            this.wrapper = installed;
            this.original = observed;
            wrapperInstalled = true;
            return installed;
        }

        /**
         * Identity-restores the original callback for the given properties; a no-op
         * when this telemetry never installed a wrapper.
         */
        public boolean restoreWrapper(final Properties properties) {
            final BiFunction<Object, Object, Object> installed = this.wrapper;
            if (installed == null) {
                return false;
            }
            wrapperRestored = properties.replace(SETUP_PROPERTY, installed, this.original);
            return wrapperRestored;
        }

        /** Identity-restores the original callback on the system properties. */
        void restore() {
            restoreWrapper(System.getProperties());
        }

        private void record(final Object result) {
            if (result == null) {
                nullResults.incrementAndGet();
            } else if (!(result instanceof Object[] array)) {
                nonArrayResults.incrementAndGet();
            } else if (array.length == 0) {
                emptyArrays.incrementAndGet();
            } else {
                nonEmptyArrays.incrementAndGet();
                maxArrayLength.accumulateAndGet(array.length, Math::max);
            }
        }

        public boolean bridgeObserved() {
            return bridgeObserved;
        }

        public boolean bridgeValueNotBiFunction() {
            return bridgeValueNotBiFunction;
        }

        public boolean wrapperInstalled() {
            return wrapperInstalled;
        }

        public boolean wrapperRestored() {
            return wrapperRestored;
        }

        public long invocations() {
            return invocations.get();
        }

        public long nullResults() {
            return nullResults.get();
        }

        public long nonArrayResults() {
            return nonArrayResults.get();
        }

        public long emptyArrays() {
            return emptyArrays.get();
        }

        public long nonEmptyArrays() {
            return nonEmptyArrays.get();
        }

        public long throwables() {
            return throwables.get();
        }

        public int maxArrayLength() {
            return maxArrayLength.get();
        }
    }
}
