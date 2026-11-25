package dev.turboism.sdk.cubism.temporarydeform;

import dev.turboism.sdk.CubismEditor;

/**
 * Canvas-space pointer input delivered to an active {@link TemporaryDeformSession}.
 *
 * @param canvasX pointer horizontal coordinate in modeling canvas space
 * @param canvasY pointer vertical coordinate in modeling canvas space
 * @param hitRadius camera-scaled hit radius in canvas units
 * @param shiftDown whether Shift is held during this event
 * @param controlDown whether Control is held during this event
 * @param altDown whether Alt is held during this event
 */
@CubismEditor(from = "5.0.00")
public record TemporaryDeformPointerEvent(
        float canvasX, float canvasY, float hitRadius, boolean shiftDown, boolean controlDown, boolean altDown) {

    public TemporaryDeformPointerEvent {
        if (!Float.isFinite(canvasX) || !Float.isFinite(canvasY)) {
            throw new IllegalArgumentException("canvas coordinates must be finite");
        }
        if (!Float.isFinite(hitRadius) || hitRadius <= 0.0F) {
            throw new IllegalArgumentException("hitRadius must be finite and positive");
        }
    }
}
