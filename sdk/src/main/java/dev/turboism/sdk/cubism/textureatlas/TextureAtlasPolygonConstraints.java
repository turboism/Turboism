package dev.turboism.sdk.cubism.textureatlas;

import java.util.Objects;

/**
 * Constraints for polygon packing into the current atlas page.
 *
 * <p>{@code margin} is the minimum distance kept between packed outlines and the
 * page edge. {@code requestedScale} is a fixed uniform scale applied to every
 * free item; {@code 0} selects automatic scaling, where the planner searches the
 * largest scale whose plan still fits. Rotation freedom is bounded by
 * {@code rotationMode}; per-item {@code preserveAngle}/{@code preserveScale} flags
 * further restrict it.</p>
 */
public record TextureAtlasPolygonConstraints(
    int pageWidth,
    int pageHeight,
    int margin,
    TextureAtlasRotationMode rotationMode,
    double requestedScale,
    TextureAtlasLayoutBackend backend,
    TextureAtlasLayoutQuality quality
) {

    public TextureAtlasPolygonConstraints {
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException("pageWidth/pageHeight must be positive");
        }
        if (margin < 0) {
            throw new IllegalArgumentException("margin must be >= 0");
        }
        if (!(requestedScale >= 0) || !Double.isFinite(requestedScale)) {
            throw new IllegalArgumentException("requestedScale must be >= 0 (0 = automatic)");
        }
        Objects.requireNonNull(rotationMode, "rotationMode");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(quality, "quality");
    }

    public boolean automaticScale() {
        return requestedScale == 0;
    }
}
