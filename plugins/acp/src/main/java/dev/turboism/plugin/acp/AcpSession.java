package dev.turboism.plugin.acp;

import java.util.List;
import java.util.Objects;

/** Active ACP session identity plus the latest agent-owned configuration catalog. */
record AcpSession(
        String sessionId, List<AcpConfigOption> configOptions, AcpClient.AcpCapabilities capabilities) {
    AcpSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank() || sessionId.length() > 512) {
            throw new IllegalArgumentException("sessionId is invalid");
        }
        configOptions = List.copyOf(Objects.requireNonNull(configOptions, "configOptions"));
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    AcpSession(final String sessionId, final List<AcpConfigOption> configOptions) {
        this(sessionId, configOptions, AcpClient.AcpCapabilities.NONE);
    }

    boolean durableSessionsAvailable() {
        return capabilities.loadSession() || capabilities.resumeSession();
    }

    AcpConfigOption option(final String id) {
        return configOptions.stream()
                .filter(option -> option.id().equals(id))
                .findFirst()
                .orElse(null);
    }
}
