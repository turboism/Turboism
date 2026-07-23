package dev.turboism.sdk.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

@PreviewApi
public record AppearanceRequest(
    String appearanceId,
    AppearanceBase base,
    AppearancePalette palette,
    long expectedRevision
) {
    public AppearanceRequest {
        Objects.requireNonNull(appearanceId, "appearanceId");
        if (appearanceId.isBlank() || appearanceId.length() > 128) {
            throw new IllegalArgumentException("appearanceId must contain 1-128 characters");
        }
        base = Objects.requireNonNull(base, "base");
        palette = Objects.requireNonNull(palette, "palette");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }
}
