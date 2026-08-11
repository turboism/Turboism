package dev.turboism.adapter.cubism.performance;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PerformanceProbeReportWriter {

    private static final int MAX_BYTES = 256 * 1024;
    private final ObjectMapper mapper = new ObjectMapper();

    public void write(
        final Path output,
        final String artifactSha256,
        final String agentSha256,
        final String fixtureSha256,
        final String scenario,
        final long startedEpochMillis,
        final long endedEpochMillis,
        final PerformanceProbeRecorder.Snapshot snapshot
    ) throws IOException {
        final Map<String, Object> report = new LinkedHashMap<>();
        report.put("format", "turboism.cubism.performance-probe");
        report.put("schemaVersion", 1);
        report.put("cubismVersion", "5.3.02");
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
        snapshot.metrics().forEach((metric, value) -> metrics.put(metricName(metric), Map.of(
            "calls", value.calls(),
            "sampled", value.sampled(),
            "totalNanos", value.totalNanos(),
            "maxNanos", value.maxNanos()
        )));
        report.put("metrics", metrics);
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
