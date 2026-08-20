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

    /**
     * @param value the panel identity string, non-blank
     * @return the wrapped panel id
     * @throws NullPointerException when {@code value} is {@code null}
     * @throws IllegalArgumentException when {@code value} is blank
     */
    public static EmbeddedPanelId of(final String value) {
        return new EmbeddedPanelId(value);
    }
}
