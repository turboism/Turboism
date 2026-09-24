package dev.turboism.sdk.cubism.mirror;

/**
 * Direction of a whole-object Warp Deformer mirror copy.
 *
 * <p>Directions name the visible outcome: {@link #LEFT_TO_RIGHT} copies the half of the grid on
 * the left of the mirror axis onto the right half, and so on. The axis is the line through the
 * control-point bounding-box center; horizontal directions use a horizontal axis, vertical
 * directions use a vertical axis.</p>
 */
public enum WarpMirrorDirection {
    /** Source = left of the vertical axis; the right half is overwritten by its mirror. */
    LEFT_TO_RIGHT,
    /** Source = right of the vertical axis; the left half is overwritten by its mirror. */
    RIGHT_TO_LEFT,
    /** Source = above the horizontal axis; the lower half is overwritten by its mirror. */
    TOP_TO_BOTTOM,
    /** Source = below the horizontal axis; the upper half is overwritten by its mirror. */
    BOTTOM_TO_TOP
}
