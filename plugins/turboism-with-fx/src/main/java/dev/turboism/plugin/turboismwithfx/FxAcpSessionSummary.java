package dev.turboism.plugin.turboismwithfx;

import java.util.Objects;

/** Opaque durable fx session identity plus its ACP-provided last-update timestamp. */
record FxAcpSessionSummary(String sessionId, String updatedAt) {

    FxAcpSessionSummary {
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
