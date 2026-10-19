package dev.turboism.plugin.acp;

import java.util.Objects;

/** Opaque durable ACP session identity and optional agent-provided display metadata. */
record AcpSessionSummary(String sessionId, String title, String updatedAt) {

    AcpSessionSummary(final String sessionId, final String updatedAt) {
        this(sessionId, "", updatedAt);
    }

    AcpSessionSummary {
        sessionId = requireText(sessionId, "sessionId", 512);
        title = Objects.requireNonNullElse(title, "");
        updatedAt = Objects.requireNonNullElse(updatedAt, "");
    }

    private static String requireText(final String value, final String name, final int maximum) {
        final String text = Objects.requireNonNull(value, name);
        if (text.isBlank() || text.length() > maximum || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }
}
