package dev.turboism.adapter.cubism.performance;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializes a probe capture to a pretty-printed JSON report on disk.
 *
 * <p>The report identifies the exact bytes it describes - host artifact, agent, and
 * fixture digests - so a measurement can never be silently attributed to a different
 * build. Output is capped at 256 KiB and published by writing a sibling {@code .tmp}
 * file and moving it into place, atomically where the filesystem allows.</p>
 */
public final class PerformanceProbeReportWriter {

    private static final int MAX_BYTES = 256 * 1024;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Writes a {@code turboism.cubism.performance-probe} JSON document. Camera/edit retain
     * schema v1; the opt-in images scenario uses v2 with sampled latency bounds and explicit
     * measurement limitations. Creates parent directories and replaces an existing file.
     *
     * <p>The exact Cubism version and artifact digest are supplied by the installer profile
     * that selected the independently reviewed target set.</p>
     *
     * @param output             destination path; resolved to an absolute normalized path
     * @param cubismVersion      exact reviewed Cubism Editor version
     * @param artifactSha256     digest of the instrumented Cubism artifact
     * @param agentSha256        digest of the probe agent that did the instrumenting
     * @param fixtureSha256      digest of the model fixture the scenario ran against
     * @param scenario           name of the measured scenario
     * @param startedEpochMillis when the capture window opened
     * @param endedEpochMillis   when the capture window closed
     * @param snapshot           the counters to report
     * @throws IOException when the encoded report exceeds 256 KiB, or the file cannot be
     *     created, written, or moved into place
     */
    public void write(
        final Path output,
        final String cubismVersion,
        final String artifactSha256,
        final String agentSha256,
        final String fixtureSha256,
        final String scenario,
        final long startedEpochMillis,
        final long endedEpochMillis,
        final PerformanceProbeRecorder.Snapshot snapshot
    ) throws IOException {
        final boolean images = "images".equals(scenario);
        if (!images && !"camera".equals(scenario) && !"edit".equals(scenario)) {
            throw new IllegalArgumentException("unsupported performance probe scenario");
        }
        if (images && (!"5.3.02".equals(cubismVersion)
            || !dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_3_02.sha256().equals(artifactSha256))) {
            throw new IllegalArgumentException("image diagnostics require the exact reviewed 5.3.02 artifact");
        }
        final Map<String, Object> report = new LinkedHashMap<>();
        report.put("format", "turboism.cubism.performance-probe");
        report.put("schemaVersion", images ? 2 : 1);
        report.put("cubismVersion", requireText(cubismVersion, "cubismVersion"));
        report.put("artifactSha256", artifactSha256);
        report.put("agentSha256", agentSha256);
        report.put("fixtureSha256", fixtureSha256);
        report.put("scenario", scenario);
        report.put("capture", Map.of(
            "startEpochMs", startedEpochMillis,
            "endEpochMs", endedEpochMillis,
            "dropped", 0,
            "failures", snapshot.failures()
        ));
        final Map<String, Object> metrics = new LinkedHashMap<>();
        snapshot.metrics().forEach((metric, value) -> {
            if (!images && metric.id() > PerformanceProbeMetric.REINIT_MODEL_INSTANCE_EXE.id()) return;
            final Map<String, Object> reading = new LinkedHashMap<>();
            reading.put("calls", value.calls());
            reading.put("sampled", value.sampled());
            reading.put("totalNanos", value.totalNanos());
            reading.put("maxNanos", value.maxNanos());
            if (images) {
                final PerformanceProbeRecorder.LatencySnapshot latency = value.latency();
                reading.put("latency", Map.of(
                    "samples", latency.samples(),
                    "p50UpperBoundNanos", latency.p50UpperBoundNanos(),
                    "p95UpperBoundNanos", latency.p95UpperBoundNanos(),
                    "p99UpperBoundNanos", latency.p99UpperBoundNanos()
                ));
            }
            metrics.put(metricName(metric), reading);
        });
        report.put("metrics", metrics);
        if (images) report.put("measurement", Map.of(
            "timing", "sampled-inclusive-method-wall-time",
            "quantiles", "base2-bucket-upper-bound",
            "counts", "method-entries-including-failures",
            "gpuTime", "not-measured",
            "uploadBytes", "not-measured"
        ));
        report.put("writtenAt", Instant.now().toString());

        final byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
        if (bytes.length > MAX_BYTES) throw new IOException("performance probe report exceeds 256 KiB");
        final Path target = output.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        final Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static String metricName(final PerformanceProbeMetric metric) {
        final String name = metric.name().toLowerCase(java.util.Locale.ROOT);
        final StringBuilder result = new StringBuilder(name.length());
        boolean upper = false;
        for (int i = 0; i < name.length(); i++) {
            final char character = name.charAt(i);
            if (character == '_') {
                upper = true;
            } else {
                result.append(upper ? Character.toUpperCase(character) : character);
                upper = false;
            }
        }
        return result.toString();
    }
}
