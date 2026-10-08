package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Exact-host performance evidence probe: a manual-test-only SDK plugin that
 * resolves {@link PerformanceProbeService} through {@link PluginContext#services()}
 * and appends every snapshot to {@code state/perf-metrics.csv} as one CSV row.
 *
 * <p>The probe touches only the performance service, {@code paths()},
 * {@code logger()}, and {@code tasks()}; it never touches runtime, reflection,
 * or host objects. Sampling starts on {@link #enable()} and stops on
 * {@link #disable()}/{@link #shutdown()}; closing the registration also
 * unmounts the agent-side render counter when no other sampler remains.</p>
 *
 * <p>Optional phase annotation: when {@code state/perf-phase.txt} exists its
 * first line (max 64 bytes, sanitized to printable ASCII) is written as the
 * row's {@code phase} column, so an external driver can tag workload phases
 * without restarting the editor. The read happens once per sample tick.</p>
 *
 * <p>CSV columns: {@code epoch_ms,phase,cpu_percent,fps,rendered_frames,
 * heap_used_bytes,nonheap_bytes,gc_collections,gc_pause_millis}.</p>
 */
public final class PerformanceMetricsProbe implements CubismPlugin {

    static final String METRICS_FILE = "perf-metrics.csv";
    static final String PHASE_FILE = "perf-phase.txt";
    static final String INTERVAL_PROPERTY = "turboism.perfMetrics.intervalMillis";
    static final int MAX_PHASE_BYTES = 64;
    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);
    private static final String HEADER = "epoch_ms,phase,cpu_percent,fps,rendered_frames,"
            + "heap_used_bytes,nonheap_bytes,gc_collections,gc_pause_millis";

    private final Object lifecycle = new Object();
    private PluginContext context;
    private Registration sampling;
    private BufferedWriter writer;
    private Path metricsPath;
    private Path phasePath;
    private boolean enabled;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        context.logger().info("Performance metrics probe initialized");
    }

    @Override
    public void enable() {
        final PluginContext current = Objects.requireNonNull(context, "context");
        synchronized (lifecycle) {
            if (enabled) {
                return;
            }
            enabled = true;
        }
        try {
            final Path stateDir = current.paths().stateDir();
            Files.createDirectories(stateDir);
            metricsPath = stateDir.resolve(METRICS_FILE);
            phasePath = stateDir.resolve(PHASE_FILE);
            final boolean writeHeader = !Files.exists(metricsPath) || Files.size(metricsPath) == 0L;
            writer = Files.newBufferedWriter(
                    metricsPath,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            if (writeHeader) {
                writer.write(HEADER);
                writer.newLine();
                writer.flush();
            }
            final PerformanceProbeService stats = current.services()
                    .find(PerformanceProbeService.class)
                    .orElseGet(PerformanceProbeService::unavailable);
            sampling = stats.sample(interval(), this::append);
            current.logger().info("Performance metrics sampling -> " + metricsPath);
        } catch (RuntimeException | IOException failure) {
            current.logger()
                    .warn("Performance metrics probe failed to enable: "
                            + failure.getClass().getSimpleName() + " " + failure.getMessage());
            synchronized (lifecycle) {
                enabled = false;
            }
            closeQuietly();
        }
    }

    @Override
    public void disable() {
        stop();
    }

    @Override
    public void shutdown() {
        stop();
    }

    private void stop() {
        synchronized (lifecycle) {
            if (!enabled) {
                return;
            }
            enabled = false;
        }
        closeQuietly();
    }

    private void closeQuietly() {
        final Registration active = sampling;
        sampling = null;
        if (active != null) {
            try {
                active.close();
            } catch (RuntimeException ignored) {
                // Best effort: the runtime scope also cleans the registration.
            }
        }
        final BufferedWriter open = writer;
        writer = null;
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // Best effort: a broken writer must not fail disable.
            }
        }
    }

    private Duration interval() {
        final String raw = System.getProperty(INTERVAL_PROPERTY, "");
        if (raw.isBlank()) {
            return DEFAULT_INTERVAL;
        }
        try {
            final long millis = Long.parseLong(raw.trim());
            return millis > 0 ? Duration.ofMillis(millis) : DEFAULT_INTERVAL;
        } catch (NumberFormatException invalid) {
            return DEFAULT_INTERVAL;
        }
    }

    private void append(final PerformanceSnapshot snapshot) {
        final BufferedWriter open = writer;
        if (open == null) {
            return;
        }
        try {
            open.write(String.format(
                    Locale.ROOT,
                    "%d,%s,%.3f,%.3f,%d,%d,%d,%d,%d",
                    snapshot.timestampEpochMs(),
                    phase(),
                    snapshot.cpuPercent(),
                    snapshot.fps(),
                    snapshot.renderedFrames(),
                    snapshot.jvmHeapBytes(),
                    snapshot.jvmNonHeapBytes(),
                    snapshot.gcCollections(),
                    snapshot.gcPauseMillis()));
            open.newLine();
            open.flush();
        } catch (IOException failure) {
            if (context != null) {
                context.logger()
                        .warn("Performance metrics write failed: "
                                + failure.getClass().getSimpleName());
            }
        }
    }

    private String phase() {
        final Path marker = phasePath;
        if (marker == null) {
            return "";
        }
        try {
            if (!Files.isRegularFile(marker) || Files.size(marker) > MAX_PHASE_BYTES) {
                return "";
            }
            final String raw = Files.readString(marker, StandardCharsets.UTF_8)
                    .lines()
                    .findFirst()
                    .orElse("");
            final StringBuilder clean = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length(); i++) {
                final char c = raw.charAt(i);
                if (c >= 0x21 && c <= 0x7E && c != ',') {
                    clean.append(c);
                }
            }
            return clean.toString();
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }
}
