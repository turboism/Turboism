package dev.turboism.bootstrap;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

record AgentOptions(
    Path home,
    String hostClassName,
    Duration detectionTimeout,
    boolean performanceProbeInstall,
    boolean performanceProbeCapture,
    int performanceProbeDelaySeconds,
    int performanceProbeDurationSeconds,
    Path performanceProbeOutput,
    String performanceProbeScenario,
    String performanceProbeAgentSha256,
    String performanceProbeFixtureSha256,
    String performanceProbeRunId,
    Path performanceProbeRollbackOutput
) {

    private static final String DEFAULT_HOST_CLASS = "com.live2d.cubism.CEAppCtrl";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(90);
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    static AgentOptions parse(final String rawOptions, final Path defaultHome) {
        final Map<String, String> values = new LinkedHashMap<>();
        if (rawOptions != null && !rawOptions.isBlank()) {
            for (String token : rawOptions.split(";")) {
                if (token.isBlank()) {
                    continue;
                }
                final int separator = token.indexOf('=');
                if (separator <= 0 || separator == token.length() - 1) {
                    throw new IllegalArgumentException("Agent option must be key=value: " + token);
                }
                final String key = token.substring(0, separator).trim();
                final String value = token.substring(separator + 1).trim();
                if (!key.equals("home") && !key.equals("hostClass") && !key.equals("timeoutSeconds")
                    && !key.equals("performanceProbeInstall") && !key.equals("performanceProbeCapture")
                    && !key.equals("performanceProbeDelaySeconds")
                    && !key.equals("performanceProbeDurationSeconds") && !key.equals("performanceProbeOutput")
                    && !key.equals("performanceProbeScenario")
                    && !key.equals("performanceProbeAgentSha256")
                    && !key.equals("performanceProbeFixtureSha256")
                    && !key.equals("performanceProbeRunId")
                    && !key.equals("performanceProbeRollbackOutput")) {
                    throw new IllegalArgumentException("Unknown Turboism agent option: " + key);
                }
                if (values.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("Duplicate Turboism agent option: " + key);
                }
            }
        }

        final Path home = Path.of(values.getOrDefault("home", defaultHome.toString()))
            .toAbsolutePath()
            .normalize();
        final String hostClass = values.getOrDefault("hostClass", DEFAULT_HOST_CLASS);
        if (hostClass.isBlank()) {
            throw new IllegalArgumentException("hostClass must not be blank");
        }
        final long timeoutSeconds;
        try {
            timeoutSeconds = Long.parseLong(values.getOrDefault(
                "timeoutSeconds",
                Long.toString(DEFAULT_TIMEOUT.toSeconds())
            ));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("timeoutSeconds must be an integer", exception);
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 600) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 600");
        }
        return new AgentOptions(
            home,
            hostClass,
            Duration.ofSeconds(timeoutSeconds),
            parseBoolean(values, "performanceProbeInstall"),
            parseBoolean(values, "performanceProbeCapture"),
            parseBoundedInteger(values, "performanceProbeDelaySeconds", 0, 300, 0),
            parseBoundedInteger(values, "performanceProbeDurationSeconds", 5, 120, 30),
            Path.of(values.getOrDefault(
                "performanceProbeOutput",
                home.resolve("logs/runtime/performance-probe.json").toString()
            )).toAbsolutePath().normalize(),
            values.getOrDefault("performanceProbeScenario", "camera"),
            values.getOrDefault("performanceProbeAgentSha256", "unbound"),
            values.getOrDefault("performanceProbeFixtureSha256", "unbound"),
            values.getOrDefault("performanceProbeRunId", "unbound"),
            rollbackOutput(values)
        );
    }

    private static Path rollbackOutput(final Map<String, String> values) {
        final String configured = values.get("performanceProbeRollbackOutput");
        return configured == null ? null : Path.of(configured).toAbsolutePath().normalize();
    }

    private static boolean parseBoolean(final Map<String, String> values, final String key) {
        final String value = values.getOrDefault(key, "false");
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private static int parseBoundedInteger(
        final Map<String, String> values,
        final String key,
        final int minimum,
        final int maximum,
        final int fallback
    ) {
        final int parsed;
        try {
            parsed = Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer", exception);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    AgentOptions {
        home = Objects.requireNonNull(home, "home");
        hostClassName = Objects.requireNonNull(hostClassName, "hostClassName");
        detectionTimeout = Objects.requireNonNull(detectionTimeout, "detectionTimeout");
        performanceProbeOutput = Objects.requireNonNull(performanceProbeOutput, "performanceProbeOutput");
        performanceProbeScenario = Objects.requireNonNull(performanceProbeScenario, "performanceProbeScenario");
        if (!performanceProbeScenario.equals("camera") && !performanceProbeScenario.equals("edit")) {
            throw new IllegalArgumentException("performanceProbeScenario must be camera or edit");
        }
        performanceProbeAgentSha256 = Objects.requireNonNull(performanceProbeAgentSha256, "performanceProbeAgentSha256");
        performanceProbeFixtureSha256 = Objects.requireNonNull(performanceProbeFixtureSha256, "performanceProbeFixtureSha256");
        if (performanceProbeCapture && !performanceProbeInstall) {
            throw new IllegalArgumentException(
                "performanceProbeCapture requires performanceProbeInstall=true"
            );
        }
        if (performanceProbeCapture) {
            requireBoundSha256(performanceProbeAgentSha256, "performanceProbeAgentSha256");
            requireBoundSha256(performanceProbeFixtureSha256, "performanceProbeFixtureSha256");
        }
        if (!performanceProbeRunId.equals("unbound")
            && !RUN_ID_PATTERN.matcher(performanceProbeRunId).matches()) {
            throw new IllegalArgumentException(
                "performanceProbeRunId must match [A-Za-z0-9][A-Za-z0-9._-]{0,127} or be unbound"
            );
        }
        if (performanceProbeRollbackOutput != null && performanceProbeRunId.equals("unbound")) {
            throw new IllegalArgumentException("performanceProbeRollbackOutput requires performanceProbeRunId");
        }
    }

    private static void requireBoundSha256(final String value, final String key) {
        if (value.equals("unbound") || !SHA256_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                key + " must be a lowercase 64-character SHA-256 when capture is enabled"
            );
        }
    }
}
