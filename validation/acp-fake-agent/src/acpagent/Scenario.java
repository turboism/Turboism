package acpagent;

import dev.turboism.sdk.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Scenario configuration loaded from the {@code bridgeConfig} properties file. */
final class Scenario {

    final Path resultFile;
    final Path tokenFile;
    final String objectKind;
    final String newNamePrefix;
    final long stepTimeoutSeconds;
    final String runId;

    private Scenario(
            final Path resultFile,
            final Path tokenFile,
            final String objectKind,
            final String newNamePrefix,
            final long stepTimeoutSeconds,
            final String runId) {
        this.resultFile = resultFile;
        this.tokenFile = tokenFile;
        this.objectKind = objectKind;
        this.newNamePrefix = newNamePrefix;
        this.stepTimeoutSeconds = stepTimeoutSeconds;
        this.runId = runId;
    }

    /**
     * Loads the scenario from the {@code turboism.acp.validation.bridgeConfig} system property.
     * Missing optional keys fall back to task-layout defaults; an absent run id falls back to a
     * value the probe rejects, keeping the run fail-closed.
     */
    static Scenario load() throws IOException {
        final String configured = System.getProperty("turboism.acp.validation.bridgeConfig", "");
        final Path workingDirectory = Path.of("").toAbsolutePath();
        Properties properties = new Properties();
        if (!configured.isBlank()) {
            final Path file = Path.of(configured);
            if (Files.isRegularFile(file)) {
                try (InputStream input = Files.newInputStream(file)) {
                    properties.load(input);
                }
            }
        }
        return new Scenario(
                path(properties.getProperty("resultFile"), workingDirectory.resolve("agent-result.properties")),
                path(
                        properties.getProperty("tokenFile"),
                        workingDirectory.resolve("../dev.turboism.plugin.mcp/mcp.token").normalize()),
                blankToNull(properties.getProperty("objectKind")),
                properties.getProperty("newNamePrefix", "ACP-Validated-Rename"),
                positive(properties.getProperty("stepTimeoutSeconds"), 120L),
                System.getenv().getOrDefault("TURBOISM_ACP_FAKE_RUN_ID", "missing-run-id"));
    }

    /** Builds the rename target name; unique per run so stale states cannot pass the readback. */
    String newName() {
        final String suffix = runId.length() > 8 ? runId.substring(runId.length() - 8) : runId;
        return newNamePrefix + "-" + suffix + "-" + (System.currentTimeMillis() % 100_000L);
    }

    private static Path path(final String value, final Path fallback) {
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static long positive(final String value, final long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            final long parsed = Long.parseLong(value.strip());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
