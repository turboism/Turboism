package dev.turboism.plugin.acp;

import java.util.Objects;

/** Opaque durable ACP session identity plus its agent-provided last-update timestamp. */
record AcpSessionSummary(String sessionId, String updatedAt) {

    AcpSessionSummary {
        sessionId = requireText(sessionId, "sessionId", 512);
        updatedAt = requireText(updatedAt, "updatedAt", 128);
    }

    private static String requireText(final String value, final String name, final int maximum) {
        final String text = Objects.requireNonNull(value, name);
        if (text.isBlank() || text.length() > maximum || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }
}
