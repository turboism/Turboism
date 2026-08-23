package dev.turboism.sdk.cubism.recentfile;


import java.util.Objects;

/** Opaque, cross-session-stable identity of a recently opened Cubism project file. */
public record RecentFileId(String value) {
    public RecentFileId {
        value = Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (value.length() > 128) {
            throw new IllegalArgumentException("value must not exceed 128 characters");
        }
    }
}
