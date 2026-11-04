package dev.turboism.validation.modelupdate;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Test-only, task-window-scoped camera workload. Uses the real Swing mouse-wheel
 * listener and an EDT barrier after the native repaint request. Every observed event
 * must advance the model-update render-path counter. Never paints an extra frame.
 * Measures event-to-EDT-barrier completion, NOT physical mouse-to-photon latency.
 */
final class CanvasWheelWorkload {
    private static final String ENABLE = "turboism.optimization.modelUpdateSkip";
    private static final String STATS = "turboism.model-update-skip.stats";
    private static final String UNIFORM_STATS = "turboism.uniform-location.stats";
    private static final String ELISION_PREDICATE = "turboism.upload-elision.predicate";
    private static final String ELISION_GATE = "turboism.upload-elision.gate";
    private static final String ELISION_STATS = "turboism.upload-elision.stats";
    private static final String STATE_CONSULT = "turboism.state-elision.consult";
    private static final String STATE_GATE = "turboism.state-elision.gate";
    private static final String STATE_STATS = "turboism.state-elision.stats";
    private static final String INPUTPATH_PREDICATE = "turboism.input-path.focus";
    private static final String INPUTPATH_GATE = "turboism.input-path.gate";
    private static final String INPUTPATH_STATS = "turboism.input-path.stats";
    private static final String COMPOSITE_PREDICATE = "turboism.canvas-composite.paint";
    private static final String COMPOSITE_GATE = "turboism.canvas-composite.gate";
    private static final String COMPOSITE_STATS = "turboism.canvas-composite.stats";
    // Measurement-only clipping-mask probe: present only when the run installed it, so
    // these keys document the mask/group cost of the measured legs.
    private static final String CLIPMASK_STATS = "turboism.clip-mask-probe.stats";
    private static final String CLIPMASK_DEDUPE_STATS = "turboism.clip-mask-dedupe.stats";
    private static final String CLIPMASK_SCISSOR_STATS = "turboism.clip-mask-scissor.stats";
    private static final String MESH_SETUP_STATS = "turboism.mesh-setup-elision.stats";
    private static final int WARMUP_PAIRS = 12;
    private static final int MEASURED_PAIRS = 100;
    // Calibration is diagnostic only. Its smaller sample count never satisfies acceptance.
    private final boolean calibration = Boolean.getBoolean("turboism.validation.modelUpdateCalibration");
    private final String fixture;
    private final Path state;
    private Frame window;
    private JComponent canvas;
    private JLabel zoom;
    private String originalZoom;
    private int width, height;
    private final String factor = System.getProperty("turboism.validation.modelUpdateFactor", "modelSkip");
    private boolean originalCanvasBuffering, originalSwingBuffering;
    private javax.swing.RepaintManager repaintManager;
    private GpuCompletionProbe gpuProbe;
    private GlSubmissionProbe glProbe;
    private UniformLocationTrial uniformTrial;
    private NarrowUniformTrial narrowTrial;
    private java.util.concurrent.Callable<String> viewMetadata;
    private NativeInteractionHost cameraHost;
    private NativeInteractionHost.Camera cameraBaseline;
    private NativeInteractionHost.CameraTransform cameraTransformBaseline;
    private String cameraMetadataBaseline;
    private final String humanTracePath = System.getProperty("turboism.validation.humanTrace", "").strip();
    private final boolean humanIdleResume = Boolean.getBoolean("turboism.validation.humanIdleResume");
    // Exact camera restoration is the default: the inverse wheel only restores
    // the displayed rounded zoom, so the cross-leg window digest otherwise
    // compares slightly different camera states. Opt out explicitly with
    // -Dturboism.validation.exactCameraBaseline=false.
    private final boolean exactCamera = Boolean.parseBoolean(
        System.getProperty("turboism.validation.exactCameraBaseline", "true"))
        || !humanTracePath.isEmpty();
    private HumanTrace humanTrace;
    private long measuredQueueNanos, measuredHandlerNanos, measuredRepaintBarrierNanos,
        measuredResumeNanos;

    CanvasWheelWorkload(final String fixture, final Path state) {
        if (fixture == null || fixture.isBlank()) throw new IllegalArgumentException("fixture absent");
        this.fixture = fixture;
        this.state = state;
    }

    /**
     * Records a real human session. The user should zoom, pan and pause as they
     * normally do; the resulting trace is replayed by the human workload.
     */
    void record(final Path output, final long seconds) throws Exception {
        if (output == null) throw new IllegalArgumentException("trace output absent");
        awaitCanvasReady(() -> {
            discover();
            return null;
        });
        onEdt(() -> {
            width = canvas.getWidth();
            height = canvas.getHeight();
            return null;
        });
        Files.writeString(state.resolve("ui-tables.txt"), dumpUiTables(window));
        Files.writeString(state.resolve("wheel-benchmark.txt"),
            "schemaVersion=1\nstatus=RECORDING\nstage=recording\nseconds=" + seconds
                + "\ntrace=" + output + "\n");
        final String kind = System.getProperty("turboism.validation.recordKind", "wheel").strip();
        if (!List.of("wheel", "pan", "artmesh").contains(kind)) {
            throw new IllegalArgumentException("unsupported record kind: " + kind);
        }
        final HumanTrace trace = HumanTrace.record(output, seconds, kind, canvas, window,
            zoom::getText, state.resolve("wheel-progress.txt"));
        Files.writeString(state.resolve("wheel-benchmark.txt"),
            "schemaVersion=1\nstatus=PASS\nworkload=human-trace-recording\nkind=" + trace.kind()
                + "\nevents=" + trace.events().size()
                + "\ndurationNanos=" + trace.durationNanos()
                + "\nwheelNotches=" + trace.wheelNotches()
                + "\ntrace=" + output + "\n");
    }

    void run(final boolean probe) throws Exception {
        // An active SDK model can precede the first completed paint of a large project.
        // Keep this bounded readiness wait outside all interaction measurements.
        final long readinessStarted = System.nanoTime();
        Files.writeString(state.resolve("wheel-benchmark.txt"),
            "schemaVersion=1\nstatus=PREPARING\nstage=await-fixture-canvas\n");
        awaitCanvasReady(() -> {
            discover();
            final Point origin = canvas.getLocationOnScreen();
            canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0, width / 2, height / 2,
                origin.x + width / 2, origin.y + height / 2, 0, false, MouseEvent.NOBUTTON));
            return null;
        });
        final long readinessNanos = System.nanoTime() - readinessStarted;
        final int warmupPairs = calibration ? 2 : WARMUP_PAIRS;
        final int measuredPairs = calibration ? 8 : MEASURED_PAIRS;
        Files.writeString(state.resolve("wheel-progress.txt"), "stage=canvas-ready\n");
        onEdt(() -> {
            // The model/appearance plugins can settle the canvas by one pixel
            // after the initial discovery; bind the workload and camera
            // baseline to the final layout.
            width = canvas.getWidth();
            height = canvas.getHeight();
            cameraHost = new NativeInteractionHost(fixture, window, canvas);
            cameraBaseline = cameraHost.camera();
            cameraTransformBaseline = cameraHost.cameraTransform();
            cameraMetadataBaseline = cameraHost.captureMetadata();
            return null;
        });
        Files.writeString(state.resolve("camera-baseline.txt"), cameraMetadataBaseline);
        final String previous = System.getProperty(ENABLE);
        if (!List.of("modelSkip", "canvasBuffering", "swingBuffering", "uniformCache", "uniformValues", "uniformSuite", "uniformHook", "matrixScratch", "uploadElision", "redundantState", "inputPath", "canvasComposite").contains(factor)) {
            throw new IllegalArgumentException("unknown benchmark factor");
        }
        if (!humanTracePath.isEmpty()) {
            humanTrace = HumanTrace.read(Path.of(humanTracePath));
            if (!List.of("wheel", "pan", "artmesh").contains(humanTrace.kind())) {
                throw new IllegalArgumentException("unsupported human trace kind: " + humanTrace.kind());
            }
            if (!factor.equals("modelSkip")) {
                throw new IllegalArgumentException("human trace supports only the modelSkip factor");
            }
            if (humanTrace.canvasWidth() > 0 && humanTrace.canvasWidth() != width) {
                throw new IllegalArgumentException("trace canvas width " + humanTrace.canvasWidth() + " != " + width);
            }
            if (humanTrace.canvasHeight() > 0 && humanTrace.canvasHeight() != height) {
                throw new IllegalArgumentException("trace canvas height " + humanTrace.canvasHeight() + " != " + height);
            }
        }
        final StringBuilder report = new StringBuilder("schemaVersion=1\n")
            .append("readinessNanos=").append(readinessNanos).append('\n')
            .append("calibration=").append(calibration).append('\n')
            .append("performanceAccepted=false\n")
            .append("workload=").append(humanTrace == null
                ? "awt-wheel-native-repaint-barrier"
                : "awt-human-" + humanTrace.kind() + "-trace").append('\n')
            .append("latencyDefinition=").append(humanTrace == null
                ? "wheel-to-EDT-barrier-with-native-repaint-not-presentation"
                : "human-input-to-first-completed-display").append('\n')
            .append("graphicsConfiguration=").append(canvas.getGraphicsConfiguration().getClass().getName()).append('\n')
            .append("java2dOpenGLRequested=").append(System.getProperty("sun.java2d.opengl", "unset")).append('\n')
            .append("factor=").append(factor).append('\n')
            .append("originalCanvasBuffering=").append(originalCanvasBuffering).append('\n')
            .append("originalSwingBuffering=").append(originalSwingBuffering).append('\n')
            .append("width=").append(width).append("\nheight=").append(height)
            .append("\nzoomBefore=").append(originalZoom).append('\n');
        if (humanTrace != null) {
            report.append("human.trace=").append(humanTracePath).append('\n')
                .append("human.traceKind=").append(humanTrace.kind()).append('\n')
                .append("human.traceEvents=").append(humanTrace.events().size()).append('\n')
                .append("human.traceDurationNanos=").append(humanTrace.durationNanos()).append('\n')
                .append("human.traceWheelNotches=").append(humanTrace.wheelNotches()).append('\n')
                .append("human.idleResume=").append(humanIdleResume).append('\n')
                .append("human.speed=1.0\n");
        }
        try {
            final boolean allocations = Boolean.getBoolean("turboism.validation.allocationProfile");
            final boolean profile = Boolean.getBoolean("turboism.validation.modelUpdateJfr") || allocations;
            report.append("allocationProfiling=").append(allocations).append('\n');
            final boolean gpuWait = Boolean.getBoolean("turboism.validation.modelUpdateGpuWait");
            final boolean glCalls = Boolean.getBoolean("turboism.validation.modelUpdateGlCalls");
            final boolean glCategories = Boolean.getBoolean("turboism.validation.modelUpdateGlCallCategories");
            if (glCategories && !glCalls) {
                throw new IllegalArgumentException("glCallCategories requires the modelUpdateGlCalls probe");
            }
            final boolean glRedundancy = Boolean.getBoolean("turboism.validation.modelUpdateGlRedundancy");
            if (glRedundancy && !glCalls) {
                throw new IllegalArgumentException("glRedundancy requires the modelUpdateGlCalls probe");
            }
            if (factor.equals("uploadElision")) {
                final java.util.Properties slots = System.getProperties();
                if (!(slots.get(ELISION_PREDICATE) instanceof java.util.function.BiPredicate)
                    || !(slots.get(ELISION_GATE) instanceof java.util.function.Consumer)) {
                    throw new IllegalArgumentException(
                        "uploadElision factor requires the skippedFrameUploadElision hook installed");
                }
                report.append("uploadElision.installed=true\n");
            }
            if (factor.equals("redundantState")) {
                final java.util.Properties slots = System.getProperties();
                if (!(slots.get(STATE_CONSULT) instanceof java.lang.invoke.MethodHandle)
                    || !(slots.get(STATE_GATE) instanceof java.util.function.Consumer)) {
                    throw new IllegalArgumentException(
                        "redundantState factor requires the redundantStateElision hook installed");
                }
                report.append("stateElision.installed=true\n");
            }
            if (factor.equals("inputPath")) {
                final java.util.Properties slots = System.getProperties();
                if (!(slots.get(INPUTPATH_PREDICATE) instanceof java.util.function.Predicate)
                    || !(slots.get(INPUTPATH_GATE) instanceof java.util.function.Consumer)) {
                    throw new IllegalArgumentException(
                        "inputPath factor requires the inputPathElision hook installed");
                }
                report.append("inputPath.installed=true\n");
            }
            if (factor.equals("canvasComposite")) {
                final java.util.Properties slots = System.getProperties();
                if (!(slots.get(COMPOSITE_PREDICATE) instanceof java.util.function.Predicate)
                    || !(slots.get(COMPOSITE_GATE) instanceof java.util.function.Consumer)) {
                    throw new IllegalArgumentException(
                        "canvasComposite factor requires the canvasCompositeElision hook installed");
                }
                report.append("canvasComposite.installed=true\n");
            }
            final boolean uniform = factor.equals("uniformCache") || factor.equals("uniformValues") || factor.equals("uniformSuite");
            final boolean resourceTelemetry = Boolean.getBoolean("turboism.validation.resources");
            report.append("resourceTelemetry=").append(resourceTelemetry).append('\n');
            final boolean matrix = factor.equals("matrixScratch");
            final boolean narrow = factor.equals("uniformHook") || matrix;
            report.append("matrixScratch.comparison=").append(matrix).append('\n');
            if (matrix) report.append("matrixScratch.uniformCacheFixedOn=true\nmatrixScratch.executionCounter=false\n");
            final boolean uniformShadow = Boolean.getBoolean(narrow
                ? "turboism.uniform-location.shadow" : "turboism.validation.uniformCacheShadow");
            if ((factor.equals("uniformValues") || factor.equals("uniformSuite")) && uniformShadow) {
                throw new IllegalArgumentException("location-shadow mode is not a value-write shadow experiment");
            }
            // A JFR-only ON leg diagnoses the residual path after caching. It is
            // deliberately not an OFF/ON performance comparison. Keep intrusive
            // GL decorators and forced GPU waits separate from this experiment.
            if ((uniform || narrow) && (glCalls || gpuWait || probe)) {
                throw new IllegalArgumentException("uniform trial requires GL-call timing, GPU waits and model digest probes OFF");
            }
            if (uniform) {
                uniformTrial = onEdt(() -> UniformLocationTrial.attach(canvas, uniformShadow));
                report.append("uniformCache.scope=single-display-context-thread-error-confirmed\n")
                    .append("uniformCache.shadow=").append(uniformShadow).append('\n')
                    .append("uniformCache.trialOnly=true\n");
                verifyUniformPixels(report);
                report.append("uniformCache.twoZoomPixelParity=true\n")
                    .append("uniformCache.pixelSource=native-glReadPixels-nonuniform\n")
                    .append("uniformCache.distinctCameraStates=true\n");
            }
            if (narrow) {
                viewMetadata = onEdt(() -> {
                    NativeInteractionHost host = new NativeInteractionHost(fixture, window, canvas);
                    return host::captureMetadata;
                });
                narrowTrial = onEdt(() -> NarrowUniformTrial.attach(canvas, matrix));
                report.append("uniformHook.glProxy=false\n")
                    .append("uniformHook.shadow=").append(uniformShadow).append('\n');
                verifyUniformPixels(report);
                report.append("uniformCache.twoZoomPixelParity=true\n")
                    .append("uniformCache.pixelSource=native-canvas-paint-ARGB-with-hook-execution\n")
                    .append("uniformCache.distinctCameraStates=true\n");
            }
            if (glCalls) glProbe = onEdt(() -> GlSubmissionProbe.attach(canvas));
            if (gpuWait) gpuProbe = onEdt(() -> new GpuCompletionProbe(canvas));
            report.append("profiling=").append(profile).append('\n')
                .append("diagnosticOnly=").append(profile || glCalls || gpuWait || probe || uniformShadow).append('\n')
                .append("gpuCompletion.enabled=").append(gpuWait).append('\n')
                .append("glCallCategories=").append(glCategories).append('\n')
                .append("glRedundancy=").append(glRedundancy).append('\n');
            final boolean diagnostic = probe || profile || gpuWait || glCalls || uniformShadow;
            if (factor.equals("uniformSuite") && diagnostic) throw new IllegalArgumentException("suite requires diagnostic profilers OFF");
            if (factor.equals("redundantState") && diagnostic) throw new IllegalArgumentException("redundantState requires diagnostic profilers OFF");
            final boolean nativeRepeat = Boolean.getBoolean("turboism.validation.nativeRepeatControl");
            if (nativeRepeat && (!factor.equals("modelSkip") || diagnostic)) {
                throw new IllegalArgumentException("native repeat control requires unprofiled modelSkip factor");
            }
            report.append("nativeRepeatControl=").append(nativeRepeat).append('\n');
            final int[] variants = nativeRepeat ? new int[]{0, 0, 0, 0}
                : factor.equals("uniformSuite") ? new int[]{0, 1, 2, 2, 1, 0}
                : diagnostic ? new int[]{1} : new int[]{0, 1, 1, 0};
            final List<String> legFocusOwners = new ArrayList<>();
            final List<String> legCanvasCursors = new ArrayList<>();
            final List<String> legWindowDigests = new ArrayList<>();
            for (int leg = 0; leg < variants.length; leg++) {
                if (exactCamera) onEdt(() -> { restoreExactCamera(); return null; });
                final int variant = variants[leg];
                final boolean enabled = variant != 0;
                final String variantName = factor.equals("uniformSuite")
                    ? (variant == 0 ? "native" : variant == 1 ? "locations" : "locations-and-values")
                    : matrix ? (enabled ? "locations-and-matrix-scratch" : "locations-only")
                    : narrow ? (enabled ? "narrow-locations" : "native") : enabled ? "on" : "off";
                final String beforePixels = "modelSkip".equals(factor) ? "not-requested" : capturePixels(leg == 0 ? "restored-start" : null);
                onEdt(() -> {
                    if (factor.equals("modelSkip")) {
                        System.setProperty(ENABLE, Boolean.toString(enabled));
                    } else if (factor.equals("uniformCache") || factor.equals("uniformValues") || factor.equals("uniformSuite")) {
                        System.setProperty(ENABLE, "true");
                        if (factor.equals("uniformSuite")) {
                            uniformTrial.setEnabled(variant >= 1);
                            uniformTrial.setValuesEnabled(variant >= 2);
                        } else setTrialFactor(enabled);
                        canvas.repaint();
                    } else if (narrow) {
                        System.setProperty(ENABLE, "true");
                        narrowTrial.setEnabled(enabled);
                        canvas.repaint();
                    } else if (factor.equals("uploadElision")) {
                        System.setProperty(ENABLE, "true");
                        final Object gate = System.getProperties().get(ELISION_GATE);
                        if (enabled && !(gate instanceof java.util.function.Consumer)) {
                            throw new IllegalStateException("upload elision gate absent");
                        }
                        if (gate instanceof java.util.function.Consumer consumer) {
                            consumer.accept(enabled);
                        }
                        canvas.repaint();
                    } else if (factor.equals("redundantState")) {
                        System.setProperty(ENABLE, "true");
                        final Object gate = System.getProperties().get(STATE_GATE);
                        if (enabled && !(gate instanceof java.util.function.Consumer)) {
                            throw new IllegalStateException("state elision gate absent");
                        }
                        if (gate instanceof java.util.function.Consumer consumer) {
                            consumer.accept(enabled);
                        }
                        canvas.repaint();
                    } else if (factor.equals("inputPath")) {
                        System.setProperty(ENABLE, "true");
                        // Production uploadElision and the uniform hook keep
                        // their own armed state; only the input-path gate
                        // differs between legs.
                        setInputPathArmed(enabled);
                        canvas.repaint();
                    } else if (factor.equals("canvasComposite")) {
                        System.setProperty(ENABLE, "true");
                        // Production uploadElision and inputPathElision keep
                        // their own armed state; only the composite gate
                        // differs between legs.
                        setCompositeArmed(enabled);
                        canvas.repaint();
                    } else if (factor.equals("canvasBuffering")) {
                        System.setProperty(ENABLE, "true");
                        canvas.setDoubleBuffered(enabled ? false : originalCanvasBuffering);
                        canvas.repaint();
                    } else {
                        System.setProperty(ENABLE, "true");
                        repaintManager.setDoubleBufferingEnabled(enabled ? false : originalSwingBuffering);
                        canvas.repaint();
                    }
                    return null;
                });
                onEdt(() -> null);
                final String afterPixels = "modelSkip".equals(factor) ? "not-requested" : capturePixels(null);
                if (!beforePixels.equals(afterPixels)) {
                    throw new IllegalStateException("buffering change altered canvas pixels");
                }
                if (humanTrace != null) {
                    runHumanLeg(leg, enabled, report, legFocusOwners, legCanvasCursors, legWindowDigests);
                    continue;
                }
                Files.writeString(state.resolve("wheel-progress.txt"),
                    "stage=warmup\nleg=" + leg + "\nenabled=" + enabled + "\n");
                for (int pair = 0; pair < warmupPairs; pair++) runPair(null, 0);
                Files.writeString(state.resolve("wheel-progress.txt"),
                    "stage=measuring\nleg=" + leg + "\nenabled=" + enabled + "\n");
                final Map<String, Long> before = snapshot();
                final Map<String, Long> uniformBefore = uniformTrial != null ? uniformTrial.snapshot()
                    : narrowTrial != null ? narrowTrial.snapshot() : Map.of();
                final Map<String, Long> elisionBefore = uploadElisionStats();
                final Map<String, Long> stateBefore = stateElisionStats();
                final Map<String, Long> uniformHookBefore = statsSlot(UNIFORM_STATS);
                final Map<String, Long> clipMaskBefore = statsSlot(CLIPMASK_STATS);
                final Map<String, Long> clipMaskDedupeBefore = statsSlot(CLIPMASK_DEDUPE_STATS);
                final Map<String, Long> clipMaskScissorBefore = statsSlot(CLIPMASK_SCISSOR_STATS);
                final Map<String, Long> meshSetupBefore = statsSlot(MESH_SETUP_STATS);
                final Map<String, Long> inputPathBefore = inputPathStats();
                final Map<String, Long> compositeBefore = compositeStats();
                final long[] nanos = new long[measuredPairs * 2];
                measuredQueueNanos = measuredHandlerNanos = measuredRepaintBarrierNanos = measuredResumeNanos = 0L;
                final long elapsed;
                final BenchmarkResources resources = resourceTelemetry
                    ? new BenchmarkResources(canvas.getClass().getClassLoader(), onEdt(() -> Thread.currentThread().getId())) : null;
                try (resources; jdk.jfr.Recording recording = profile
                        ? new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")) : null) {
                    if (recording != null) {
                        recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(5));
                        recording.enable("jdk.NativeMethodSample").withPeriod(java.time.Duration.ofMillis(5));
                        recording.enable("jdk.JavaMonitorEnter").withThreshold(java.time.Duration.ofMillis(1));
                        recording.enable("jdk.ThreadPark").withThreshold(java.time.Duration.ofMillis(1));
                        if (allocations) AllocationProfile.configure(recording);
                        recording.start();
                    }
                    if (gpuProbe != null) onEdt(() -> { gpuProbe.start(); return null; });
                    if (glProbe != null) onEdt(() -> { glProbe.start(); return null; });
                    if (resources != null) resources.start();
                    final long start = System.nanoTime();
                    for (int pair = 0; pair < measuredPairs; pair++) runPair(nanos, pair * 2);
                    elapsed = System.nanoTime() - start;
                    if (resources != null) resources.stop();
                    if (glProbe != null) {
                        onEdt(() -> { glProbe.stop(); return null; });
                        report.append(glProbe.report());
                        glProbe.requireValid();
                    }
                    if (gpuProbe != null) {
                        onEdt(() -> { gpuProbe.stop(); return null; });
                        gpuProbe.requireValid();
                        report.append(gpuProbe.report());
                    }
                    if (recording != null) {
                        recording.stop();
                        Path recorded = state.resolve("wheel-leg-" + leg + ".jfr");
                        recording.dump(recorded);
                        if (allocations) Files.writeString(state.resolve("allocation-leg-" + leg + ".txt"),
                            AllocationProfile.summarize(recorded));
                    }
                }
                final Map<String, Long> after = snapshot();
                final long[] sorted = nanos.clone();
                Arrays.sort(sorted);
                long total = 0L;
                int over33 = 0, over50 = 0;
                for (long value : nanos) {
                    total += value;
                    if (value > 33_000_000L) over33++;
                    if (value > 50_000_000L) over50++;
                }
                final String p = "leg." + leg + ".";
                report.append(p).append("enabled=").append(enabled).append('\n')
                    .append(p).append("variant=").append(variantName).append('\n')
                    .append(p).append("canvasPixelParity=").append("modelSkip".equals(factor)
                        ? "not-requested" : Boolean.toString(beforePixels.equals(afterPixels))).append('\n')
                    .append(p).append("canvasPixelDigest=").append(afterPixels).append('\n')
                    .append(p).append("samples=").append(nanos.length).append('\n')
                    .append(p).append("elapsedNanos=").append(elapsed).append('\n')
                    .append(p).append("meanNanos=").append(total / nanos.length).append('\n')
                    .append(p).append("meanQueueNanos=").append(measuredQueueNanos / nanos.length).append('\n')
                    .append(p).append("meanHandlerNanos=").append(measuredHandlerNanos / nanos.length).append('\n')
                    .append(p).append("meanRepaintBarrierNanos=").append(measuredRepaintBarrierNanos / nanos.length).append('\n')
                    .append(p).append("meanCallerResumeNanos=").append(measuredResumeNanos / nanos.length).append('\n')
                    .append(p).append("p50Nanos=").append(percentile(sorted, 0.50)).append('\n')
                    .append(p).append("p95Nanos=").append(percentile(sorted, 0.95)).append('\n')
                    .append(p).append("p99Nanos=").append(percentile(sorted, 0.99)).append('\n')
                    .append(p).append("maxNanos=").append(sorted[sorted.length - 1]).append('\n')
                    .append(p).append("over33ms=").append(over33).append('\n')
                    .append(p).append("over50ms=").append(over50).append('\n')
                    .append(p).append("rawNanos=").append(Arrays.toString(nanos)).append('\n');
                for (String key : after.keySet().stream().sorted().toList()) {
                    if (key.equals("active") || key.equals("parameterCount") || key.endsWith("MaxNanos")) continue;
                    report.append(p).append("modelUpdate.").append(key).append('=')
                        .append(after.get(key) - before.getOrDefault(key, 0L)).append('\n');
                }
                final Map<String, Long> elisionAfter = uploadElisionStats();
                // Counters report the leg delta; gauges and high-water marks
                // report the absolute reading — a delta would show 0 on every
                // leg after the table first fills.
                final java.util.Set<String> elisionGauges = java.util.Set.of(
                    "entries", "capacity", "peakEntries", "snapshotBytes",
                    "snapshotBytesPeak", "mode");
                for (String key : elisionAfter.keySet().stream().sorted().toList()) {
                    if (key.equals("armed")) continue;
                    report.append(p).append("uploadElision.").append(key).append('=')
                        .append(elisionGauges.contains(key)
                            ? elisionAfter.get(key)
                            : elisionAfter.get(key) - elisionBefore.getOrDefault(key, 0L))
                        .append('\n');
                }
                final Map<String, Long> stateAfter = stateElisionStats();
                // Same convention: counters are leg deltas; gauges/peak
                // readings report the absolute value at leg end.
                final java.util.Set<String> stateGauges = java.util.Set.of(
                    "entries", "contexts", "mode");
                for (String key : stateAfter.keySet().stream().sorted().toList()) {
                    if (key.equals("armed")) continue;
                    report.append(p).append("stateElision.").append(key).append('=')
                        .append(stateGauges.contains(key)
                            ? stateAfter.get(key)
                            : stateAfter.get(key) - stateBefore.getOrDefault(key, 0L))
                        .append('\n');
                }
                // Production uniform-hook engagement for co-installed legs: the
                // slot is absent unless the reviewed uniform hook published it,
                // so these keys only appear when the cache is actually live.
                final Map<String, Long> uniformHookAfter = statsSlot(UNIFORM_STATS);
                for (String key : uniformHookAfter.keySet().stream().sorted().toList()) {
                    report.append(p).append("uniformLocationHook.").append(key).append('=')
                        .append(key.equals("active")
                            ? uniformHookAfter.get(key)
                            : uniformHookAfter.get(key) - uniformHookBefore.getOrDefault(key, 0L))
                        .append('\n');
                }
                if (factor.equals("inputPath")) {
                    final Map<String, Long> inputPathAfter = inputPathStats();
                    for (String key : inputPathAfter.keySet().stream().sorted().toList()) {
                        if (key.equals("armed")) continue;
                        report.append(p).append("inputPath.").append(key).append('=')
                            .append(inputPathAfter.get(key)
                                - inputPathBefore.getOrDefault(key, 0L))
                            .append('\n');
                    }
                }
                if (factor.equals("canvasComposite")) {
                    final Map<String, Long> compositeAfter = compositeStats();
                    for (String key : compositeAfter.keySet().stream().sorted().toList()) {
                        if (key.equals("armed")) continue;
                        report.append(p).append("canvasComposite.").append(key).append('=')
                            .append(compositeAfter.get(key)
                                - compositeBefore.getOrDefault(key, 0L))
                            .append('\n');
                    }
                }
                final String legFocusOwner = onEdt(CanvasWheelWorkload::focusOwnerFingerprint);
                final String legCanvasCursor = onEdt(this::canvasCursorFingerprint);
                legFocusOwners.add(legFocusOwner);
                legCanvasCursors.add(legCanvasCursor);
                report.append(p).append("focusOwner=").append(legFocusOwner).append('\n')
                    .append(p).append("canvasCursor=").append(legCanvasCursor).append('\n');
                // Window-level parity: canvasPixelDigest only proves the GL
                // readback, not what reaches the screen. Capture the canvas
                // parent region on screen via Robot; fall back to an
                // offscreen printAll render when Robot is unavailable (e.g.
                // Wine GDI capture limitations) — printAll proves the Swing
                // composite result but cannot see the real blit.
                final int captureLeg = leg;
                onEdt(() -> {
                    Files.writeString(state.resolve("camera-leg-" + captureLeg + "-before-restore.txt"),
                        cameraHost.captureMetadata());
                    if (exactCamera) restoreExactCamera();
                    Files.writeString(state.resolve("camera-leg-" + captureLeg + "-capture.txt"),
                        cameraHost.captureMetadata());
                    return null;
                });
                report.append(p).append("exactCameraBaseline=").append(exactCamera).append('\n');
                final String[] windowShot = onEdt(this::windowSnapshot);
                legWindowDigests.add(windowShot[0]);
                report.append(p).append("windowDigest=").append(windowShot[0]).append('\n')
                    .append(p).append("windowDigestMethod=").append(windowShot[1]).append('\n')
                    .append(p).append("windowDistinctColors=").append(windowShot[2]).append('\n');
                if (resources != null) {
                    for (var entry : resources.snapshot().entrySet()) {
                        report.append(p).append("resources.").append(entry.getKey()).append('=')
                            .append(entry.getValue()).append('\n');
                    }
                }
                // Measurement-only mask/group counters, reported next to the leg's
                // renderFramesPerSecond keys. Counter keys are leg deltas; the gauge
                // (active) reports the absolute reading, and the per-rendered-frame
                // values normalise the mask cost without post-processing.
                final Map<String, Long> clipMaskAfter = statsSlot(CLIPMASK_STATS);
                for (String key : clipMaskAfter.keySet().stream().sorted().toList()) {
                    report.append(p).append("clipMask.").append(key).append('=')
                        .append(key.equals("active")
                            ? clipMaskAfter.get(key)
                            : clipMaskAfter.get(key) - clipMaskBefore.getOrDefault(key, 0L))
                        .append('\n');
                }
                final long clipMaskFrames = clipMaskAfter.getOrDefault("frames", 0L)
                    - clipMaskBefore.getOrDefault("frames", 0L);
                if (clipMaskFrames > 0L) {
                    report.append(p).append("clipMask.maskPassesPerRenderedFrame=")
                        .append((double) (clipMaskAfter.getOrDefault("maskPasses", 0L)
                            - clipMaskBefore.getOrDefault("maskPasses", 0L)) / clipMaskFrames).append('\n')
                        .append(p).append("clipMask.maskPathNanosPerRenderedFrame=")
                        .append((double) (clipMaskAfter.getOrDefault("maskPathNanos", 0L)
                            - clipMaskBefore.getOrDefault("maskPathNanos", 0L)) / clipMaskFrames).append('\n')
                        .append(p).append("clipMask.groupPassesPerRenderedFrame=")
                        .append((double) (clipMaskAfter.getOrDefault("groupPasses", 0L)
                            - clipMaskBefore.getOrDefault("groupPasses", 0L)) / clipMaskFrames).append('\n');
                }
                final Map<String, Long> clipMaskDedupeAfter = statsSlot(CLIPMASK_DEDUPE_STATS);
                for (String key : clipMaskDedupeAfter.keySet().stream().sorted().toList()) {
                    report.append(p).append("clipMaskDedupe.").append(key).append('=')
                        .append(clipMaskDedupeAfter.get(key) - clipMaskDedupeBefore.getOrDefault(key, 0L))
                        .append('\n');
                }
                final Map<String, Long> meshSetupAfter = statsSlot(MESH_SETUP_STATS);
                for (String key : meshSetupAfter.keySet().stream().sorted().toList()) {
                    final boolean gauge = key.equals("active") || key.equals("generation") || key.equals("trackedMeshes");
                    report.append(p).append("meshSetupElision.").append(key).append('=')
                        .append(gauge
                            ? meshSetupAfter.get(key)
                            : meshSetupAfter.get(key) - meshSetupBefore.getOrDefault(key, 0L))
                        .append('\n');
                }
                final Map<String, Long> clipMaskScissorAfter = statsSlot(CLIPMASK_SCISSOR_STATS);
                for (String key : clipMaskScissorAfter.keySet().stream().sorted().toList()) {
                    if (key.equals("savedPermille")) continue;
                    final boolean gauge = key.equals("active") || key.equals("enabled")
                        || key.equals("trackedTextures");
                    report.append(p).append("clipMaskScissor.").append(key).append('=')
                        .append(gauge
                            ? clipMaskScissorAfter.get(key)
                            : clipMaskScissorAfter.get(key) - clipMaskScissorBefore.getOrDefault(key, 0L))
                        .append('\n');
                }
                final long scissorBaseline = clipMaskScissorAfter.getOrDefault("baselinePixels", 0L)
                    - clipMaskScissorBefore.getOrDefault("baselinePixels", 0L);
                final long scissorCleared = clipMaskScissorAfter.getOrDefault("clearedPixels", 0L)
                    - clipMaskScissorBefore.getOrDefault("clearedPixels", 0L);
                if (scissorBaseline > 0L) {
                    report.append(p).append("clipMaskScissor.savedPermille=")
                        .append((scissorBaseline - scissorCleared) * 1000L / scissorBaseline).append('\n');
                    if (clipMaskFrames > 0L) {
                        report.append(p).append("clipMaskScissor.clearedPixelsPerRenderedFrame=")
                            .append((double) scissorCleared / clipMaskFrames).append('\n')
                            .append(p).append("clipMaskScissor.savedPixelsPerRenderedFrame=")
                            .append((double) (scissorBaseline - scissorCleared) / clipMaskFrames).append('\n');
                    }
                }
                if (uniformTrial != null) {
                    long completed = uniformTrial.snapshot().get("completedDisplayFrames")
                        - uniformBefore.getOrDefault("completedDisplayFrames", 0L);
                    report.append(p).append("completedDisplayFrames=").append(completed).append('\n')
                        .append(p).append("renderFramesPerSecond=").append(completed * 1_000_000_000.0 / elapsed).append('\n');
                    for (var entry : uniformTrial.snapshot().entrySet()) {
                        report.append(p).append("uniformCache.").append(entry.getKey()).append('=')
                            .append(entry.getValue() - uniformBefore.getOrDefault(entry.getKey(), 0L)).append('\n');
                    }
                }
                if (narrowTrial != null) {
                    Map<String, Long> observed = narrowTrial.snapshot();
                    narrowTrial.requireLeg(uniformBefore, observed, enabled, nanos.length);
                    long completed = NarrowUniformTrial.delta(uniformBefore, observed, "completedDisplayFrames");
                    if (completed < nanos.length) throw new IllegalStateException("missing native display completions");
                    report.append(p).append("completedDisplayFrames=").append(completed).append('\n')
                        .append(p).append("renderFramesPerSecond=").append(completed * 1_000_000_000.0 / elapsed).append('\n');
                    for (var entry : observed.entrySet()) {
                        report.append(p).append("uniformHook.").append(entry.getKey()).append('=')
                            .append(entry.getValue() - uniformBefore.getOrDefault(entry.getKey(), 0L)).append('\n');
                    }
                }
                Files.writeString(state.resolve("wheel-benchmark.txt"), report);
            }
            // The focus owner and the canvas cursor are observable Java-side
            // state the elision must never disturb: identical fingerprints on
            // every leg are a correctness gate, not just telemetry.
            if (legFocusOwners.stream().distinct().count() != 1
                || legCanvasCursors.stream().distinct().count() != 1) {
                throw new IllegalStateException("focus/cursor state differs across legs: owners="
                    + legFocusOwners + " cursors=" + legCanvasCursors);
            }
            report.append("crossLegFocusOwnerParity=true\n")
                .append("crossLegCanvasCursorParity=true\n");
            if (legWindowDigests.stream().distinct().count() != 1) {
                throw new IllegalStateException(
                    "window-level screenshots differ across legs: " + legWindowDigests);
            }
            report.append("crossLegWindowDigestParity=true\n");
            if (uniformTrial != null) uniformTrial.requireValid();
            if ((factor.equals("uniformValues") || factor.equals("uniformSuite")) && uniformTrial.snapshot().get("skippedUniformWrites") == 0L) {
                throw new IllegalStateException("uniform value experiment did not exercise any eligible write");
            }
            final String restored = onEdt(() -> zoom.getText());
            if (!originalZoom.equals(restored)) throw new IllegalStateException("zoom was not restored");
            report.append("zoomRestored=").append(restored).append("\nstatus=PASS\n");
        } catch (Throwable failure) {
            report.append("status=FAIL\nerror=").append(failure).append('\n');
            throw failure;
        } finally {
            onEdt(() -> {
                if (gpuProbe != null) gpuProbe.close();
                if (glProbe != null) glProbe.close();
                if (uniformTrial != null) uniformTrial.close();
                if (narrowTrial != null) narrowTrial.close();
                if (previous == null) System.clearProperty(ENABLE); else System.setProperty(ENABLE, previous);
                canvas.setDoubleBuffered(originalCanvasBuffering);
                if (javax.swing.RepaintManager.currentManager(canvas) == repaintManager) {
                    repaintManager.setDoubleBufferingEnabled(originalSwingBuffering);
                }
                return null;
            });
            Files.writeString(state.resolve("wheel-benchmark.txt"), report);
        }
    }

    /** The values experiment keeps the proven location cache ON in both controls. */
    private void setTrialFactor(boolean enabled) {
        if (narrowTrial != null) {
            narrowTrial.setEnabled(enabled);
        } else if (factor.equals("uniformSuite")) {
            uniformTrial.setEnabled(enabled);
            uniformTrial.setValuesEnabled(enabled);
        } else if (factor.equals("uniformValues")) {
            uniformTrial.setEnabled(true);
            uniformTrial.setValuesEnabled(enabled);
        } else {
            uniformTrial.setValuesEnabled(false);
            uniformTrial.setEnabled(enabled);
        }
    }

    /** Toggle at one fixed camera state: inverse wheel may restore only rounded zoom. */
    private void verifyUniformPixels(StringBuilder report) throws Exception {
        String original = verifyUniformPixelsAtState("original", report);
        dispatchAndDrain(-1);
        try {
            String alternate = verifyUniformPixelsAtState("alternate", report);
            if (original.equals(alternate)) throw new IllegalStateException("camera states identical; parity vacuous");
        } finally {
            dispatchAndDrain(1);
        }
    }

    private String verifyUniformPixelsAtState(String stateName, StringBuilder report) throws Exception {
        final FrameReadback nativeBefore, nativeRepeat, cached, nativeAfter;
        if (narrowTrial != null) {
            // Keep hover/action events out of the four same-state controls. Paint
            // synchronously here, never queue a nested EDT wait or change timing.
            final String[] metadata = {null};
            List<FrameReadback> captures = NativeParitySequence.capture(enabled -> {
                String before = viewMetadata.call();
                setTrialFactor(enabled);
                FrameReadback frame = narrowTrial.capture(canvas);
                String after = viewMetadata.call();
                if (!before.equals(after) || (metadata[0] != null && !metadata[0].equals(after))) {
                    throw new IllegalStateException("camera/viewport changed during parity capture");
                }
                metadata[0] = after;
                return frame;
            });
            Files.writeString(state.resolve("wheel-pixels-" + stateName + ".properties"), metadata[0]);
            nativeBefore = captures.get(0);
            nativeRepeat = captures.get(1);
            cached = captures.get(2);
            nativeAfter = captures.get(3);
        } else {
            // The legacy GL-proxy path requires asynchronous readback completion.
            onEdt(() -> { setTrialFactor(false); return null; });
            nativeBefore = captureNativeFrame();
            nativeRepeat = captureNativeFrame();
            onEdt(() -> { setTrialFactor(true); return null; });
            cached = captureNativeFrame();
            onEdt(() -> { setTrialFactor(false); return null; });
            nativeAfter = captureNativeFrame();
        }
        if (narrowTrial != null) nativeBefore.writeArgbPng(state.resolve("wheel-pixels-" + stateName + ".png"));
        String key = "uniformCache.parity." + stateName + ".";
        report.append(key).append("atomicEdt=").append(narrowTrial != null).append('\n')
            .append(key).append("nativeBefore=").append(nativeBefore.digest()).append('\n')
            .append(key).append("nativeRepeat=").append(nativeRepeat.digest()).append('\n')
            .append(key).append("cached=").append(cached.digest()).append('\n')
            .append(key).append("nativeAfter=").append(nativeAfter.digest()).append('\n')
            .append(key).append("pixels=").append(cached.pixels()).append('\n')
            .append(key).append("distinctPixelsAtLeast=").append(cached.distinctPixels()).append('\n');
        if (!nativeBefore.samePixels(nativeRepeat)) throw new IllegalStateException("native same-state repaint is unstable: " + stateName);
        if (!nativeRepeat.samePixels(cached) || !cached.samePixels(nativeAfter)) {
            throw new IllegalStateException("uniform cache pixel mismatch at fixed camera: " + stateName);
        }
        return nativeBefore.digest();
    }

    private FrameReadback captureNativeFrame() throws Exception {
        if (narrowTrial != null) return onEdt(() -> narrowTrial.capture(canvas));
        onEdt(() -> { uniformTrial.requestReadback(); canvas.repaint(); return null; });
        onEdt(() -> null);
        return onEdt(() -> uniformTrial.takeReadback());
    }

    private record HumanMetrics(
        int postedEvents, int deliveredEvents, int frames,
        long inputToFirstFrameNanos, long settleNanos,
        double frameRate, long frameIntervalP50, long frameIntervalP95, long frameIntervalP99,
        double maxLagNotches, double p95LagNotches, double maxZoomStepNotches,
        double multiNotchFrameRatio, int maxEventsPerFrame,
        double sustainableRatePerSecond, boolean sustainableLagOne,
        long idleResumeNanos) { }

    /**
     * Human-trace leg. Input is posted to the real system event queue at the
     * recorded pacing and never drained between events; the metrics describe what
     * a person sees: time to the first completed display, time to catch up after
     * the last input, frame pacing, per-frame zoom jumps and residual lag.
     */
    private void runHumanLeg(final int leg, final boolean enabled, final StringBuilder report,
                             final List<String> legFocusOwners, final List<String> legCanvasCursors,
                             final List<String> legWindowDigests) throws Exception {
        if (!"wheel".equals(humanTrace.kind())) {
            runHumanPointerLeg(leg, enabled, report, legFocusOwners, legCanvasCursors, legWindowDigests);
            return;
        }
        final HumanTrace.FrameTimeline frames = onEdt(() -> HumanTrace.FrameTimeline.attach(canvas, zoom::getText));
        final HumanTrace.WheelObserver observer = onEdt(() -> HumanTrace.WheelObserver.attach(canvas, zoom::getText));
        try {
            final int warmups = calibration ? 0 : 1;
            for (int replay = 0; replay < warmups; replay++) {
                Files.writeString(state.resolve("wheel-progress.txt"),
                    "stage=human-warmup\nleg=" + leg + "\nenabled=" + enabled
                        + "\nreplay=" + replay + "\n");
                resetHumanState(frames, observer);
                replayHumanTrace(1.0d);
                awaitHumanQuiescence(frames, observer, 0, 400L, 20_000L);
            }
            resetHumanState(frames, observer);
            final long idleResume = humanIdleResume ? measureHumanIdleResume(frames, observer) : -1L;
            resetHumanState(frames, observer);

            final Map<String, Long> modelBefore = snapshot();
            final Map<String, Long> clipBefore = statsSlot(CLIPMASK_STATS);
            final Map<String, Long> dedupeBefore = statsSlot(CLIPMASK_DEDUPE_STATS);
            final Map<String, Long> scissorBefore = statsSlot(CLIPMASK_SCISSOR_STATS);
            final Map<String, Long> meshBefore = statsSlot(MESH_SETUP_STATS);
            Files.writeString(state.resolve("wheel-progress.txt"),
                "stage=human-measuring\nleg=" + leg + "\nenabled=" + enabled + "\n");
            final List<HumanTrace.Posted> posted = replayHumanTrace(1.0d);
            final long replayEnd = System.nanoTime();
            final int wheelPosted = (int) posted.stream()
                .filter(entry -> entry.source().kind() == HumanTrace.Kind.WHEEL).count();
            awaitHumanQuiescence(frames, observer, wheelPosted, 400L, 25_000L);
            final Map<String, Long> modelAfter = snapshot();
            final List<HumanTrace.FrameTimeline.Frame> frameList = frames.snapshot();
            final List<HumanTrace.WheelObserver.Applied> applied = observer.snapshot();
            final HumanMetrics metrics = measureHumanWheel(posted, applied, frameList, idleResume);
            final String p = "leg." + leg + ".";
            report.append(p).append("enabled=").append(enabled).append('\n')
                .append(p).append("variant=").append(enabled ? "human-on" : "human-off").append('\n')
                .append(p).append("human.posted=").append(metrics.postedEvents()).append('\n')
                .append(p).append("human.delivered=").append(metrics.deliveredEvents()).append('\n')
                .append(p).append("human.frames=").append(metrics.frames()).append('\n')
                .append(p).append("human.inputToFirstFrameNanos=").append(metrics.inputToFirstFrameNanos()).append('\n')
                .append(p).append("human.settleNanos=").append(metrics.settleNanos()).append('\n')
                .append(p).append("human.frameRate=").append(metrics.frameRate()).append('\n')
                .append(p).append("human.frameIntervalP50Nanos=").append(metrics.frameIntervalP50()).append('\n')
                .append(p).append("human.frameIntervalP95Nanos=").append(metrics.frameIntervalP95()).append('\n')
                .append(p).append("human.frameIntervalP99Nanos=").append(metrics.frameIntervalP99()).append('\n')
                .append(p).append("human.maxLagNotches=").append(metrics.maxLagNotches()).append('\n')
                .append(p).append("human.p95LagNotches=").append(metrics.p95LagNotches()).append('\n')
                .append(p).append("human.maxZoomStepNotches=").append(metrics.maxZoomStepNotches()).append('\n')
                .append(p).append("human.multiNotchFrameRatio=").append(metrics.multiNotchFrameRatio()).append('\n')
                .append(p).append("human.maxEventsPerFrame=").append(metrics.maxEventsPerFrame()).append('\n')
                .append(p).append("human.sustainableRatePerSecond=").append(metrics.sustainableRatePerSecond()).append('\n')
                .append(p).append("human.sustainableLagOne=").append(metrics.sustainableLagOne()).append('\n')
                .append(p).append("human.idleResumeNanos=").append(metrics.idleResumeNanos()).append('\n')
                .append(p).append("human.replayWallNanos=").append(replayEnd - posted.get(0).postNanos()).append('\n');
            for (String key : modelAfter.keySet().stream().sorted().toList()) {
                if (key.equals("active") || key.equals("parameterCount") || key.endsWith("MaxNanos")) continue;
                report.append(p).append("modelUpdate.").append(key).append('=')
                    .append(modelAfter.get(key) - modelBefore.getOrDefault(key, 0L)).append('\n');
            }
            appendSlotDelta(report, p, "clipMask", statsSlot(CLIPMASK_STATS), clipBefore,
                java.util.Set.of("active"));
            appendSlotDelta(report, p, "clipMaskDedupe", statsSlot(CLIPMASK_DEDUPE_STATS), dedupeBefore,
                java.util.Set.of("active", "entries", "capacity", "peakEntries", "heldPeak"));
            appendSlotDelta(report, p, "clipMaskScissor", statsSlot(CLIPMASK_SCISSOR_STATS), scissorBefore,
                java.util.Set.of("active", "enabled", "savedPermille", "trackedTextures"));
            appendSlotDelta(report, p, "meshSetupElision", statsSlot(MESH_SETUP_STATS), meshBefore,
                java.util.Set.of("active", "generation", "trackedMeshes"));
            writeHumanRaw(leg, posted, applied, frameList);

            // Return to the shared baseline before the cross-leg screenshot gate.
            onEdt(() -> {
                restoreExactCamera();
                zoom.setText(originalZoom);
                forceParentPaint();
                return null;
            });
            Thread.sleep(150L);
            report.append(p).append("exactCameraBaseline=true\n");
            final String[] windowShot = onEdt(this::windowSnapshot);
            legWindowDigests.add(windowShot[0]);
            report.append(p).append("windowDigest=").append(windowShot[0]).append('\n')
                .append(p).append("windowDigestMethod=").append(windowShot[1]).append('\n')
                .append(p).append("windowDistinctColors=").append(windowShot[2]).append('\n');
            final String legFocusOwner = onEdt(CanvasWheelWorkload::focusOwnerFingerprint);
            final String legCanvasCursor = onEdt(this::canvasCursorFingerprint);
            legFocusOwners.add(legFocusOwner);
            legCanvasCursors.add(legCanvasCursor);
            report.append(p).append("focusOwner=").append(legFocusOwner).append('\n')
                .append(p).append("canvasCursor=").append(legCanvasCursor).append('\n');
            Files.writeString(state.resolve("wheel-progress.txt"),
                "stage=human-done\nleg=" + leg + "\nenabled=" + enabled + "\n");
            Files.writeString(state.resolve("wheel-benchmark.txt"), report);
        } finally {
            onEdt(() -> {
                frames.close();
                observer.close();
                return null;
            });
        }
    }

    /**
     * Pan and ArtMesh legs replay the same recorded gesture without per-event
     * drains. Pan must move only the camera; an ArtMesh drag must change exactly
     * the selected mesh and survive native Undo/Redo.
     */
    private void runHumanPointerLeg(final int leg, final boolean enabled, final StringBuilder report,
                                    final List<String> legFocusOwners, final List<String> legCanvasCursors,
                                    final List<String> legWindowDigests) throws Exception {
        final boolean mesh = "artmesh".equals(humanTrace.kind());
        final HumanTrace.FrameTimeline frames = onEdt(() -> HumanTrace.FrameTimeline.attach(canvas, zoom::getText));
        final HumanTrace.MouseObserver observer = onEdt(() -> HumanTrace.MouseObserver.attach(canvas));
        try {
            ensureCanvasFocus();
            final int warmups = calibration ? 0 : 1;
            for (int replay = 0; replay < warmups; replay++) {
                final NativeInteractionHost.Geometry before = onEdt(cameraHost::geometry);
                Files.writeString(state.resolve("wheel-progress.txt"),
                    "stage=human-warmup\nleg=" + leg + "\nenabled=" + enabled
                        + "\nkind=" + humanTrace.kind() + "\nreplay=" + replay + "\n");
                resetPointerState(frames, observer, mesh);
                replayHumanTrace(1.0d);
                awaitPointerQuiescence(frames, observer, 400L, 25_000L);
                if (mesh) undoPointerChanges(before);
                else restorePointerCamera();
            }
            resetPointerState(frames, observer, mesh);
            final NativeInteractionHost.Camera cameraBefore = onEdt(cameraHost::camera);
            final NativeInteractionHost.Geometry geometryBefore = onEdt(cameraHost::geometry);
            final String undoBefore = onEdt(cameraHost::undoState);
            final int undoPositionBefore = onEdt(cameraHost::undoPosition);
            final boolean modifiedBefore = onEdt(cameraHost::modified);
            final String actionBefore = mesh ? "not-required" : onEdt(cameraHost::actionState);
            final Map<String, Long> modelBefore = snapshot();
            final Map<String, Long> clipBefore = statsSlot(CLIPMASK_STATS);
            final Map<String, Long> dedupeBefore = statsSlot(CLIPMASK_DEDUPE_STATS);
            final Map<String, Long> scissorBefore = statsSlot(CLIPMASK_SCISSOR_STATS);
            final Map<String, Long> meshBefore = statsSlot(MESH_SETUP_STATS);
            Files.writeString(state.resolve("wheel-progress.txt"),
                "stage=human-measuring\nleg=" + leg + "\nenabled=" + enabled
                    + "\nkind=" + humanTrace.kind() + "\n");
            final List<HumanTrace.Posted> posted = replayHumanTrace(1.0d);
            final long replayEnd = System.nanoTime();
            awaitPointerQuiescence(frames, observer, 400L, 30_000L);
            final Map<String, Long> modelAfter = snapshot();
            final List<HumanTrace.FrameTimeline.Frame> frameList = frames.snapshot();
            final List<HumanTrace.MouseObserver.Moved> moved = observer.snapshot();
            final HumanMetrics metrics = measureHumanPointer(posted, moved, frameList);
            final String p = "leg." + leg + ".";
            report.append(p).append("enabled=").append(enabled).append('\n')
                .append(p).append("variant=").append(mesh
                    ? (enabled ? "human-artmesh-on" : "human-artmesh-off")
                    : (enabled ? "human-pan-on" : "human-pan-off")).append('\n')
                .append(p).append("human.kind=").append(humanTrace.kind()).append('\n')
                .append(p).append("human.posted=").append(metrics.postedEvents()).append('\n')
                .append(p).append("human.delivered=").append(metrics.deliveredEvents()).append('\n')
                .append(p).append("human.frames=").append(metrics.frames()).append('\n')
                .append(p).append("human.inputToFirstFrameNanos=").append(metrics.inputToFirstFrameNanos()).append('\n')
                .append(p).append("human.settleNanos=").append(metrics.settleNanos()).append('\n')
                .append(p).append("human.frameRate=").append(metrics.frameRate()).append('\n')
                .append(p).append("human.frameIntervalP50Nanos=").append(metrics.frameIntervalP50()).append('\n')
                .append(p).append("human.frameIntervalP95Nanos=").append(metrics.frameIntervalP95()).append('\n')
                .append(p).append("human.frameIntervalP99Nanos=").append(metrics.frameIntervalP99()).append('\n')
                .append(p).append("human.maxEventsPerFrame=").append(metrics.maxEventsPerFrame()).append('\n')
                .append(p).append("human.eventsPerFrameP95=").append(metrics.p95LagNotches()).append('\n')
                .append(p).append("human.sustainableRatePerSecond=").append(metrics.sustainableRatePerSecond()).append('\n')
                .append(p).append("human.sustainableLagOne=").append(metrics.sustainableLagOne()).append('\n')
                .append(p).append("human.replayWallNanos=").append(replayEnd - posted.get(0).postNanos()).append('\n');
            final int pointerPosted = countKind(posted, HumanTrace.Kind.MOUSE);
            int changedCount = 0;
            int movedVertices = 0;
            boolean wholeMeshMoved = true;
            boolean undoRestored = false;
            if (mesh) {
                if (!onEdt(cameraHost::adoptSelectedTarget)) {
                    final List<Point> candidates = onEdt(cameraHost::candidatePoints);
                    Files.writeString(state.resolve("human-artmesh-candidates.txt"),
                        "candidates=" + candidates + "\n");
                    throw new IllegalStateException("recorded ArtMesh drag did not select exactly one editable ArtMesh"
                        + "; candidates=" + candidates);
                }
                final int target = cameraHost.targetIndex();
                final NativeInteractionHost.Geometry movedGeometry = onEdt(cameraHost::geometry);
                final List<Integer> changed = NativeInteractionHost.changed(geometryBefore, movedGeometry);
                changedCount = changed.size();
                if (!changed.equals(List.of(target))) {
                    throw new IllegalStateException("ArtMesh drag changed meshes " + changed + " target=" + target);
                }
                movedVertices = movedCount(geometryBefore.calculated().get(target),
                    movedGeometry.calculated().get(target));
                wholeMeshMoved = NativeInteractionHost.wholeMeshChanged(geometryBefore, movedGeometry, target);
                if (movedVertices == 0) {
                    throw new IllegalStateException("ArtMesh drag did not move any target vertex");
                }
                // A human ArtMesh gesture can be a point edit (one vertex) or a
                // whole-mesh drag. Both are valid authoring input; record which
                // one the trace produced and keep the exact-one-mesh gate.
                Files.writeString(state.resolve("human-artmesh-geometry.txt"),
                    describeGeometryChange(geometryBefore, movedGeometry, target));
                if (!cameraBefore.equals(onEdt(cameraHost::camera))) {
                    throw new IllegalStateException("ArtMesh drag moved the camera");
                }
                onEdt(() -> { cameraHost.undo(); return null; });
                onEdt(() -> null);
                Thread.sleep(100L);
                undoRestored = NativeInteractionHost.changed(geometryBefore, onEdt(cameraHost::geometry)).isEmpty();
                if (!undoRestored) throw new IllegalStateException("native Undo did not restore ArtMesh geometry");
                onEdt(() -> { cameraHost.redo(); return null; });
                onEdt(() -> null);
                Thread.sleep(100L);
                if (!NativeInteractionHost.changed(movedGeometry, onEdt(cameraHost::geometry)).isEmpty()) {
                    throw new IllegalStateException("native Redo did not replay the ArtMesh drag");
                }
                onEdt(() -> { cameraHost.undo(); return null; });
                onEdt(() -> null);
                Thread.sleep(100L);
            } else {
                if (cameraBefore.equals(onEdt(cameraHost::camera))) {
                    throw new IllegalStateException("recorded pan did not move the camera");
                }
                if (!NativeInteractionHost.changed(geometryBefore, onEdt(cameraHost::geometry)).isEmpty()) {
                    throw new IllegalStateException("recorded pan changed mesh geometry");
                }
                if (!undoBefore.equals(onEdt(cameraHost::undoState))) {
                    throw new IllegalStateException("recorded pan changed the Undo state");
                }
                if (modifiedBefore != onEdt(cameraHost::modified)) {
                    throw new IllegalStateException("recorded pan changed the modified flag");
                }
                if (!actionBefore.equals(onEdt(cameraHost::actionState))) {
                    throw new IllegalStateException("recorded pan left the host action state");
                }
            }
            report.append(p).append("human.pointerPosted=").append(pointerPosted).append('\n')
                .append(p).append("human.cameraChanged=").append(!cameraBefore.equals(onEdt(cameraHost::camera))).append('\n')
                .append(p).append("human.changedMeshCount=").append(changedCount).append('\n')
                .append(p).append("human.movedVertexCount=").append(movedVertices).append('\n')
                .append(p).append("human.wholeMeshMoved=").append(wholeMeshMoved).append('\n')
                .append(p).append("human.undoRestored=").append(mesh ? undoRestored : "not-required").append('\n')
                .append(p).append("human.undoPositionBefore=").append(undoPositionBefore).append('\n')
                .append(p).append("human.undoPositionAfter=").append(onEdt(cameraHost::undoPosition)).append('\n')
                .append(p).append("human.modifiedBefore=").append(modifiedBefore).append('\n')
                .append(p).append("human.modifiedAfter=").append(onEdt(cameraHost::modified)).append('\n');
            for (String key : modelAfter.keySet().stream().sorted().toList()) {
                if (key.equals("active") || key.equals("parameterCount") || key.endsWith("MaxNanos")) continue;
                report.append(p).append("modelUpdate.").append(key).append('=')
                    .append(modelAfter.get(key) - modelBefore.getOrDefault(key, 0L)).append('\n');
            }
            appendSlotDelta(report, p, "clipMask", statsSlot(CLIPMASK_STATS), clipBefore,
                java.util.Set.of("active"));
            appendSlotDelta(report, p, "clipMaskDedupe", statsSlot(CLIPMASK_DEDUPE_STATS), dedupeBefore,
                java.util.Set.of("active", "entries", "capacity", "peakEntries", "heldPeak"));
            appendSlotDelta(report, p, "clipMaskScissor", statsSlot(CLIPMASK_SCISSOR_STATS), scissorBefore,
                java.util.Set.of("active", "enabled", "savedPermille", "trackedTextures"));
            appendSlotDelta(report, p, "meshSetupElision", statsSlot(MESH_SETUP_STATS), meshBefore,
                java.util.Set.of("active", "generation", "trackedMeshes"));
            writeHumanPointerRaw(leg, posted, moved, frameList);

            if (mesh) undoPointerChanges(geometryBefore);
            else restorePointerCamera();
            onEdt(() -> { cameraHost.restoreSelection(); return null; });
            onEdt(() -> {
                restoreExactCamera();
                zoom.setText(originalZoom);
                forceParentPaint();
                return null;
            });
            Thread.sleep(150L);
            final String[] windowShot = onEdt(this::windowSnapshot);
            legWindowDigests.add(windowShot[0]);
            report.append(p).append("exactCameraBaseline=true\n")
                .append(p).append("windowDigest=").append(windowShot[0]).append('\n')
                .append(p).append("windowDigestMethod=").append(windowShot[1]).append('\n')
                .append(p).append("windowDistinctColors=").append(windowShot[2]).append('\n');
            final String legFocusOwner = onEdt(CanvasWheelWorkload::focusOwnerFingerprint);
            final String legCanvasCursor = onEdt(this::canvasCursorFingerprint);
            legFocusOwners.add(legFocusOwner);
            legCanvasCursors.add(legCanvasCursor);
            report.append(p).append("focusOwner=").append(legFocusOwner).append('\n')
                .append(p).append("canvasCursor=").append(legCanvasCursor).append('\n');
            Files.writeString(state.resolve("wheel-progress.txt"),
                "stage=human-done\nleg=" + leg + "\nenabled=" + enabled
                    + "\nkind=" + humanTrace.kind() + "\n");
            Files.writeString(state.resolve("wheel-benchmark.txt"), report);
        } finally {
            onEdt(() -> {
                frames.close();
                observer.close();
                return null;
            });
        }
    }

    private static int countKind(final List<HumanTrace.Posted> posted, final HumanTrace.Kind kind) {
        int count = 0;
        for (HumanTrace.Posted entry : posted) if (entry.source().kind() == kind) count++;
        return count;
    }

    private void ensureCanvasFocus() throws Exception {
        onEdt(() -> {
            window.toFront();
            window.requestFocus();
            return null;
        });
        for (int attempt = 0; attempt < 40; attempt++) {
            if (onEdt(() -> NativeInteractionFocus.owns(canvas, window))) return;
            onEdt(() -> {
                canvas.requestFocusInWindow();
                return null;
            });
            Thread.sleep(50L);
        }
        throw new IllegalStateException("canvas focus was not acquired for the recorded pointer trace");
    }

    private void resetPointerState(final HumanTrace.FrameTimeline frames,
                                   final HumanTrace.MouseObserver observer,
                                   final boolean mesh) throws Exception {
        onEdt(() -> {
            if (mesh) {
                cameraHost.clearSelection();
            } else {
                restoreExactCamera();
            }
            return null;
        });
        Thread.sleep(150L);
        onEdt(() -> {
            frames.clear();
            observer.clear();
            return null;
        });
    }

    private void restorePointerCamera() throws Exception {
        onEdt(() -> {
            restoreExactCamera();
            return null;
        });
    }

    private void undoPointerChanges(final NativeInteractionHost.Geometry baseline) throws Exception {
        for (int attempt = 0; attempt < 16; attempt++) {
            if (NativeInteractionHost.changed(baseline, onEdt(cameraHost::geometry)).isEmpty()) return;
            onEdt(() -> {
                cameraHost.undo();
                return null;
            });
            onEdt(() -> null);
            Thread.sleep(80L);
        }
        if (!NativeInteractionHost.changed(baseline, onEdt(cameraHost::geometry)).isEmpty()) {
            throw new IllegalStateException("could not undo the recorded ArtMesh gesture");
        }
    }

    private void awaitPointerQuiescence(final HumanTrace.FrameTimeline frames,
                                        final HumanTrace.MouseObserver observer,
                                        final long quietMillis, final long timeoutMillis) throws Exception {
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        long lastCount = -1L;
        long lastChange = System.nanoTime();
        while (System.nanoTime() < deadline) {
            final long count = frames.count();
            if (count != lastCount) {
                lastCount = count;
                lastChange = System.nanoTime();
            }
            if (count > 0L && System.nanoTime() - lastChange >= quietMillis * 1_000_000L) return;
            Thread.sleep(10L);
        }
        throw new IllegalStateException("human pointer workload did not quiesce: frames=" + frames.count()
            + " delivered=" + observer.snapshot().size());
    }

    private static String describeGeometryChange(final NativeInteractionHost.Geometry before,
                                                 final NativeInteractionHost.Geometry after,
                                                 final int target) {
        final StringBuilder text = new StringBuilder("target=").append(target).append('\n');
        text.append("interpolatedMoved=").append(movedCount(before.interpolated().get(target),
            after.interpolated().get(target))).append('/')
            .append(before.interpolated().get(target).length / 2).append('\n');
        text.append("sourceMoved=").append(movedCount(before.source().get(target),
            after.source().get(target))).append('/')
            .append(before.source().get(target).length / 2).append('\n');
        text.append("calculatedMoved=").append(movedCount(before.calculated().get(target),
            after.calculated().get(target))).append('/')
            .append(before.calculated().get(target).length / 2).append('\n');
        for (int i = 0; i < before.keyforms().get(target).size(); i++) {
            text.append("keyform").append(i).append("Moved=")
                .append(movedCount(before.keyforms().get(target).get(i),
                    after.keyforms().get(target).get(i))).append('/')
                .append(before.keyforms().get(target).get(i).length / 2).append('\n');
        }
        return text.toString();
    }

    private static int movedCount(final float[] before, final float[] after) {
        int moved = 0;
        for (int i = 0; i + 1 < before.length; i += 2) {
            if (Float.floatToRawIntBits(before[i]) != Float.floatToRawIntBits(after[i])
                || Float.floatToRawIntBits(before[i + 1]) != Float.floatToRawIntBits(after[i + 1])) {
                moved++;
            }
        }
        return moved;
    }

    private HumanMetrics measureHumanPointer(final List<HumanTrace.Posted> posted,
                                             final List<HumanTrace.MouseObserver.Moved> moved,
                                             final List<HumanTrace.FrameTimeline.Frame> frames) {
        final List<HumanTrace.Posted> pointer = new ArrayList<>();
        for (HumanTrace.Posted entry : posted) {
            if (entry.source().kind() == HumanTrace.Kind.MOUSE) pointer.add(entry);
        }
        if (pointer.isEmpty() || frames.isEmpty()) {
            throw new IllegalStateException("human pointer trace produced no pointer/display observation");
        }
        final long firstPost = pointer.get(0).postNanos();
        final long lastPost = pointer.get(pointer.size() - 1).postNanos();
        final List<HumanTrace.FrameTimeline.Frame> valid = new ArrayList<>();
        for (HumanTrace.FrameTimeline.Frame frame : frames) {
            if (frame.nanoTime() >= firstPost) valid.add(frame);
        }
        if (valid.isEmpty()) throw new IllegalStateException("human pointer trace produced no display");
        final HumanTrace.FrameTimeline.Frame firstFrame = valid.get(0);
        HumanTrace.FrameTimeline.Frame settleFrame = valid.get(valid.size() - 1);
        for (HumanTrace.FrameTimeline.Frame frame : valid) {
            if (frame.nanoTime() >= lastPost) settleFrame = frame;
        }
        final List<HumanTrace.FrameTimeline.Frame> window = new ArrayList<>();
        for (HumanTrace.FrameTimeline.Frame frame : valid) {
            if (frame.nanoTime() >= firstFrame.nanoTime() && frame.nanoTime() <= settleFrame.nanoTime()) {
                window.add(frame);
            }
        }
        final long[] intervals = new long[Math.max(0, window.size() - 1)];
        for (int i = 1; i < window.size(); i++) {
            intervals[i - 1] = Math.max(0L, window.get(i).nanoTime() - window.get(i - 1).nanoTime());
        }
        final long[] sorted = intervals.clone();
        Arrays.sort(sorted);
        final int[] eventsPerFrame = new int[window.size()];
        for (int i = 1; i < window.size(); i++) {
            int count = 0;
            for (HumanTrace.MouseObserver.Moved entry : moved) {
                if (entry.nanoTime() > window.get(i - 1).nanoTime()
                    && entry.nanoTime() <= window.get(i).nanoTime()) count++;
            }
            eventsPerFrame[i] = count;
        }
        int maxEventsPerFrame = 0;
        for (int count : eventsPerFrame) maxEventsPerFrame = Math.max(maxEventsPerFrame, count);
        final double frameSeconds = Math.max(1e-9d, (settleFrame.nanoTime() - firstFrame.nanoTime())
            / 1_000_000_000.0d);
        final double elapsedSeconds = Math.max(1e-9d, (lastPost - firstPost)
            / 1_000_000_000.0d);
        return new HumanMetrics(pointer.size(), moved.size(), window.size(),
            Math.max(0L, firstFrame.nanoTime() - firstPost),
            Math.max(0L, settleFrame.nanoTime() - lastPost),
            window.size() / frameSeconds,
            sorted.length == 0 ? 0L : percentile(sorted, 0.50d),
            sorted.length == 0 ? 0L : percentile(sorted, 0.95d),
            sorted.length == 0 ? 0L : percentile(sorted, 0.99d),
            0d, eventsPerFrameP95(eventsPerFrame), 0d, 0d, maxEventsPerFrame,
            pointer.size() / elapsedSeconds, maxEventsPerFrame <= 2, -1L);
    }

    private static double eventsPerFrameP95(final int[] counts) {
        if (counts.length == 0) return 0d;
        final long[] sorted = new long[counts.length];
        for (int i = 0; i < counts.length; i++) sorted[i] = counts[i];
        Arrays.sort(sorted);
        return percentile(sorted, 0.95d);
    }

    private void writeHumanPointerRaw(final int leg, final List<HumanTrace.Posted> posted,
                                      final List<HumanTrace.MouseObserver.Moved> moved,
                                      final List<HumanTrace.FrameTimeline.Frame> frames) throws Exception {
        final StringBuilder events = new StringBuilder(
            "index\tpostNanos\ttargetNanos\tdeliveredNanos\tid\tx\ty\tmodifiers\n");
        int row = 0;
        for (HumanTrace.Posted entry : posted) {
            if (entry.source().kind() != HumanTrace.Kind.MOUSE) continue;
            final HumanTrace.MouseObserver.Moved observed = row < moved.size() ? moved.get(row) : null;
            events.append(row).append('\t')
                .append(entry.postNanos()).append('\t')
                .append(entry.targetNanos()).append('\t')
                .append(observed == null ? "-1" : observed.nanoTime()).append('\t')
                .append(entry.source().id()).append('\t')
                .append(entry.source().x()).append('\t')
                .append(entry.source().y()).append('\t')
                .append(entry.source().modifiers()).append('\n');
            row++;
        }
        Files.writeString(state.resolve("human-events-leg-" + leg + ".tsv"), events.toString());
        final StringBuilder timeline = new StringBuilder("index\tnanoTime\tzoom\n");
        for (int i = 0; i < frames.size(); i++) {
            timeline.append(i).append('\t').append(frames.get(i).nanoTime()).append('\t')
                .append(frames.get(i).zoom()).append('\n');
        }
        Files.writeString(state.resolve("human-frames-leg-" + leg + ".tsv"), timeline.toString());
    }

    private static String dumpUiTables(final Frame window) {
        final StringBuilder text = new StringBuilder();
        collectTables(window, text, 0);
        return text.toString();
    }

    private static void collectTables(final Component component, final StringBuilder text, final int depth) {
        if (component == null || depth > 40) return;
        if (component instanceof javax.swing.JTable table) {
            text.append("JTable class=").append(table.getClass().getName())
                .append(" rows=").append(table.getRowCount())
                .append(" cols=").append(table.getColumnCount())
                .append(" bounds=").append(table.getBounds()).append('\n');
            for (int column = 0; column < Math.min(8, table.getColumnCount()); column++) {
                text.append("  column").append(column).append('=')
                    .append(table.getColumnName(column)).append('\n');
            }
            for (int row = 0; row < Math.min(6, table.getRowCount()); row++) {
                text.append("  row").append(row).append('=')
                    .append(String.valueOf(table.getValueAt(row, 0))).append('\n');
            }
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) collectTables(child, text, depth + 1);
        }
    }

    private List<HumanTrace.Posted> replayHumanTrace(final double speed) throws Exception {
        final java.util.concurrent.atomic.AtomicReference<List<HumanTrace.Posted>> result =
            new java.util.concurrent.atomic.AtomicReference<>(List.of());
        final Thread thread = new Thread(() -> result.set(HumanTrace.replay(humanTrace, canvas,
            50_000_000L, speed, null, null)), "turboism-human-replay");
        thread.setDaemon(true);
        thread.start();
        thread.join(180_000L);
        if (thread.isAlive()) throw new IllegalStateException("human replay did not finish");
        if (result.get().isEmpty()) throw new IllegalStateException("human replay posted no events");
        return result.get();
    }

    private void resetHumanState(final HumanTrace.FrameTimeline frames,
                                 final HumanTrace.WheelObserver observer) throws Exception {
        onEdt(() -> {
            restoreExactCamera();
            zoom.setText(originalZoom);
            forceParentPaint();
            return null;
        });
        Thread.sleep(150L);
        onEdt(() -> {
            frames.clear();
            observer.clear();
            return null;
        });
    }

    private void forceParentPaint() {
        final Component parent = canvas.getParent();
        if (parent instanceof JComponent jparent && parent.getWidth() > 0 && parent.getHeight() > 0) {
            jparent.paintImmediately(0, 0, parent.getWidth(), parent.getHeight());
        } else {
            canvas.paintImmediately(0, 0, width, height);
        }
    }

    private void awaitHumanQuiescence(final HumanTrace.FrameTimeline frames,
                                      final HumanTrace.WheelObserver observer,
                                      final int expectedDelivered,
                                      final long quietMillis, final long timeoutMillis) throws Exception {
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        long lastCount = -1L;
        long lastChange = System.nanoTime();
        while (System.nanoTime() < deadline) {
            final long count = frames.count();
            if (count != lastCount) {
                lastCount = count;
                lastChange = System.nanoTime();
            }
            final int delivered = observer == null ? Integer.MAX_VALUE : observer.snapshot().size();
            if (count > 0L && delivered >= expectedDelivered
                && System.nanoTime() - lastChange >= quietMillis * 1_000_000L) return;
            Thread.sleep(10L);
        }
        throw new IllegalStateException("human workload did not quiesce: frames=" + frames.count()
            + " delivered=" + (observer == null ? "n/a" : observer.snapshot().size())
            + " expected=" + expectedDelivered);
    }

    private long measureHumanIdleResume(final HumanTrace.FrameTimeline frames,
                                        final HumanTrace.WheelObserver observer) throws Exception {
        onEdt(() -> {
            restoreExactCamera();
            zoom.setText(originalZoom);
            return null;
        });
        Thread.sleep(1_500L);
        awaitHumanQuiescence(frames, observer, 0, 150L, 3_000L);
        onEdt(() -> {
            frames.clear();
            observer.clear();
            return null;
        });
        final Point origin = onEdt(canvas::getLocationOnScreen);
        final MouseWheelEvent event = new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(), 0, width / 2, height / 2,
            origin.x + width / 2, origin.y + height / 2, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1);
        final long post = System.nanoTime();
        java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(event);
        awaitHumanQuiescence(frames, observer, 1, 300L, 6_000L);
        final List<HumanTrace.FrameTimeline.Frame> list = frames.snapshot();
        if (list.isEmpty()) throw new IllegalStateException("idle resume produced no display");
        return Math.max(0L, list.get(0).nanoTime() - post);
    }

    private HumanMetrics measureHumanWheel(final List<HumanTrace.Posted> posted,
                                           final List<HumanTrace.WheelObserver.Applied> applied,
                                           final List<HumanTrace.FrameTimeline.Frame> frames,
                                           final long idleResumeNanos) {
        final List<HumanTrace.Posted> wheels = new ArrayList<>();
        for (HumanTrace.Posted entry : posted) {
            if (entry.source().kind() == HumanTrace.Kind.WHEEL) wheels.add(entry);
        }
        if (wheels.isEmpty() || applied.isEmpty() || frames.isEmpty()) {
            throw new IllegalStateException("human wheel trace produced no wheel/display observation");
        }
        final long firstPost = wheels.get(0).postNanos();
        final long lastPost = wheels.get(wheels.size() - 1).postNanos();
        final List<HumanTrace.FrameTimeline.Frame> valid = new ArrayList<>();
        for (HumanTrace.FrameTimeline.Frame frame : frames) {
            if (frame.nanoTime() >= firstPost && !Double.isNaN(parseZoom(frame.zoom()))) valid.add(frame);
        }
        if (valid.isEmpty()) throw new IllegalStateException("human wheel trace produced no parseable display");
        HumanTrace.FrameTimeline.Frame firstFrame = valid.get(0);
        final String finalZoom = applied.get(applied.size() - 1).zoom();
        final double finalValue = parseZoom(finalZoom);
        HumanTrace.FrameTimeline.Frame settleFrame = valid.get(valid.size() - 1);
        for (HumanTrace.FrameTimeline.Frame frame : valid) {
            if (frame.nanoTime() >= lastPost && Math.abs(parseZoom(frame.zoom()) - finalValue) < 0.051d) {
                settleFrame = frame;
            }
        }
        final List<HumanTrace.FrameTimeline.Frame> window = new ArrayList<>();
        for (HumanTrace.FrameTimeline.Frame frame : valid) {
            if (frame.nanoTime() >= firstFrame.nanoTime() && frame.nanoTime() <= settleFrame.nanoTime()) {
                window.add(frame);
            }
        }
        final long[] intervals = new long[Math.max(0, window.size() - 1)];
        for (int i = 1; i < window.size(); i++) {
            intervals[i - 1] = Math.max(0L, window.get(i).nanoTime() - window.get(i - 1).nanoTime());
        }
        final long[] sorted = intervals.clone();
        Arrays.sort(sorted);
        final double[] lags = new double[window.size()];
        final int[] eventsPerFrame = new int[window.size()];
        // Backlog is input the user has already produced but the screen has not
        // applied yet. It is not the same as "latest applied zoom vs frame zoom",
        // which is always zero immediately before a paint.
        int postedIndex = 0;
        int appliedIndex = 0;
        int postedRotation = 0;
        int appliedRotation = 0;
        for (int i = 0; i < window.size(); i++) {
            final HumanTrace.FrameTimeline.Frame frame = window.get(i);
            while (postedIndex < wheels.size()
                && wheels.get(postedIndex).postNanos() <= frame.nanoTime()) {
                postedRotation += wheels.get(postedIndex).source().wheelRotation();
                postedIndex++;
            }
            while (appliedIndex < applied.size()
                && applied.get(appliedIndex).nanoTime() <= frame.nanoTime()) {
                appliedRotation += applied.get(appliedIndex).rotation();
                appliedIndex++;
            }
            lags[i] = Math.abs(postedRotation - appliedRotation);
            if (i > 0) {
                int count = 0;
                for (HumanTrace.WheelObserver.Applied entry : applied) {
                    if (entry.nanoTime() > window.get(i - 1).nanoTime()
                        && entry.nanoTime() <= frame.nanoTime()) count++;
                }
                eventsPerFrame[i] = count;
            }
        }
        Arrays.sort(lags);
        double maxZoomStep = 0d;
        int multiNotch = 0;
        for (int i = 1; i < window.size(); i++) {
            final double before = parseZoom(window.get(i - 1).zoom());
            final double after = parseZoom(window.get(i).zoom());
            if (before > 0d && after > before) {
                final double notches = Math.log(after / before) / Math.log(Math.sqrt(2.0d));
                maxZoomStep = Math.max(maxZoomStep, notches);
                if (notches > 1.5d) multiNotch++;
            }
        }
        int maxEventsPerFrame = 0;
        for (int count : eventsPerFrame) maxEventsPerFrame = Math.max(maxEventsPerFrame, count);
        final double maxLag = lags.length == 0 ? 0d : lags[lags.length - 1];
        final double p95Lag = lags.length == 0 ? 0d : percentileDouble(lags, 0.95d);
        final double elapsedSeconds = Math.max(1e-9d, (lastPost - firstPost) / 1_000_000_000.0d);
        final double frameSeconds = Math.max(1e-9d, (settleFrame.nanoTime() - firstFrame.nanoTime())
            / 1_000_000_000.0d);
        return new HumanMetrics(wheels.size(), applied.size(), window.size(),
            Math.max(0L, firstFrame.nanoTime() - firstPost),
            Math.max(0L, settleFrame.nanoTime() - lastPost),
            window.size() / frameSeconds,
            sorted.length == 0 ? 0L : percentile(sorted, 0.50d),
            sorted.length == 0 ? 0L : percentile(sorted, 0.95d),
            sorted.length == 0 ? 0L : percentile(sorted, 0.99d),
            maxLag, p95Lag, maxZoomStep,
            window.size() <= 1 ? 0d : (double) multiNotch / (window.size() - 1),
            maxEventsPerFrame,
            wheels.size() / elapsedSeconds, maxLag <= 1.0d, idleResumeNanos);
    }

    private static double percentileDouble(final double[] sorted, final double fraction) {
        if (sorted.length == 0) return 0d;
        final int index = Math.max(0, Math.min(sorted.length - 1,
            (int) Math.ceil(sorted.length * fraction) - 1));
        return sorted[index];
    }

    private static double parseZoom(final String text) {
        if (text == null) return Double.NaN;
        final String value = text.strip().replace("%", "").replace(',', '.');
        if (value.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static void appendSlotDelta(final StringBuilder report, final String legPrefix,
                                        final String name, final Map<String, Long> after,
                                        final Map<String, Long> before,
                                        final java.util.Set<String> gauges) {
        for (String key : after.keySet().stream().sorted().toList()) {
            if (key.equals("armed") || key.equals("savedPermille")) continue;
            report.append(legPrefix).append(name).append('.').append(key).append('=')
                .append(gauges.contains(key) ? after.get(key)
                    : after.get(key) - before.getOrDefault(key, 0L)).append('\n');
        }
    }

    private void writeHumanRaw(final int leg, final List<HumanTrace.Posted> posted,
                               final List<HumanTrace.WheelObserver.Applied> applied,
                               final List<HumanTrace.FrameTimeline.Frame> frames) throws Exception {
        final StringBuilder events = new StringBuilder(
            "index\tpostNanos\ttargetNanos\tdeliveredNanos\tzoomAfter\trotation\tscrollAmount\n");
        for (int i = 0; i < posted.size(); i++) {
            final HumanTrace.Posted entry = posted.get(i);
            final HumanTrace.WheelObserver.Applied observed = i < applied.size() ? applied.get(i) : null;
            events.append(i).append('\t')
                .append(entry.postNanos()).append('\t')
                .append(entry.targetNanos()).append('\t')
                .append(observed == null ? "-1" : observed.nanoTime()).append('\t')
                .append(observed == null ? "" : observed.zoom()).append('\t')
                .append(entry.source().wheelRotation()).append('\t')
                .append(entry.source().scrollAmount()).append('\n');
        }
        Files.writeString(state.resolve("human-events-leg-" + leg + ".tsv"), events.toString());
        final StringBuilder timeline = new StringBuilder("index\tnanoTime\tzoom\n");
        for (int i = 0; i < frames.size(); i++) {
            timeline.append(i).append('\t').append(frames.get(i).nanoTime()).append('\t')
                .append(frames.get(i).zoom()).append('\n');
        }
        Files.writeString(state.resolve("human-frames-leg-" + leg + ".tsv"), timeline.toString());
    }

    private void runPair(final long[] timings, final int offset) throws Exception {
        boolean changed = false;
        try {
            final Observation event = dispatchAndDrain(-1);
            final String after = event.zoom();
            final long elapsed = event.nanos();
            changed = true;
            if (originalZoom.equals(after)) throw new IllegalStateException("wheel did not change displayed zoom");
            if (timings != null) { timings[offset] = elapsed; recordPhases(event); }
        } finally {
            if (changed) {
                final Observation event = dispatchAndDrain(1);
                final String restored = event.zoom();
                final long elapsed = event.nanos();
                if (!originalZoom.equals(restored)) {
                    throw new IllegalStateException("inverse wheel failed to restore zoom: " + restored);
                }
                if (timings != null) { timings[offset + 1] = elapsed; recordPhases(event); }
            }
        }
    }

    private String capturePixels(String diagnosticName) throws Exception {
        if (uniformTrial != null || narrowTrial != null) {
            FrameReadback frame;
            if (narrowTrial != null && diagnosticName != null) {
                final String[] metadata = {null};
                frame = onEdt(() -> {
                    String before = viewMetadata.call();
                    FrameReadback captured = narrowTrial.capture(canvas);
                    metadata[0] = viewMetadata.call();
                    if (!before.equals(metadata[0])) {
                        throw new IllegalStateException("camera/viewport changed during diagnostic capture");
                    }
                    return captured;
                });
                Files.writeString(state.resolve("wheel-pixels-" + diagnosticName + ".properties"), metadata[0]);
                frame.writeArgbPng(state.resolve("wheel-pixels-" + diagnosticName + ".png"));
            } else frame = captureNativeFrame();
            return frame.digest();
        }
        final java.awt.Rectangle bounds = onEdt(() -> {
            final Point point = canvas.getLocationOnScreen();
            return new java.awt.Rectangle(point.x, point.y, width, height);
        });
        final java.awt.image.BufferedImage image = new java.awt.Robot().createScreenCapture(bounds);
        final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        final int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        final java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(pixels.length * Integer.BYTES);
        bytes.asIntBuffer().put(pixels);
        digest.update(bytes);
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private record Observation(String zoom, long nanos, long queueNanos, long handlerNanos,
                               long repaintBarrierNanos, long resumeNanos) { }
    private record Handler(String zoom, long start, long end) { }
    private record Barrier(long updates, long ended) { }

    private void recordPhases(final Observation event) {
        measuredQueueNanos += event.queueNanos();
        measuredHandlerNanos += event.handlerNanos();
        measuredRepaintBarrierNanos += event.repaintBarrierNanos();
        measuredResumeNanos += event.resumeNanos();
    }

    private Observation dispatchAndDrain(final int direction) throws Exception {
        final long before = onEdt(CanvasWheelWorkload::renderUpdates);
        final long started = System.nanoTime();
        final Handler handler = onEdt(() -> {
            final long start = System.nanoTime();
            final String value = wheel(direction);
            return new Handler(value, start, System.nanoTime());
        });
        final Barrier barrier = onEdt(() -> new Barrier(renderUpdates(), System.nanoTime()));
        final long ended = System.nanoTime();
        if (barrier.updates() <= before) throw new IllegalStateException("wheel did not produce a native repaint before the EDT barrier");
        return new Observation(handler.zoom(), ended - started, handler.start() - started,
            handler.end() - handler.start(), barrier.ended() - handler.end(), ended - barrier.ended());
    }

    private static long renderUpdates() {
        final Map<String, Long> counters = snapshot();
        return counters.getOrDefault("full", 0L) + counters.getOrDefault("skipped", 0L);
    }

    private String wheel(final int direction) {
        if (!window.isShowing() || !window.getTitle().contains(fixture)
            || !canvas.isShowing() || SwingUtilities.getWindowAncestor(canvas) != window
            || canvas.getWidth() != width || canvas.getHeight() != height) {
            throw new IllegalStateException("camera workload identity or dimensions changed");
        }
        final int x = width / 2, y = height / 2;
        final Point origin = canvas.getLocationOnScreen();
        canvas.dispatchEvent(new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(), 0, x, y, origin.x + x, origin.y + y, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, direction));
        return zoom.getText();
    }

    private void discover() {
        final List<Frame> windows = new ArrayList<>();
        for (Window candidate : Window.getWindows()) {
            if (candidate instanceof Frame frame && frame.isShowing() && frame.getTitle().contains(fixture)) windows.add(frame);
        }
        if (windows.size() != 1) throw new IllegalStateException("fixture window count=" + windows.size());
        window = windows.get(0);
        final List<Component> panels = new ArrayList<>();
        collect(window, panels, "com.jogamp.opengl.awt.GLJPanel", 0);
        if (panels.size() != 1 || !(panels.get(0) instanceof JComponent panel)) {
            throw new IllegalStateException("unique GLJPanel not found");
        }
        canvas = panel;
        originalCanvasBuffering = canvas.isDoubleBuffered();
        repaintManager = javax.swing.RepaintManager.currentManager(canvas);
        originalSwingBuffering = repaintManager.isDoubleBufferingEnabled();
        if (Arrays.stream(canvas.getMouseWheelListeners())
            .noneMatch(listener -> listener.getClass().getName().equals("com.live2d.ui.m$b"))) {
            throw new IllegalStateException("reviewed native wheel listener missing");
        }
        final Container area = canvas.getParent().getParent();
        final List<JLabel> percentages = new ArrayList<>();
        labels(area, percentages, true, 0);
        if (percentages.size() != 1) throw new IllegalStateException("ambiguous percentage label");
        final JLabel percent = percentages.get(0);
        final Component[] siblings = percent.getParent().getComponents();
        int index = -1;
        for (int i = 0; i < siblings.length; i++) if (siblings[i] == percent) index = i;
        if (index < 1) throw new IllegalStateException("zoom label predecessor absent");
        final List<JLabel> values = new ArrayList<>();
        labels(siblings[index - 1], values, false, 0);
        if (values.size() != 1) throw new IllegalStateException("zoom value label ambiguous");
        zoom = values.get(0);
        originalZoom = zoom.getText();
        if (!originalZoom.matches("[0-9]+(?:[.,][0-9]+)?")) throw new IllegalStateException("invalid zoom value");
        width = canvas.getWidth();
        height = canvas.getHeight();
        if (width < 64 || height < 64) throw new IllegalStateException("canvas too small");
    }

    private static void collect(final Component c, final List<Component> out, final String type, final int depth) {
        if (depth > 32) throw new IllegalStateException("UI depth exceeded");
        if (!c.isShowing()) return;
        if (c.getClass().getName().equals(type)) out.add(c);
        if (c instanceof Container container) for (Component child : container.getComponents()) collect(child, out, type, depth + 1);
    }

    private static void labels(final Component c, final List<JLabel> out, final boolean percent, final int depth) {
        if (depth > 32) throw new IllegalStateException("UI depth exceeded");
        if (!c.isShowing()) return;
        if (c instanceof JLabel label && (!percent || "%".equals(label.getText()))) out.add(label);
        if (c instanceof Container container) for (Component child : container.getComponents()) labels(child, out, percent, depth + 1);
    }

    private void awaitCanvasReady(final java.util.concurrent.Callable<Void> action) throws Exception {
        final FutureTask<Void> task = new FutureTask<>(action);
        EventQueue.invokeLater(task);
        final StringBuilder traces = new StringBuilder();
        final long start = System.nanoTime();
        try {
            for (int interval = 1; interval <= 18; interval++) {
                try {
                    task.get(10L, TimeUnit.SECONDS);
                    return;
                } catch (java.util.concurrent.TimeoutException notReady) {
                    // This sampler stops before warm-up and never runs during measured legs.
                    traces.append("elapsedNanos=").append(System.nanoTime() - start).append('\n');
                    final var bean = java.lang.management.ManagementFactory.getThreadMXBean();
                    for (final var thread : bean.dumpAllThreads(true, true)) {
                        if (thread == null) continue;
                        traces.append(thread.getThreadName()).append(" state=")
                            .append(thread.getThreadState()).append(" lock=")
                            .append(thread.getLockName()).append(" owner=")
                            .append(thread.getLockOwnerName()).append('\n');
                        final StackTraceElement[] stack = thread.getStackTrace();
                        for (int i = 0; i < Math.min(64, stack.length); i++) {
                            traces.append("  at ").append(stack[i]).append('\n');
                        }
                    }
                    Files.writeString(state.resolve("wheel-readiness-threads.txt"), traces);
                    if (interval == 18) throw notReady;
                }
            }
        } finally {
            if (!task.isDone()) task.cancel(false);
        }
    }

    private static <T> T onEdt(final java.util.concurrent.Callable<T> action) throws Exception {
        return onEdt(action, 10L);
    }

    private static <T> T onEdt(final java.util.concurrent.Callable<T> action,
                               final long timeoutSeconds) throws Exception {
        final FutureTask<T> task = new FutureTask<>(action);
        EventQueue.invokeLater(task);
        try { return task.get(timeoutSeconds, TimeUnit.SECONDS); }
        catch (Exception failure) { task.cancel(false); throw failure; }
    }

    private static Map<String, Long> snapshot() {
        final Object callback = System.getProperties().get(STATS);
        if (!(callback instanceof Supplier<?> supplier) || !(supplier.get() instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("installed model-update statistics absent");
        }
        final Map<String, Long> result = new java.util.HashMap<>();
        raw.forEach((k, v) -> { if (k instanceof String key && v instanceof Number value) result.put(key, value.longValue()); });
        return result;
    }

    private static Map<String, Long> uploadElisionStats() {
        return statsSlot(ELISION_STATS);
    }

    private static Map<String, Long> stateElisionStats() {
        return statsSlot(STATE_STATS);
    }

    private static Map<String, Long> statsSlot(final String property) {
        final Object callback = System.getProperties().get(property);
        if (!(callback instanceof Supplier<?> supplier) || !(supplier.get() instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        final Map<String, Long> result = new java.util.HashMap<>();
        raw.forEach((k, v) -> { if (k instanceof String key && v instanceof Number value) result.put(key, value.longValue()); });
        return result;
    }

    /** Arms/disarms the input-path elision bridge for the current leg. */
    private static void setInputPathArmed(final boolean enabled) {
        final Object gate = System.getProperties().get(INPUTPATH_GATE);
        if (enabled && !(gate instanceof java.util.function.Consumer)) {
            throw new IllegalStateException("input path elision gate absent");
        }
        if (gate instanceof java.util.function.Consumer consumer) {
            consumer.accept(enabled);
        }
    }

    private static Map<String, Long> inputPathStats() {
        final Object callback = System.getProperties().get(INPUTPATH_STATS);
        if (!(callback instanceof Supplier<?> supplier) || !(supplier.get() instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        final Map<String, Long> result = new java.util.HashMap<>();
        raw.forEach((k, v) -> { if (k instanceof String key && v instanceof Number value) result.put(key, value.longValue()); });
        return result;
    }

    /** Arms/disarms the canvas-composite elision bridge for the current leg. */
    private static void setCompositeArmed(final boolean enabled) {
        final Object gate = System.getProperties().get(COMPOSITE_GATE);
        if (enabled && !(gate instanceof java.util.function.Consumer)) {
            throw new IllegalStateException("canvas composite elision gate absent");
        }
        if (gate instanceof java.util.function.Consumer consumer) {
            consumer.accept(enabled);
        }
    }

    private static Map<String, Long> compositeStats() {
        final Object callback = System.getProperties().get(COMPOSITE_STATS);
        if (!(callback instanceof Supplier<?> supplier) || !(supplier.get() instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        final Map<String, Long> result = new java.util.HashMap<>();
        raw.forEach((k, v) -> { if (k instanceof String key && v instanceof Number value) result.put(key, value.longValue()); });
        return result;
    }

    private void restoreExactCamera() throws Exception {
        cameraHost.restoreCamera(cameraBaseline, cameraTransformBaseline);
        canvas.paintImmediately(0, 0, width, height);
        java.awt.Toolkit.getDefaultToolkit().sync();
        final String restoredMetadata = cameraHost.captureMetadata();
        if (!cameraMetadataBaseline.equals(restoredMetadata)) {
            Files.writeString(state.resolve("camera-restoration-failed.txt"), restoredMetadata);
            throw new IllegalStateException("exact camera/viewport restoration failed");
        }
    }

    /**
     * Window-level screenshot of the canvas and its parent region. Returns
     * {@code {digest, method}}: {@code robot} captures real on-screen pixels
     * (preferred — proves the Wine/GDI composite result); when Robot is
     * unavailable the fallback re-renders the canvas parent tree into a
     * BufferedImage via {@code printAll} ({@code printAll} proves the Swing
     * paint result but cannot observe the actual screen blit).
     */
    private String[] windowSnapshot() {
        final Component parent = canvas.getParent() != null ? canvas.getParent() : canvas;
        try {
            final java.awt.Rectangle bounds =
                new java.awt.Rectangle(parent.getLocationOnScreen(), parent.getSize());
            final java.awt.image.BufferedImage image =
                new java.awt.Robot().createScreenCapture(bounds);
            return imageResult(image, "robot");
        } catch (Throwable robotFailure) {
            final int w = Math.max(1, parent.getWidth());
            final int h = Math.max(1, parent.getHeight());
            final java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            final java.awt.Graphics2D g = image.createGraphics();
            try {
                parent.printAll(g);
            } finally {
                g.dispose();
            }
            return imageResult(image, "printAll");
        }
    }

    private static String[] imageResult(final java.awt.image.BufferedImage image,
                                        final String method) {
        try {
            final java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");
            final java.util.HashSet<Integer> colors = new java.util.HashSet<>();
            final int[] row = new int[image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
                for (int px : row) {
                    colors.add(px);
                    digest.update((byte) (px >>> 16));
                    digest.update((byte) (px >>> 8));
                    digest.update((byte) px);
                }
            }
            return new String[] {java.util.HexFormat.of().formatHex(digest.digest()),
                method, Integer.toString(colors.size())};
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stable fingerprint of the Java-side focus owner (class + identity). */
    private static String focusOwnerFingerprint() {
        final java.awt.Component owner = java.awt.KeyboardFocusManager
            .getCurrentKeyboardFocusManager().getFocusOwner();
        return owner == null ? "none"
            : owner.getClass().getName() + "@" + System.identityHashCode(owner);
    }

    /** Stable fingerprint of the canvas cursor instance. */
    private String canvasCursorFingerprint() {
        final java.awt.Cursor cursor = canvas.getCursor();
        return cursor == null ? "none"
            : cursor.getClass().getName() + "@" + System.identityHashCode(cursor)
                + ":" + cursor.getType();
    }

    static long percentile(final long[] sorted, final double fraction) {
        if (sorted.length == 0) throw new IllegalArgumentException("empty observations");
        return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * fraction) - 1))];
    }
}
