package dev.turboism.plugin.turboismwithfx;

import java.util.List;
import java.util.Objects;

/** Active durable fx session identity plus the latest fx-owned configuration catalog. */
record FxAcpSession(
    String sessionId,
    List<FxAcpConfigOption> configOptions,
    FxAcpClient.FxAcpCapabilities capabilities
) {
    FxAcpSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank() || sessionId.length() > 512) {
            throw new IllegalArgumentException("sessionId is invalid");
        }
        configOptions = List.copyOf(Objects.requireNonNull(configOptions, "configOptions"));
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    FxAcpSession(
        final String sessionId,
        final List<FxAcpConfigOption> configOptions
    ) {
        this(sessionId, configOptions, FxAcpClient.FxAcpCapabilities.NONE);
    }

    boolean durableSessionsAvailable() {
        return capabilities.loadSession() && capabilities.listSessions();
    }

    FxAcpConfigOption option(final String id) {
        return configOptions.stream().filter(option -> option.id().equals(id)).findFirst().orElse(null);
    }
}
