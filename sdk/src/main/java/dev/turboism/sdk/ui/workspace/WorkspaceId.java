package dev.turboism.sdk.ui.workspace;


import java.util.Objects;

/**
 * Identifies one Editor workspace layout by its host-side key.
 *
 * <p>The value is validated at construction: it is never {@code null} and never blank, so any
 * {@code WorkspaceId} instance that exists is usable as a lookup key.
 *
 * @param value the host's workspace key, non-null and non-blank
 */
public record WorkspaceId(String value) {
    public WorkspaceId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}
