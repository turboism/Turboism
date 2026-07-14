package dev.turboism.bootstrap;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

record AgentOptions(Path home, String hostClassName, Duration detectionTimeout) {

    private static final String DEFAULT_HOST_CLASS = "com.live2d.cubism.CEAppCtrl";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(90);

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
                if (!key.equals("home") && !key.equals("hostClass") && !key.equals("timeoutSeconds")) {
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
        return new AgentOptions(home, hostClass, Duration.ofSeconds(timeoutSeconds));
    }

    AgentOptions {
        home = Objects.requireNonNull(home, "home");
        hostClassName = Objects.requireNonNull(hostClassName, "hostClassName");
        detectionTimeout = Objects.requireNonNull(detectionTimeout, "detectionTimeout");
    }
}
