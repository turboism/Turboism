package dev.turboism.sdk.cubism;

/**
 * Immutable snapshot of the host viewport's rendering state.
 *
 * @param rendering whether the viewport was actively drawing frames at capture time; when false
 *     {@code framesPerSecond} typically reports the last measured rate rather than zero
 * @param framesPerSecond measured frame rate; must not be negative
 * @param rendererName host-reported name of the renderer backend; must not be blank
 */
public record RenderStatusSnapshot(
    boolean rendering,
    double framesPerSecond,
    String rendererName
) {
    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException if {@code framesPerSecond} is negative, or {@code rendererName}
     *     is null or blank
     */
    public RenderStatusSnapshot {
        if (framesPerSecond < 0.0) {
            throw new IllegalArgumentException("framesPerSecond must not be negative");
        }
        if (rendererName == null || rendererName.isBlank()) {
            throw new IllegalArgumentException("rendererName must not be null or blank");
        }
    }
}
