package dev.turboism.sdk.cubism.mirror;

import dev.turboism.sdk.cubism.id.DeformerId;

import java.util.Objects;

/**
 * One whole-object Warp Deformer mirror request.
 *
 * <p>The operation mirrors the deformer grid of {@code target} across the axis through the
 * control-point bounding-box center in {@code direction}, writing the object's current keyform.
 * When {@code preserveDescendants} is {@code true}, every descendant's evaluated canvas geometry
 * must remain unchanged; if the runtime cannot prove that, the operation rejects instead of
 * writing partially.</p>
 *
 * @param target host id of the Warp Deformer to mirror
 * @param direction which half of the grid is mirrored onto the other
 * @param preserveDescendants whether descendant geometry is compensated to stay in place
 */
public record WarpMirrorRequest(
    DeformerId target,
    WarpMirrorDirection direction,
    boolean preserveDescendants
) {
    public WarpMirrorRequest {
        target = Objects.requireNonNull(target, "target");
        direction = Objects.requireNonNull(direction, "direction");
    }
}
