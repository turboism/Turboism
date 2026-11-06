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

    /** Bound on one capability capture; a wedged host readback must not stall the recording. */
    private static final long CAPTURE_TIMEOUT_SECONDS = 30L;

    private final PluginLocalization i18n;
    private final PluginLogger logger;
    private final UiScheduler uiScheduler;
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
        this.i18n = i18n;
        this.logger = logger;
        this.uiScheduler = uiScheduler;
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
        String result = i18n.text("gif.cancelled");
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
            result = exportRecording(raw, width, height, recordedFrames);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            result = i18n.text("gif.failed") + " " + failure.getMessage();
            logger.error("GIF recording failed", failure);
        } finally {
            deleteTemporary(raw);
            final String finalResult = result;
            synchronized (gate) {
                worker = null;
                exportDialog = null;
                if (!closed) {
                    state = State.IDLE;
                    status = finalResult;
                }
            }
            refreshStrip();
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

    private String exportRecording(final Path raw, final int width, final int height, final int count)
            throws InterruptedException {
        String error = null;
        while (true) {
            checkCancelled();
            publishStatus(i18n.text("gif.export"));
            final String previousFailure = error;
            final GifExportSettingsDialog.Settings settings = showDialog(width, height, previousFailure);
            if (settings == null) return i18n.text("gif.cancelled");
            checkCancelled();
            Path optimized = null;
            try {
                final Path destination = settings.destination();
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
                checkCancelled();
                synchronized (gate) {
                    checkCancelled();
                    publish(optimized, destination);
                }
                final GifExportSettingsDialog dialog = exportDialog;
                if (dialog != null) dialog.rememberDirectory(destination);
                return i18n.text("gif.saved") + " " + destination.getFileName() + " (" + Files.size(destination)
                        + " B)";
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
     */
    private GifExportSettingsDialog.Settings showDialog(final int width, final int height, final String previousFailure)
            throws InterruptedException {
        final Object[] answer = new Object[1];
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
                logger.error("GIF export dialog failed", failure);
            } finally {
                settled.countDown();
            }
        });
        while (!settled.await(50, TimeUnit.MILLISECONDS)) {
            checkCancelled();
        }
        return (GifExportSettingsDialog.Settings) answer[0];
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

    private PanelView renderStrip() {
        final State current = state;
        final String statusText;
        synchronized (gate) {
            statusText = status;
        }
        return PanelView.row(
                PanelView.button(ACTION_RECORD, recordLabel(current), ACTION_RECORD),
                PanelView.button(ACTION_STOP, i18n.text("gif.control.stop"), ACTION_STOP),
                PanelView.button(ACTION_DISCARD, i18n.text("gif.control.discard"), ACTION_DISCARD),
                PanelView.textInput(ACTION_FPS, i18n.text("gif.fps"), Integer.toString(fps), ACTION_FPS),
                PanelView.text(statusText));
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
}
