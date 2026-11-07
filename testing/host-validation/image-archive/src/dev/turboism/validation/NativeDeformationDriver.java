package dev.turboism.validation;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;

/**
 * Test-only Windows repaint driver for the FPS exerciser session.
 *
 * <p>The Cubism viewport repaints on demand, so a static fixture can produce no
 * {@code renderScene} call inside the sampling window. The Linux/Proton runner
 * forces repaints by resizing the window through X11, which needs a display
 * server. This driver reaches the same effect from inside the JVM: it resizes
 * the task's own frame on the EDT, so the modeling canvas re-lays-out and
 * re-renders without any platform bridge, synthetic input or host mutation
 * beyond the window geometry the reviewed X11 driver also changes.</p>
 *
 * <p>Safety: it only touches the single visible frame whose title carries the
 * exact task fixture name, re-checks that frame before every burst, restores the
 * original size when it stops, and never signals, closes or cleans any process.
 * It stops when its deadline expires or the caller's stop file appears.</p>
 */
final class NativeDeformationDriver {
    private static final String FIXTURE_PROPERTY = "turboism.validation.fixtureName";
    private static final String HOME_PROPERTY = "turboism.validation.imageArchive.home";
    private static final String STOP_FILE = "state/texture-upload/fps-driver.stop";
    /** Resize amplitude in pixels; large enough to force a canvas relayout. */
    private static final int AMPLITUDE = 120;
    /** Pause between geometry changes so each one produces its own frame. */
    private static final long STEP_MILLIS = 60L;

    private NativeDeformationDriver() {}

    /**
     * Drives repaint bursts until {@code seconds} elapse or the stop file
     * appears, then restores the original window size.
     */
    static String drive(final int seconds) throws Exception {
        if (seconds < 1 || seconds > 900) throw new IllegalArgumentException("invalid driver duration");
        final String fixture = System.getProperty(FIXTURE_PROPERTY, "");
        if (fixture.isBlank()) throw new IllegalStateException("driver requires the task fixture identity");
        final Path home = Path.of(System.getProperty(HOME_PROPERTY, ""));
        final Path stop = home.resolve(STOP_FILE);
        final Path summary = home.resolve("state/texture-upload/fps-driver.txt");
        Files.createDirectories(summary.getParent());

        final Frame frame = awaitTaskFrame(fixture);
        final Dimension original = onEdt(frame::getSize);
        Files.writeString(home.resolve("state/texture-upload/fps-driver-structure.txt"), describeFrame(frame, original));
        final long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        int bursts = 0, resizes = 0;
        String lastFailure = "none";
        try {
            while (System.nanoTime() < deadline && !Files.exists(stop)) {
                try {
                    if (!onEdt(frame::isShowing)) {
                        lastFailure = "task frame no longer showing";
                        break;
                    }
                    resizes += burst(frame, original);
                    bursts++;
                } catch (Exception failure) {
                    lastFailure = failure.getClass().getSimpleName();
                    Thread.sleep(250);
                }
            }
        } finally {
            try {
                onEdt(() -> {
                    frame.setSize(original);
                    frame.validate();
                    return null;
                });
            } catch (Exception ignored) {
                // Restoring geometry is best effort; the host closes the frame afterwards.
            }
            Files.writeString(
                    summary,
                    "status=" + (resizes > 0 ? "PASS" : "FAIL") + "\n"
                            + "bursts=" + bursts + "\n"
                            + "resizes=" + resizes + "\n"
                            + "durationSeconds=" + seconds + "\n"
                            + "originalWidth=" + original.width + "\n"
                            + "originalHeight=" + original.height + "\n"
                            + "lastFailure=" + lastFailure + "\n");
        }
        return "bursts=" + bursts + " resizes=" + resizes + " lastFailure=" + lastFailure;
    }

    /** One grow/shrink cycle; each geometry change is a separate relayout. */
    private static int burst(final Frame frame, final Dimension original) throws Exception {
        final int width = Math.max(320, original.width);
        final int height = Math.max(240, original.height);
        final List<Dimension> steps = List.of(
                new Dimension(width + AMPLITUDE, height),
                new Dimension(width, height + AMPLITUDE),
                new Dimension(width - AMPLITUDE / 2, height),
                new Dimension(width, height - AMPLITUDE / 2),
                new Dimension(width, height));
        int applied = 0;
        for (Dimension step : steps) {
            onEdt(() -> {
                frame.setSize(step);
                frame.validate();
                return null;
            });
            applied++;
            Thread.sleep(STEP_MILLIS);
        }
        return applied;
    }

    private static Frame awaitTaskFrame(final String fixture) throws Exception {
        final long deadline = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < deadline) {
            final Frame frame = onEdt(() -> {
                final List<Frame> matches = new ArrayList<>();
                for (Frame candidate : Frame.getFrames()) {
                    if (candidate.isVisible() && candidate.getTitle() != null
                            && candidate.getTitle().contains(fixture)) {
                        matches.add(candidate);
                    }
                }
                return matches.size() == 1 ? matches.get(0) : null;
            });
            if (frame != null) return frame;
            Thread.sleep(250);
        }
        throw new IllegalStateException("task fixture frame did not appear");
    }

    /** Test-only structural report so a failed drive can be diagnosed without a screenshot. */
    static String describeFrame(final Frame frame, final Dimension original) {
        final StringBuilder text = new StringBuilder();
        text.append("frame=").append(frame.getTitle()).append('\n');
        text.append("originalSize=").append(original.width).append('x').append(original.height).append('\n');
        final List<JSlider> sliders = new ArrayList<>();
        collectSliders(frame, sliders);
        text.append("sliders=").append(sliders.size()).append('\n');
        for (JSlider slider : sliders) {
            text.append("  class=").append(slider.getClass().getName())
                    .append(" showing=").append(slider.isShowing())
                    .append(" value=").append(slider.getValue())
                    .append(" size=").append(slider.getWidth()).append('x').append(slider.getHeight())
                    .append('\n');
        }
        return text.toString();
    }

    private static void collectSliders(final Container root, final List<JSlider> matches) {
        for (Component child : root.getComponents()) {
            if (child instanceof JSlider slider) matches.add(slider);
            if (child instanceof Container container) collectSliders(container, matches);
        }
    }

    private static <T> T onEdt(final Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        final java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        try {
            return task.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            task.cancel(false);
            throw new IllegalStateException("driver EDT operation timed out", timeout);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new Exception("driver EDT operation failed", failure.getCause());
        }
    }
}
