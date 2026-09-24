package dev.turboism.sdk.cubism.mirror;

import java.util.List;

/**
 * Whole-object Warp Deformer mirror operations (BoundingBox overlay mirror workflow).
 *
 * <p>One call mirrors one Warp Deformer's current keyform across the control-point
 * bounding-box center axis in the requested direction. When the request asks to preserve
 * descendants, the parent write and all descendant compensation writes commit in a single
 * host transaction (one Undo entry); the operation rejects without writing when it cannot
 * prove that every descendant's evaluated canvas geometry will be preserved.</p>
 *
 * <p>Implementations bridge to the Cubism host. The default {@link #unavailable()}
 * implementation returns {@link WarpMirrorOutcome#BLOCKED} with
 * {@link WarpMirrorBlockerCode#UNAVAILABLE}.</p>
 */
public interface WarpMirrorService {

    /**
     * Mirrors one whole Warp Deformer.
     *
     * @param request target, direction, and descendant-preservation flag
     * @return the typed outcome; never {@code null}
     */
    WarpMirrorResult apply(WarpMirrorRequest request);

    /** Returns a service that reports every request as unavailable. */
    static WarpMirrorService unavailable() {
        return request -> WarpMirrorResult.blocked(List.of(new WarpMirrorBlocker(
            WarpMirrorBlockerCode.UNAVAILABLE,
            "Warp mirror is unavailable on this host.")));
    }
}
