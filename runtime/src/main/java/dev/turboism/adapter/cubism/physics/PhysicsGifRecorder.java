package dev.turboism.adapter.cubism.physics;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.i18n.CubismHostLocale;
import dev.turboism.ui.host.EdtDispatch;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/** Preview-local recording controls and cancellable, disk-backed background recording. */
final class PhysicsGifRecorder implements AutoCloseable {
    private static final String MARKER = "turboism.physics.gif";
    private final ResourceBundle messages =
            ResourceBundle.getBundle("dev.turboism.adapter.cubism.physics.messages", CubismHostLocale.resolve());
    private final Object panel;
    private final JComponent surface;
    private final Component originalTop;
    private final JPanel top = new JPanel(new BorderLayout());
    private final JSpinner fps = new JSpinner(new SpinnerNumberModel(30, 1, 50, 1));
    private final JButton record = new JButton(tr("record"));
    private final JButton discard = new JButton(tr("discard"), new RecordingIcon(RecordingIcon.Kind.DISCARD));
    private final JButton stop = new JButton(tr("stop"), new RecordingIcon(RecordingIcon.Kind.STOP));
    private final Object recordingGate = new Object();
    private volatile State state = State.IDLE;
    private volatile int recordedFrames;
    private final JLabel status = new JLabel(" ");
    private final Runnable onClose;
    private final HierarchyListener lifecycle;
    private volatile boolean closed;
    private Thread worker;
    private PhysicsGifExportDialog exportDialog;

    static PhysicsGifRecorder install(final Object panel, final Runnable onClose) throws Exception {
        final Object component = PhysicsPreviewCapture.invoke(panel, "getJComponent");
        if (!(component instanceof JComponent surface)
                || !(surface.getLayout() instanceof BorderLayout)
                || surface.getClientProperty(MARKER) != null) return null;
        return new PhysicsGifRecorder(panel, surface, onClose);
    }

    private PhysicsGifRecorder(final Object panel, final JComponent surface, final Runnable onClose) {
        this.panel = panel;
        this.surface = surface;
        lifecycle = event -> {
            if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0 && !surface.isDisplayable())
                close();
        };
        this.onClose = onClose;
        originalTop = ((BorderLayout) surface.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        final JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 3));
        controls.setOpaque(false);
        top.setOpaque(false);
        styleButton(record, "record");
        styleButton(discard, "discard");
        styleButton(stop, "stop");
        addSetting(controls, "fps", fps);
        record.addActionListener(
                event -> command(dev.turboism.sdk.cubism.physics.PhysicsEditorService.GifCommand.RECORD_PAUSE));
        stop.addActionListener(event -> command(dev.turboism.sdk.cubism.physics.PhysicsEditorService.GifCommand.STOP));
        discard.addActionListener(
                event -> command(dev.turboism.sdk.cubism.physics.PhysicsEditorService.GifCommand.DISCARD));
        final JPanel recording = new JPanel();
        recording.setLayout(new javax.swing.BoxLayout(recording, javax.swing.BoxLayout.LINE_AXIS));
        recording.setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 6, 3, 6));
        recording.setOpaque(false);
        final JPanel actions = new JPanel();
        actions.setLayout(new javax.swing.BoxLayout(actions, javax.swing.BoxLayout.LINE_AXIS));
        actions.setOpaque(false);
        actions.add(record);
        actions.add(javax.swing.Box.createHorizontalStrut(4));
        actions.add(stop);
        actions.add(javax.swing.Box.createHorizontalStrut(4));
        actions.add(discard);
        actions.setMaximumSize(actions.getPreferredSize());
        controls.setMaximumSize(controls.getPreferredSize());
        final int statusHeight = status.getPreferredSize().height;
        status.setMinimumSize(new java.awt.Dimension(0, statusHeight));
        status.setPreferredSize(new java.awt.Dimension(90, statusHeight));
        status.setMaximumSize(new java.awt.Dimension(90, statusHeight));
        status.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
        recording.add(actions);
        recording.add(javax.swing.Box.createHorizontalGlue());
        recording.add(controls);
        recording.add(javax.swing.Box.createHorizontalStrut(6));
        recording.add(status);
        top.add(recording, BorderLayout.NORTH);
        if (originalTop != null) top.add(originalTop, BorderLayout.CENTER);
        surface.add(top, BorderLayout.NORTH);
        surface.putClientProperty(MARKER, this);
        surface.addHierarchyListener(lifecycle);
        refreshControls();
        surface.revalidate();
    }

    private String tr(final String key) {
        return messages.getString(key);
    }

    private void styleButton(final JButton button, final String key) {
        button.setText(null);
        button.setToolTipText(tr(key));
        button.getAccessibleContext().setAccessibleName(tr(key));
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setRolloverEnabled(true);
        button.setPreferredSize(new java.awt.Dimension(28, 28));
        button.setMinimumSize(button.getPreferredSize());
        button.setMaximumSize(button.getPreferredSize());
    }

    private void addSetting(final JPanel controls, final String key, final JComponent input) {
        final JLabel label = new JLabel(tr(key));
        label.setLabelFor(input);
        input.getAccessibleContext().setAccessibleName(tr(key));
        controls.add(label);
        controls.add(input);
    }

    enum State {
        IDLE,
        RECORDING,
        PAUSED,
        STOPPING,
        EXPORTING,
        CANCELLING
    }

    void command(final dev.turboism.sdk.cubism.physics.PhysicsEditorService.GifCommand command) {
        if (closed) return;
        switch (command) {
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
                    if (worker != null) worker.interrupt();
                    if (exportDialog != null) exportDialog.close();
                }
            }
        }
    }

    private void changeState(final State value) {
        synchronized (recordingGate) {
            state = value;
            recordingGate.notifyAll();
        }
        refreshControls();
    }

    private void startRecording() {
        try {
            fps.commitEdit();
            final int rate = ((Number) fps.getValue()).intValue();
            recordedFrames = 0;
            changeState(State.RECORDING);
            worker = new Thread(() -> runRecording(rate), "physics-gif-recorder");
            worker.setDaemon(true);
            worker.start();
        } catch (Exception failure) {
            changeState(State.IDLE);
            setStatus(tr("failed") + " " + failure.getMessage());
        }
    }

    /** Waits without capturing while paused; stopping keeps a first frame for an immediate stop. */
    private boolean awaitFrame(final long deadline) throws InterruptedException {
        synchronized (recordingGate) {
            while (true) {
                checkCancelled();
                if (state == State.STOPPING) return recordedFrames == 0;
                if (state == State.PAUSED) {
                    recordingGate.wait();
                    continue;
                }
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return true;
                TimeUnit.NANOSECONDS.timedWait(recordingGate, remaining);
            }
        }
    }

    private void runRecording(final int rate) {
        Path raw = null;
        String result = tr("cancelled");
        try {
            raw = Files.createTempFile("turboism-physics-", ".gif");
            int width = 0;
            int height = 0;
            try (var capture = new PhysicsPreviewCapture(panel);
                    var encoder = new PhysicsGifEncoder(raw, rate)) {
                long next = System.nanoTime();
                while (awaitFrame(next)) {
                    final long started = System.nanoTime();
                    final var image = capture.capture();
                    if (recordedFrames == 0) {
                        width = image.getWidth();
                        height = image.getHeight();
                    } else if (width != image.getWidth() || height != image.getHeight()) {
                        throw new IllegalStateException(tr("resized"));
                    }
                    encoder.append(image);
                    recordedFrames++;
                    EdtDispatch.post("physics GIF frame count", () -> {
                        if (state == State.RECORDING) setStatus(tr("recording") + " " + recordedFrames);
                    });
                    // Never burst to catch up after slow rendering/encoding.
                    next = Math.max(started + TimeUnit.SECONDS.toNanos(1) / rate, System.nanoTime());
                }
            }
            checkCancelled();
            EdtDispatch.post("physics GIF export state", () -> {
                if (state == State.STOPPING) changeState(State.EXPORTING);
            });
            result = exportRecording(raw, width, height, recordedFrames);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            result = tr("failed") + " " + failure.getMessage();
            dev.turboism.runtime.log.RuntimeDiagnostics.error("physics-editor", "GIF recording failed", failure);
        } finally {
            deleteTemporary(raw);
            final String finalResult = result;
            EdtDispatch.post("physics GIF completion", () -> {
                worker = null;
                exportDialog = null;
                if (!closed) {
                    changeState(State.IDLE);
                    setStatus(finalResult);
                }
            });
        }
    }

    private String exportRecording(final Path raw, final int width, final int height, final int count)
            throws Exception {
        String error = null;
        while (true) {
            checkCancelled();
            updateStatus(tr("export"));
            final String previousFailure = error;
            final var settings = EdtDispatch.call(
                    "physics GIF export dialog",
                    EdtDispatch.DEFAULT_ACCEPT_TIMEOUT,
                    () -> {
                        if (closed) return null;
                        if (exportDialog == null) exportDialog = new PhysicsGifExportDialog(messages, width, height);
                        return exportDialog.show(surface, previousFailure);
                    },
                    () -> {
                        if (exportDialog != null) exportDialog.close();
                    });
            if (settings == null) return tr("cancelled");
            checkCancelled();
            Path optimized = null;
            try {
                final Path destination = settings.destination();
                optimized = Files.createTempFile(destination.getParent(), ".turboism-physics-", ".gif");
                updateStatus(tr("optimizing"));
                PhysicsGifEncoder.optimize(
                        settings.executable(),
                        raw,
                        optimized,
                        settings.maxBytes(),
                        settings.width(),
                        settings.height(),
                        count);
                checkCancelled();
                synchronized (this) {
                    checkCancelled();
                    try {
                        Files.move(
                                optimized,
                                destination,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(optimized, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                PhysicsGifExportDialog.rememberDirectory(destination);
                return tr("saved") + " " + destination.getFileName() + " (" + Files.size(destination) + " B)";
            } catch (java.io.IOException failure) {
                checkCancelled();
                error = tr("failed") + " " + failure.getMessage();
                dev.turboism.runtime.log.RuntimeDiagnostics.error(
                        "physics-editor", "GIF export failed; recording retained for retry", failure);
            } finally {
                deleteTemporary(optimized);
            }
        }
    }

    private void checkCancelled() throws InterruptedException {
        if (closed || state == State.CANCELLING || Thread.currentThread().isInterrupted())
            throw new InterruptedException();
    }

    private static void deleteTemporary(final Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (java.io.IOException failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    "physics-editor", "GIF temporary cleanup failed", failure);
        }
    }

    private void setStatus(final String value) {
        status.setText(value);
        status.setToolTipText(value.isBlank() ? null : value);
    }

    private void updateStatus(final String value) {
        EdtDispatch.post("physics GIF progress", () -> {
            if (!closed) setStatus(value);
        });
    }

    private void refreshControls() {
        final boolean active = state == State.RECORDING || state == State.PAUSED;
        fps.setEnabled(state == State.IDLE);
        final String recordLabel = tr(state == State.RECORDING ? "pause" : state == State.PAUSED ? "resume" : "record");
        record.setToolTipText(recordLabel);
        record.getAccessibleContext().setAccessibleName(recordLabel);
        record.setIcon(
                new RecordingIcon(state == State.RECORDING ? RecordingIcon.Kind.PAUSE : RecordingIcon.Kind.RECORD));
        record.setEnabled(state == State.IDLE || active);
        stop.setEnabled(active);
        discard.setEnabled(active);
        setStatus(
                switch (state) {
                    case IDLE -> " ";
                    case RECORDING -> tr("recording") + " " + recordedFrames;
                    case PAUSED -> tr("paused") + " " + recordedFrames;
                    case STOPPING -> tr("preparing");
                    case EXPORTING -> tr("export");
                    case CANCELLING -> tr("cancelling");
                });
    }

    /** Vector icons stay sharp at the host's display scale and do not depend on symbol fonts. */
    private record RecordingIcon(Kind kind) implements javax.swing.Icon {
        enum Kind {
            RECORD,
            PAUSE,
            STOP,
            DISCARD
        }

        @Override
        public int getIconWidth() {
            return 20;
        }

        @Override
        public int getIconHeight() {
            return 20;
        }

        @Override
        public void paintIcon(final Component component, final java.awt.Graphics graphics, final int x, final int y) {
            final java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.scale(1.25, 1.25);
                g.setRenderingHint(
                        java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(
                        !component.isEnabled()
                                ? java.awt.Color.GRAY
                                : switch (kind) {
                                    case RECORD -> new java.awt.Color(175, 39, 32);
                                    case PAUSE -> new java.awt.Color(46, 160, 67);
                                    case STOP -> new java.awt.Color(63, 130, 185);
                                    default -> component.getForeground();
                                });
                switch (kind) {
                    case RECORD -> g.fillOval(1, 1, 14, 14);
                    case STOP -> g.fillRect(1, 1, 14, 14);
                    case PAUSE -> {
                        g.fillRect(2, 1, 4, 14);
                        g.fillRect(10, 1, 4, 14);
                    }
                    case DISCARD -> {
                        g.setStroke(new java.awt.BasicStroke(2.5f));
                        g.drawLine(2, 2, 14, 14);
                        g.drawLine(14, 2, 2, 14);
                    }
                }
            } finally {
                g.dispose();
            }
        }
    }

    boolean isClosed() {
        return closed;
    }

    boolean isFocused() {
        final java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(surface);
        return !closed && window != null && window.isFocused();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (worker != null) worker.interrupt();
        if (exportDialog != null) exportDialog.close();
        surface.removeHierarchyListener(lifecycle);
        surface.remove(top);
        if (originalTop != null) surface.add(originalTop, BorderLayout.NORTH);
        surface.putClientProperty(MARKER, null);
        surface.revalidate();
        surface.repaint();
        onClose.run();
    }
}
