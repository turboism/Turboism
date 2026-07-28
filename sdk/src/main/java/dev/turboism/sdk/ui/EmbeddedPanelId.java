package dev.turboism.sdk.ui;

import java.util.Objects;

/** Turboism-owned identity for embedded-panel activation requests. */
public record EmbeddedPanelId(String value) {

    public EmbeddedPanelId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static EmbeddedPanelId of(final String value) {
        return new EmbeddedPanelId(value);
    }
}
