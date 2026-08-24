package dev.turboism.sdk.appearance;


import java.util.Objects;

/**
 * A plugin's request to install its own appearance over the Editor's.
 *
 * <p>Validated on construction: {@code appearanceId} must be 1-128 non-blank characters,
 * {@code base} and {@code palette} are mandatory, and {@code expectedRevision} must not be
 * negative. Violations throw {@link IllegalArgumentException} or {@link NullPointerException}.
 *
 * @param appearanceId the plugin-chosen identity of this appearance, 1-128 characters
 * @param base the light/dark foundation to build on
 * @param palette the colours to apply
 * @param expectedRevision the {@link AppearanceStatus#revision()} the caller last observed; the
 *     apply is rejected if the current revision has moved on, giving optimistic concurrency
 */
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
