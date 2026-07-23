package dev.turboism.sdk.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

@PreviewApi
public record AppearanceStatus(
    Availability availability,
    Source source,
    Optional<String> appearanceId,
    AppearanceBase base,
    long revision,
    Optional<String> diagnosticId
) {
    public AppearanceStatus {
        availability = Objects.requireNonNull(availability, "availability");
        source = Objects.requireNonNull(source, "source");
        appearanceId = text(appearanceId, "appearanceId");
        base = Objects.requireNonNull(base, "base");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        diagnosticId = text(diagnosticId, "diagnosticId");
    }

    public enum Availability {
        AVAILABLE,
        UNAVAILABLE,
        UNSUPPORTED,
        SAFE_MODE
    }

    public enum Source {
        NATIVE,
        PLUGIN_OVERLAY
    }

    private static Optional<String> text(final Optional<String> value, final String name) {
        Objects.requireNonNull(value, name);
        return value.map(text -> {
            if (text.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return text;
        });
    }
}
