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
    private long measuredQueueNanos, measuredHandlerNanos, measuredRepaintBarrierNanos,
        measuredResumeNanos;

    CanvasWheelWorkload(final String fixture, final Path state) {
        if (fixture == null || fixture.isBlank()) throw new IllegalArgumentException("fixture absent");
        this.fixture = fixture;
        this.state = state;
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
        final String previous = System.getProperty(ENABLE);
        if (!List.of("modelSkip", "canvasBuffering", "swingBuffering", "uniformCache", "uniformValues", "uniformSuite").contains(factor)) {
            throw new IllegalArgumentException("unknown benchmark factor");
        }
        final StringBuilder report = new StringBuilder("schemaVersion=1\n")
            .append("readinessNanos=").append(readinessNanos).append('\n')
            .append("calibration=").append(calibration).append('\n')
            .append("performanceAccepted=false\n")
            .append("workload=awt-wheel-native-repaint-barrier\n")
            .append("latencyDefinition=wheel-to-EDT-barrier-with-native-repaint-not-presentation\n")
            .append("graphicsConfiguration=").append(canvas.getGraphicsConfiguration().getClass().getName()).append('\n')
            .append("java2dOpenGLRequested=").append(System.getProperty("sun.java2d.opengl", "unset")).append('\n')
            .append("factor=").append(factor).append('\n')
            .append("originalCanvasBuffering=").append(originalCanvasBuffering).append('\n')
            .append("originalSwingBuffering=").append(originalSwingBuffering).append('\n')
            .append("width=").append(width).append("\nheight=").append(height)
            .append("\nzoomBefore=").append(originalZoom).append('\n');
        try {
            final boolean profile = Boolean.getBoolean("turboism.validation.modelUpdateJfr");
            final boolean gpuWait = Boolean.getBoolean("turboism.validation.modelUpdateGpuWait");
            final boolean glCalls = Boolean.getBoolean("turboism.validation.modelUpdateGlCalls");
            final boolean uniform = factor.equals("uniformCache") || factor.equals("uniformValues") || factor.equals("uniformSuite");
            final boolean resourceTelemetry = Boolean.getBoolean("turboism.validation.resources");
            report.append("resourceTelemetry=").append(resourceTelemetry).append('\n');
            final boolean uniformShadow = Boolean.getBoolean("turboism.validation.uniformCacheShadow");
            if ((factor.equals("uniformValues") || factor.equals("uniformSuite")) && uniformShadow) {
                throw new IllegalArgumentException("location-shadow mode is not a value-write shadow experiment");
            }
            // A JFR-only ON leg diagnoses the residual path after caching. It is
            // deliberately not an OFF/ON performance comparison. Keep intrusive
            // GL decorators and forced GPU waits separate from this experiment.
            if (uniform && (glCalls || gpuWait || probe)) {
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
            if (glCalls) glProbe = onEdt(() -> GlSubmissionProbe.attach(canvas));
            if (gpuWait) gpuProbe = onEdt(() -> new GpuCompletionProbe(canvas));
            report.append("profiling=").append(profile).append('\n')
                .append("diagnosticOnly=").append(profile || glCalls || gpuWait || probe || uniformShadow).append('\n')
                .append("gpuCompletion.enabled=").append(gpuWait).append('\n');
            final boolean diagnostic = probe || profile || gpuWait || glCalls || uniformShadow;
            if (factor.equals("uniformSuite") && diagnostic) throw new IllegalArgumentException("suite requires diagnostic profilers OFF");
            final int[] variants = factor.equals("uniformSuite") ? new int[]{0, 1, 2, 2, 1, 0}
                : diagnostic ? new int[]{1} : new int[]{0, 1, 1, 0};
            for (int leg = 0; leg < variants.length; leg++) {
                final int variant = variants[leg];
                final boolean enabled = variant != 0;
                final String variantName = factor.equals("uniformSuite")
                    ? (variant == 0 ? "native" : variant == 1 ? "locations" : "locations-and-values")
                    : enabled ? "on" : "off";
                final String beforePixels = "modelSkip".equals(factor) ? "not-requested" : capturePixels();
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
                final String afterPixels = "modelSkip".equals(factor) ? "not-requested" : capturePixels();
                if (!beforePixels.equals(afterPixels)) {
                    throw new IllegalStateException("buffering change altered canvas pixels");
                }
                Files.writeString(state.resolve("wheel-progress.txt"),
                    "stage=warmup\nleg=" + leg + "\nenabled=" + enabled + "\n");
                for (int pair = 0; pair < warmupPairs; pair++) runPair(null, 0);
                Files.writeString(state.resolve("wheel-progress.txt"),
                    "stage=measuring\nleg=" + leg + "\nenabled=" + enabled + "\n");
                final Map<String, Long> before = snapshot();
                final Map<String, Long> uniformBefore = uniformTrial == null ? Map.of() : uniformTrial.snapshot();
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
                        recording.dump(state.resolve("wheel-leg-" + leg + ".jfr"));
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
                if (resources != null) {
                    for (var entry : resources.snapshot().entrySet()) {
                        report.append(p).append("resources.").append(entry.getKey()).append('=')
                            .append(entry.getValue()).append('\n');
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
                Files.writeString(state.resolve("wheel-benchmark.txt"), report);
            }
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
        if (factor.equals("uniformSuite")) {
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
        onEdt(() -> { setTrialFactor(false); return null; });
        FrameReadback nativeBefore = captureNativeFrame();
        FrameReadback nativeRepeat = captureNativeFrame();
        onEdt(() -> { setTrialFactor(true); return null; });
        FrameReadback cached = captureNativeFrame();
        onEdt(() -> { setTrialFactor(false); return null; });
        FrameReadback nativeAfter = captureNativeFrame();
        String key = "uniformCache.parity." + stateName + ".";
        report.append(key).append("nativeBefore=").append(nativeBefore.digest()).append('\n')
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
        onEdt(() -> { uniformTrial.requestReadback(); canvas.repaint(); return null; });
        onEdt(() -> null);
        return onEdt(() -> uniformTrial.takeReadback());
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

    private String capturePixels() throws Exception {
        if (uniformTrial != null) {
            return captureNativeFrame().digest();
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

    static long percentile(final long[] sorted, final double fraction) {
        if (sorted.length == 0) throw new IllegalArgumentException("empty observations");
        return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * fraction) - 1))];
    }
}
