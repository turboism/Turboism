package dev.turboism.validation.externalpsd;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.management.ObjectName;

/** Test-only bounded export diagnostics. Does not read pixels or mutate the host model. */
final class ExportProfileRecorder implements AutoCloseable {
    private final Path directory;
    private final long start = System.nanoTime();
    private final ScheduledExecutorService executor;
    private final ExternalPsdPerformanceSampler heartbeat;
    private volatile String stage = "starting";
    private int samples;
    private int dumps;
    private boolean closed;
    private Exception observerFailure;

    ExportProfileRecorder(final Path directory) throws Exception {
        this.directory = directory;
        Files.writeString(directory.resolve("export-profile.tsv"),
            "elapsedMs\tstage\theapUsed\theapCommitted\tnonHeapUsed\tmaxEdtDelayMs\tpendingEdtMs\n");
        heartbeat = ExternalPsdPerformanceSampler.start();
        executor = Executors.newSingleThreadScheduledExecutor(work -> {
            final Thread thread = new Thread(work, "external-psd-export-profile");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sample, 0L, 1L, TimeUnit.SECONDS);
    }

    void stage(final String value) { stage = value; }

    /** A live histogram requests GC. Its cost is explicitly outside the export interval. */
    void checkpoint(final String name) throws Exception {
        stage = "live-histogram-" + name;
        final Path output = directory.resolve("export-profile-" + name + "-live.txt");
        try {
            final Object histogram = ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                new Object[] {new String[0]}, new String[] {String[].class.getName()});
            final String text = String.valueOf(histogram);
            Files.writeString(output, "GC_REQUESTED=true\n" + text.substring(0, Math.min(text.length(), 2 * 1024 * 1024)));
        } catch (Exception unavailable) {
            Files.writeString(output, "UNAVAILABLE=" + unavailable.getClass().getName() + "\n");
        }
        stage = name;
    }

    private synchronized void sample() {
        if (closed || samples++ >= 900) return;
        try {
            final var memory = ManagementFactory.getMemoryMXBean();
            final var heap = memory.getHeapMemoryUsage();
            final var pulse = heartbeat.snapshot();
            Files.writeString(directory.resolve("export-profile.tsv"),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + "\t" + stage + "\t"
                    + heap.getUsed() + "\t" + heap.getCommitted() + "\t"
                    + memory.getNonHeapMemoryUsage().getUsed() + "\t"
                    + TimeUnit.NANOSECONDS.toMillis(pulse.maximumQueueDelayNanos()) + "\t"
                    + TimeUnit.NANOSECONDS.toMillis(pulse.pendingHeartbeatAgeNanos()) + "\n",
                StandardOpenOption.APPEND);
            if (samples % 10 != 1 || dumps >= 20) return;
            final StringBuilder text = new StringBuilder("stage=" + stage + "\n");
            for (final var thread : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
                if (text.length() > 256 * 1024) break;
                text.append(thread.getThreadName()).append(' ').append(thread.getThreadState())
                    .append(" lockOwner=").append(thread.getLockOwnerName()).append('\n');
                final var frames = thread.getStackTrace();
                for (int i = 0; i < Math.min(frames.length, 48); i++) text.append("  ").append(frames[i]).append('\n');
            }
            Files.writeString(directory.resolve("export-profile-stack-" + (++dumps) + ".txt"), text);
        } catch (Exception failure) {
            observerFailure = failure;
            try {
                Files.writeString(directory.resolve("export-profile-observer-error.txt"), failure.toString());
            } catch (Exception ignored) { /* Missing evidence is never a successful observation. */ }
        }
    }

    void finish() throws Exception {
        final var result = heartbeat.finish(2000L);
        synchronized (this) {
            if (observerFailure != null) throw new IllegalStateException("Profile observer failed", observerFailure);
            if (samples == 0 || !result.complete()) throw new IllegalStateException("Profile coverage incomplete");
            Files.writeString(directory.resolve("export-profile-summary.txt"), result.toString()
                + "\nacceptance=NOT_CLAIMED\n");
        }
    }

    @Override public synchronized void close() {
        closed = true;
        executor.shutdownNow();
        heartbeat.close();
    }
}
