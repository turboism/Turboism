package dev.turboism.sdk.ui;

public record ViewportSnapshot(String viewportId, int width, int height, double zoom) {
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
