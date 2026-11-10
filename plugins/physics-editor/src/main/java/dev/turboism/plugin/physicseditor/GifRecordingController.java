package dev.turboism.plugin.physicseditor;

import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.cubism.preview.PreviewFrame;
import dev.turboism.sdk.cubism.preview.PreviewFrameSource;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiScheduler;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;

/**
 * Preview GIF recording: the state machine, the frame loop, the export policy and the control strip.
 *
 * <p>This is feature business logic, so it lives with the feature. The runtime contributes the
 * generic surface it needs — {@link PreviewFrameSource}: read one frame, put my strip here — while
 * this class owns the recording states, the pause/resume semantics, the disk-backed encoder, the
 * Gifsicle invocation, the pixel/size policy and every user-visible string.</p>
 *
 * <p>Threading: commands arrive on the host UI thread and only flip state; the frame loop and the
 * export run on one plugin-owned worker thread, which reads frames through the capability instead
 * of touching the OpenGL context itself. All strip updates go through the source, which marshals
 * them.</p>
 */
final class GifRecordingController implements AutoCloseable {

    private static final String ACTION_RECORD = "record";
    private static final String ACTION_STOP = "stop";
    private static final String ACTION_DISCARD = "discard";
    private static final String ACTION_FPS = "fps";

    private static final int DEFAULT_FPS = 30;
    private static final int MIN_FPS = 1;
    private static final int MAX_FPS = 50;

    /** Counter publishes at this interval; per-frame UI updates would rebuild the strip 30×/s. */
    private static final long STATUS_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    /**
     * Reserved status cell at the end of the transport row: one text line.
     *
     * <p>The width is a floor, not a cap: the cell stretches into the spare width of a wider pane.
     * A longer message, such as a saved file name, is clipped inside the cell. The recorder aborts
     * when the preview size changes, so the strip keeps one row and a constant height.</p>
     */
    private static final int STATUS_CELL_WIDTH = 96;

    private static final int STATUS_CELL_HEIGHT = 24;

    /** Frame rate carries two digits, so its field stays narrow and leaves width for the status. */
    private static final int FPS_CELL_WIDTH = 56;

    private static final int FPS_CELL_HEIGHT = 24;

    /** Bundled Lucide alpha masks; the runtime tints each mask with the active theme foreground. */
    private static final String ICON_DIRECTORY = "/icons/lucide/";

    private static final String ICON_SUFFIX = ".png";

    private static final String ICON_RECORD = "circle";

    private static final String ICON_PAUSE = "pause";

    private static final String ICON_RESUME = "play";

    private static final String ICON_STOP = "square";

    private static final String ICON_DISCARD = "x";

    private static final byte[] NO_ICON = new byte[0];

    /** Bound on one capability capture; a wedged host readback must not stall the recording. */
    private static final long CAPTURE_TIMEOUT_SECONDS = 30L;

    private final PluginLocalization i18n;
    private final PluginLogger logger;
    private final UiScheduler uiScheduler;
    private final OutcomeSink outcomes;
    private final java.util.Map<String, byte[]> icons = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object gate = new Object();
    private volatile State state = State.IDLE;
    private volatile boolean closed;
    private PreviewFrameSource source;
    private Registration strip;
    private int fps = DEFAULT_FPS;
    private int recordedFrames;
    private long lastStatusNanos;
    private String status = " ";
    private Thread worker;
    private GifExportSettingsDialog exportDialog;

    GifRecordingController(final PluginLocalization i18n, final PluginLogger logger, final UiScheduler uiScheduler) {
        this(i18n, logger, uiScheduler, (severity, message) -> {});
    }

    GifRecordingController(
            final PluginLocalization i18n,
            final PluginLogger logger,
            final UiScheduler uiScheduler,
            final OutcomeSink outcomes) {
        this.i18n = i18n;
        this.logger = logger;
        this.uiScheduler = uiScheduler;
        this.outcomes = outcomes;
    }

    /** The recording states the strip and the commands drive. */
    enum State {
        IDLE,
        RECORDING,
        PAUSED,
        STOPPING,
        EXPORTING,
        CANCELLING
    }

    /**
     * Binds the strip to {@code target}, replacing a previous binding.
     *
     * @param target the focused preview source, never {@code null}
     */
    void bind(final PreviewFrameSource target) {
        final PreviewFrameSource stale;
        synchronized (gate) {
            if (source == target) return;
            if (closed) {
                // A binding attempted after teardown is refused, and the source it offered is
                // released here so the caller never has to track that race.
                closeQuietly(target);
                return;
            }
            stale = source;
            source = target;
            strip = null;
        }
        if (stale != null) closeQuietly(stale);
        final Registration attached = target.attachControls(renderStrip(), this::onControl);
        synchronized (gate) {
            if (source == target && !closed) {
                strip = attached;
                return;
            }
        }
        attached.close();
    }

    /**
     * Releases the current binding: the strip goes away and the source is closed.
     *
     * @param expected the source the caller bound; a superseded source is left to its own binding
     */
    void unbind(final PreviewFrameSource expected) {
        final PreviewFrameSource stale;
        final Registration attached;
        synchronized (gate) {
            if (source != expected) return;
            stale = source;
            attached = strip;
            source = null;
            strip = null;
        }
        if (attached != null) attached.close();
        if (stale != null) closeQuietly(stale);
    }

    /** @return the current recording state, for diagnostics probes and lifecycle tests */
    GifRecordingController.State state() {
        return state;
    }

    /**
     * Applies one user command.
     *
     * @param desired the requested transition
     */
    void command(final Command desired) {
        if (closed) return;
        switch (desired) {
            case RECORD_PAUSE -> {
                if (state == State.IDLE) startRecording();
                else if (state == State.RECORDING) changeState(State.PAUSED);
                else if (state == State.PAUSED) changeState(State.RECORDING);
            }
            case PAUSE -> {
                if (state == State.RECORDING) changeState(State.PAUSED);
            }
            case STOP -> {
                if (state == State.RECORDING || state == State.PAUSED) changeState(State.STOPPING);
            }
            case DISCARD -> {
                if (state != State.IDLE && state != State.CANCELLING) {
                    changeState(State.CANCELLING);
                    final Thread active = worker;
                    if (active != null) active.interrupt();
                    final GifExportSettingsDialog dialog = exportDialog;
                    if (dialog != null) dialog.close();
                }
            }
        }
    }

    /** The commands a shortcut or a strip control dispatches. */
    enum Command {
        RECORD_PAUSE,
        PAUSE,
        STOP,
        DISCARD
    }

    @Override
    public void close() {
        final PreviewFrameSource stale;
        final Registration attached;
        final Thread active;
        final GifExportSettingsDialog dialog;
        synchronized (gate) {
            if (closed) return;
            closed = true;
            stale = source;
            attached = strip;
            source = null;
            strip = null;
            active = worker;
            dialog = exportDialog;
        }
        if (active != null) active.interrupt();
        if (dialog != null) dialog.close();
        if (attached != null) attached.close();
        if (stale != null) closeQuietly(stale);
    }

    private void onControl(final String actionId, final Optional<UiActionEvent> event) {
        switch (actionId) {
            case ACTION_RECORD -> command(Command.RECORD_PAUSE);
            case ACTION_STOP -> command(Command.STOP);
            case ACTION_DISCARD -> command(Command.DISCARD);
            case ACTION_FPS -> applyRate(event);
            default -> {
                // An unknown id belongs to a strip revision this controller no longer owns.
            }
        }
    }

    private void applyRate(final Optional<UiActionEvent> event) {
        if (state != State.IDLE) return;
        final String text = event.map(UiActionEvent::value)
                .filter(UiActionEvent.TextValue.class::isInstance)
                .map(UiActionEvent.TextValue.class::cast)
                .map(UiActionEvent.TextValue::value)
                .orElse("");
        try {
            final int parsed = Integer.parseInt(text.trim());
            if (parsed < MIN_FPS || parsed > MAX_FPS) throw new NumberFormatException(text);
            fps = parsed;
        } catch (NumberFormatException invalid) {
            logger.warn("GIF frame rate must be between " + MIN_FPS + " and " + MAX_FPS + "; keeping " + fps);
            refreshStrip();
        }
    }

    private void startRecording() {
        final PreviewFrameSource target;
        synchronized (gate) {
            target = source;
        }
        if (target == null) {
            publishStatus(i18n.text("gif.no-preview"));
            return;
        }
        recordedFrames = 0;
        changeState(State.RECORDING);
        final Thread thread = new Thread(() -> runRecording(target, fps), "turboism-physics-gif-recorder");
        thread.setDaemon(true);
        synchronized (gate) {
            worker = thread;
        }
        thread.start();
    }

    /** Waits without capturing while paused; stopping keeps a first frame for an immediate stop. */
    private boolean awaitFrame(final long deadline) throws InterruptedException {
        synchronized (gate) {
            while (true) {
                checkCancelled();
                if (state == State.STOPPING) return recordedFrames == 0;
                if (state == State.PAUSED) {
                    gate.wait();
                    continue;
                }
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return true;
                TimeUnit.NANOSECONDS.timedWait(gate, remaining);
            }
        }
    }

    private void runRecording(final PreviewFrameSource target, final int rate) {
        Path raw = null;
        Outcome outcome = Outcome.silent(i18n.text("gif.cancelled"));
        try {
            raw = Files.createTempFile("turboism-physics-", ".gif");
            int width = 0;
            int height = 0;
            try (var encoder = new GifFrameEncoder(raw, rate)) {
                long next = System.nanoTime();
                while (awaitFrame(next)) {
                    final long started = System.nanoTime();
                    final BufferedImage image = decode(awaitCapture(target));
                    if (recordedFrames == 0) {
                        width = image.getWidth();
                        height = image.getHeight();
                    } else if (width != image.getWidth() || height != image.getHeight()) {
                        throw new IllegalStateException(i18n.text("gif.resized"));
                    }
                    encoder.append(image);
                    recordedFrames++;
                    publishFrameCount();
                    // Never burst to catch up after slow rendering/encoding.
                    next = Math.max(started + TimeUnit.SECONDS.toNanos(1) / rate, System.nanoTime());
                }
            }
            checkCancelled();
            changeState(State.EXPORTING);
            outcome = exportRecording(raw, width, height, recordedFrames);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            outcome = Outcome.warning(i18n.text("gif.failed") + " " + failure.getMessage());
            logger.error("GIF recording failed", failure);
        } finally {
            deleteTemporary(raw);
            final Outcome finalOutcome = outcome;
            synchronized (gate) {
                worker = null;
                exportDialog = null;
                if (!closed) {
                    state = State.IDLE;
                    // The strip carries live transport progress only. A finished export belongs on
                    // the host status bar, where it does not sit in the frame-rate line.
                    status = statusText(State.IDLE);
                }
            }
            refreshStrip();
            finalOutcome.publishTo(outcomes);
        }
    }

    private PreviewFrame awaitCapture(final PreviewFrameSource target)
            throws InterruptedException, ExecutionException, TimeoutException {
        return target.capture().toCompletableFuture().get(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static BufferedImage decode(final PreviewFrame frame) throws IOException {
        final BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame.png()));
        if (image == null) throw new IOException("preview frame is not a readable PNG");
        return image;
    }

    private Outcome exportRecording(final Path raw, final int width, final int height, final int count)
            throws InterruptedException {
        String error = null;
        while (true) {
            checkCancelled();
            publishStatus(i18n.text("gif.export"));
            final String previousFailure = error;
            final GifExportSettingsDialog.Settings settings = showDialog(width, height, previousFailure);
            if (settings == null) {
                // A cancelled dialog is not news; a failure the operator dismissed still is.
                return error == null ? Outcome.silent(i18n.text("gif.cancelled")) : Outcome.warning(error);
            }
            checkCancelled();
            Path optimized = null;
            try {
                final Path destination = settings.destination();
                if (settings.format() == GifExportSettingsDialog.Format.APNG) {
                    // Animated PNG needs no optimizer: it is written straight from the recording.
                    optimized = Files.createTempFile(destination.getParent(), ".turboism-physics-", ".png");
                    publishStatus(i18n.text("gif.encoding"));
                    ApngEncoder.write(raw, optimized, frameDelayMillis());
                } else {
                    optimized = Files.createTempFile(destination.getParent(), ".turboism-physics-", ".gif");
                    publishStatus(i18n.text("gif.optimizing"));
                    GifFrameEncoder.optimize(
                            settings.executable(),
                            raw,
                            optimized,
                            settings.maxBytes(),
                            settings.width(),
                            settings.height(),
                            count);
                }
                checkCancelled();
                synchronized (gate) {
                    checkCancelled();
                    publish(optimized, destination);
                }
                final GifExportSettingsDialog dialog = exportDialog;
                if (dialog != null) dialog.rememberDirectory(destination);
                return Outcome.info(i18n.text("gif.saved") + " " + destination.getFileName() + " ("
                        + Files.size(destination) + " B)");
            } catch (GifFrameEncoder.OptimizerUnavailableException missing) {
                checkCancelled();
                // Keep the recording and tell the operator how to recover; the unoptimized path
                // needs no optimizer at all.
                error = i18n.format("gif.gifsicle.missing", missing.executable());
                logger.warn("GIF optimizer is unavailable: " + missing.executable());
            } catch (IOException failure) {
                checkCancelled();
                error = i18n.text("gif.failed") + " " + failure.getMessage();
                logger.error("GIF export failed; recording retained for retry", failure);
            } finally {
                deleteTemporary(optimized);
            }
        }
    }

    /** Replaces the destination with the finished recording, preferring a single atomic rename. */
    private static void publish(final Path optimized, final Path destination) throws IOException {
        try {
            Files.move(optimized, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException fallback) {
            Files.move(optimized, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Shows the export dialog on the host UI thread and waits for its answer.
     *
     * <p>The dialog is modal over the Editor, so the wait must stay interruptible: cancelling the
     * recording disposes the dialog, which makes the pending {@code show} return and this method
     * throw {@link InterruptedException} on its next poll.
     *
     * <p>A dialog that fails to open is reported as an export failure, not as a cancellation, so the
     * user learns the recording could not be exported instead of reading a silent cancel.</p>
     */
    private GifExportSettingsDialog.Settings showDialog(final int width, final int height, final String previousFailure)
            throws InterruptedException {
        final Object[] answer = new Object[1];
        final RuntimeException[] broken = new RuntimeException[1];
        final CountDownLatch settled = new CountDownLatch(1);
        uiScheduler.runOnUiThread(() -> {
            try {
                if (closed) return;
                synchronized (gate) {
                    if (exportDialog == null) exportDialog = new GifExportSettingsDialog(i18n, logger, width, height);
                }
                answer[0] = exportDialog.show(previousFailure);
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (RuntimeException failure) {
                // A broken dialog is a failure, not a cancel: the caller must report it instead of
                // deleting the recording silently as if the operator had cancelled.
                logger.error("GIF export dialog failed", failure);
                broken[0] = failure;
            } finally {
                settled.countDown();
            }
        });
        while (!settled.await(50, TimeUnit.MILLISECONDS)) {
            checkCancelled();
        }
        if (broken[0] != null) {
            throw new IllegalStateException("export settings dialog failed", broken[0]);
        }
        return (GifExportSettingsDialog.Settings) answer[0];
    }

    /** Per-frame delay of the recording, in milliseconds. */
    private int frameDelayMillis() {
        return (int) Math.max(1L, Math.round(1000.0 / Math.max(1, fps)));
    }

    private void checkCancelled() throws InterruptedException {
        if (closed || state == State.CANCELLING || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private void deleteTemporary(final Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            logger.error("GIF temporary cleanup failed", failure);
        }
    }

    private void changeState(final State value) {
        synchronized (gate) {
            state = value;
            gate.notifyAll();
            status = statusText(value);
        }
        refreshStrip();
    }

    private void publishFrameCount() {
        final long now = System.nanoTime();
        if (now - lastStatusNanos < STATUS_INTERVAL_NANOS) return;
        lastStatusNanos = now;
        publishStatus(statusText(state));
    }

    private void publishStatus(final String value) {
        synchronized (gate) {
            status = value;
        }
        refreshStrip();
    }

    private String statusText(final State value) {
        return switch (value) {
            case IDLE -> " ";
            case RECORDING -> i18n.text("gif.recording") + " " + recordedFrames;
            case PAUSED -> i18n.text("gif.status.paused") + " " + recordedFrames;
            case STOPPING -> i18n.text("gif.preparing");
            case EXPORTING -> i18n.text("gif.export");
            case CANCELLING -> i18n.text("gif.cancelling");
        };
    }

    private void refreshStrip() {
        final PreviewFrameSource target;
        synchronized (gate) {
            if (closed) return;
            target = source;
        }
        if (target == null) return;
        try {
            target.updateControls(renderStrip());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException stale) {
            logger.debug("GIF control strip update dropped: " + stale.getMessage());
        }
    }

    /**
     * Renders the strip as one row: transport icon buttons, frame rate and status.
     *
     * <p>Every cell keeps a constant height while the status text changes, because a taller strip
     * shrinks the preview and the recorder then aborts on a preview that changed size.</p>
     */
    private PanelView renderStrip() {
        final State current = state;
        final String statusText;
        synchronized (gate) {
            statusText = status;
        }
        final boolean endable = canEndRecording(current);
        return PanelView.row(
                control(ACTION_RECORD, recordIcon(current), recordLabel(current), canToggleRecording(current)),
                control(ACTION_STOP, ICON_STOP, i18n.text("gif.control.stop"), endable),
                control(ACTION_DISCARD, ICON_DISCARD, i18n.text("gif.control.discard"), endable),
                PanelView.centered(
                        PanelView.textInput(ACTION_FPS, i18n.text("gif.fps"), Integer.toString(fps), ACTION_FPS),
                        FPS_CELL_WIDTH,
                        FPS_CELL_HEIGHT),
                PanelView.centered(PanelView.text(statusText), STATUS_CELL_WIDTH, STATUS_CELL_HEIGHT));
    }

    /** Stop and discard act only on a recording that is running or paused. */
    private static boolean canEndRecording(final State current) {
        return current == State.RECORDING || current == State.PAUSED;
    }

    /** The record control toggles only while no export or cancellation is running. */
    private static boolean canToggleRecording(final State current) {
        return current == State.IDLE || canEndRecording(current);
    }

    /**
     * Builds one strip control from a bundled Lucide mask.
     *
     * <p>A missing mask falls back to a text button, so a stripped build keeps every command
     * reachable instead of showing an empty control.</p>
     */
    private PanelView control(final String actionId, final String iconName, final String label, final boolean enabled) {
        final byte[] icon = icons.computeIfAbsent(iconName, this::loadIcon);
        return icon.length == 0
                ? PanelView.button(actionId, label, actionId)
                : PanelView.colorIconButton(actionId, icon, label, actionId, enabled);
    }

    private byte[] loadIcon(final String name) {
        final String path = ICON_DIRECTORY + name + ICON_SUFFIX;
        try (java.io.InputStream stream = GifRecordingController.class.getResourceAsStream(path)) {
            if (stream == null) {
                logger.warn("GIF control icon is missing: " + path);
                return NO_ICON;
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            logger.warn("GIF control icon could not be read: " + name);
            return NO_ICON;
        }
    }

    /** The transport icon that matches what a press on the record control does next. */
    private String recordIcon(final State current) {
        return switch (current) {
            case RECORDING -> ICON_PAUSE;
            case PAUSED -> ICON_RESUME;
            default -> ICON_RECORD;
        };
    }

    private String recordLabel(final State current) {
        return switch (current) {
            case RECORDING -> i18n.text("gif.control.pause");
            case PAUSED -> i18n.text("gif.control.resume");
            default -> i18n.text("gif.record");
        };
    }

    private static void closeQuietly(final PreviewFrameSource target) {
        try {
            target.close();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException alreadyGone) {
            // A source closed by its generation fence is already gone; nothing to release.
        }
    }

    /**
     * Publishes one finished export to the host status bar.
     *
     * <p>The strip is a transport control line; a saved-file notice there took the frame-rate cell
     * and stayed until the next recording. The plugin binds this sink to the host status region.</p>
     */
    @FunctionalInterface
    interface OutcomeSink {
        void publish(String severity, String message);
    }

    /**
     * Terminal result of one recording run.
     *
     * @param message the text to report
     * @param severity the host status severity, or {@code null} when nothing should be published
     */
    record Outcome(String message, String severity) {

        static Outcome info(final String message) {
            return new Outcome(message, "INFO");
        }

        static Outcome warning(final String message) {
            return new Outcome(message, "WARNING");
        }

        static Outcome silent(final String message) {
            return new Outcome(message, null);
        }

        void publishTo(final OutcomeSink sink) {
            if (severity != null) sink.publish(severity, message);
        }
    }
}
