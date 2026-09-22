package dev.turboism.sdk.ui;

/**
 * Explicit position override for a native Cubism drawing-area hint.
 *
 * <p>Coordinates are in the host drawing area's own component space, which is the
 * space the native hint placement already uses. A hint without a position keeps the
 * native lower-right placement, and stacked hints keep their native vertical order.
 * This override exists for callers that must anchor a hint themselves; prefer the
 * default placement unless the message has to point at a specific spot.</p>
 *
 * @param x horizontal position in the drawing area's component space
 * @param y vertical position in the drawing area's component space
 */
public record CanvasHintPosition(float x, float y) {
}
