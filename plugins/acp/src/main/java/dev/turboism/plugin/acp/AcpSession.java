package dev.turboism.plugin.acp;

import java.util.List;
import java.util.Objects;

/** Active ACP session identity plus the latest agent-owned configuration catalog. */
record AcpSession(
        String sessionId,
        List<AcpConfigOption> configOptions,
        AcpClient.AcpCapabilities capabilities,
        AcpClient.McpAttachment mcpAttachment) {
    AcpSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank() || sessionId.length() > 512) {
            throw new IllegalArgumentException("sessionId is invalid");
        }
        configOptions = List.copyOf(Objects.requireNonNull(configOptions, "configOptions"));
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        mcpAttachment = Objects.requireNonNull(mcpAttachment, "mcpAttachment");
    }

    AcpSession(
            final String sessionId,
            final List<AcpConfigOption> configOptions,
            final AcpClient.AcpCapabilities capabilities) {
        this(sessionId, configOptions, capabilities, AcpClient.McpAttachment.NONE);
    }

    AcpSession(final String sessionId, final List<AcpConfigOption> configOptions) {
        this(sessionId, configOptions, AcpClient.AcpCapabilities.NONE);
    }

    AcpSession withConfigOptions(final List<AcpConfigOption> options) {
        return new AcpSession(sessionId, options, capabilities, mcpAttachment);
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
