package dev.turboism.sdk.cubism;

public record RenderStatusSnapshot(
    boolean rendering,
    double framesPerSecond,
    String rendererName
) {
    public RenderStatusSnapshot {
        if (framesPerSecond < 0.0) {
            throw new IllegalArgumentException("framesPerSecond must not be negative");
        }
        if (rendererName == null || rendererName.isBlank()) {
            throw new IllegalArgumentException("rendererName must not be null or blank");
        }
    }
}
