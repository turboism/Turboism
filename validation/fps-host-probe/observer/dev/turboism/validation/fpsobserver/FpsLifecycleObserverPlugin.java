package dev.turboism.validation.fpsobserver;

import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Separately loaded test plugin proving that actor scope closure preserves other subscribers. */
public final class FpsLifecycleObserverPlugin implements TurboismPlugin {
    private volatile boolean running = true;

    @Override public void init(final PluginContext context) {
        if (!Boolean.getBoolean("turboism.validation.fps.lifecycle")) {
            throw new IllegalStateException("Observer requires explicit lifecycle acceptance mode");
        }
        final Thread worker = new Thread(() -> observe(context), "fps-lifecycle-observer");
        worker.setDaemon(true);
        worker.start();
    }

    @Override public void disable() { running = false; }

    private void observe(final PluginContext context) {
        final Path state = context.paths().stateDir();
        final String runId = System.getProperty("turboism.validation.runId", "");
        final AtomicLong count = new AtomicLong();
        final AtomicLong frames = new AtomicLong();
        try {
            if (runId.isBlank()) throw new IllegalStateException("Missing task identity");
            final PerformanceProbeService stats = context.performanceStats();
            stats.sample(Duration.ofMillis(100), sample -> {
                count.incrementAndGet();
                frames.accumulateAndGet(sample.renderedFrames(), Math::max);
            });
            final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
            final Path stop = state.resolve("stop.request");
            boolean requested = false;
            while (running && System.nanoTime() - deadline < 0) {
                publish(state, "state=RUNNING\ncount=" + count.get() + "\nframes=" + frames.get() + "\n");
                if (Files.isRegularFile(stop) && runId.equals(Files.readString(stop, StandardCharsets.UTF_8))) {
                    requested = true;
                    break;
                }
                Thread.sleep(100);
            }
            if (!requested) throw new IllegalStateException("Observer stopped without task request");
            context.disposableScope().close();
            Thread.sleep(250);
            final long settled = count.get();
            final boolean snapshotRejected = rejects(stats::snapshot);
            final boolean sampleRejected = rejects(() -> {
                final Registration leaked = stats.sample(Duration.ofHours(1), sample -> { });
                leaked.close();
            });
            Thread.sleep(450);
            publish(state, "state=STOPPED\ncount=" + count.get() + "\nframes=" + frames.get()
                + "\nsnapshotRejected=" + snapshotRejected + "\nsampleRejected=" + sampleRejected
                + "\ncallbacksStopped=" + (count.get() == settled) + "\n");
        } catch (Throwable failure) {
            try { publish(state, "state=FAIL\nfailure=" + failure.getClass().getName() + "\n"); }
            catch (Exception ignored) { context.logger().warn("FPS_OBSERVER_RESULT_WRITE_FAILED"); }
            context.logger().warn("FPS_OBSERVER_FAILED " + failure.getClass().getName());
        } finally {
            try { context.disposableScope().close(); }
            catch (Throwable failure) { context.logger().warn("FPS_OBSERVER_SCOPE_CLOSE_FAILED"); }
        }
    }

    private static boolean rejects(final Runnable action) {
        try { action.run(); return false; }
        catch (IllegalStateException expected) { return true; }
    }

    private static void publish(final Path state, final String text) throws Exception {
        final Path pending = state.resolve("observations.pending");
        final Path target = state.resolve("observations.properties");
        Files.writeString(pending, text, StandardCharsets.UTF_8);
        try { Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
