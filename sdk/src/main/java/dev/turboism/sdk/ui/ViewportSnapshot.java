package dev.turboism.sdk.ui;

/**
 * Immutable reading of the modeling viewport at one moment.
 *
 * @param viewportId host identity of the viewport, non-blank
 * @param width      viewport width in pixels, at least 1
 * @param height     viewport height in pixels, at least 1
 * @param zoom       viewport zoom factor, strictly positive
 */
public record ViewportSnapshot(String viewportId, int width, int height, double zoom) {
    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException when the id is null or blank, a dimension is
     *     below 1, or {@code zoom} is not positive
     */
    public ViewportSnapshot {
        if (viewportId == null || viewportId.isBlank()) {
            throw new IllegalArgumentException("viewportId must not be null or blank");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("viewport dimensions must be positive");
        }
        if (zoom <= 0.0) {
            throw new IllegalArgumentException("zoom must be positive");
        }
    }
}
