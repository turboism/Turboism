package dev.turboism.sdk.cubism.warp;

import dev.turboism.sdk.plugin.Registration;

/**
 * Enables runtime policy that mirrors Warp Deformer control-point drags across a
 * grid axis while the operator holds Alt (vertical axis) or Alt+Shift (horizontal
 * axis).
 *
 * <p>Participating activates the native drag-tick bridge when the reviewed host
 * hook is installed; without participation the bridge stays inert.</p>
 */
public interface WarpAltMirrorParticipation {

    Registration participate();

    /**
     * Arms or disarms the mirror axis: 0 = off, 1 = vertical grid axis,
     * 2 = horizontal grid axis. While armed, every committed Warp control-point
     * drag is mirrored across the armed axis.
     */
    default void setArmedAxis(final int axis) {
    }

    /**
     * Publishes the live Ctrl state tracked at the AWT level. While Ctrl is held
     * the native drag is self-only (no content deformation), so the mirror does
     * not apply.
     */
    default void setLiveCtrlDown(final boolean down) {
    }

    /**
     * @return whether the reviewed native drag-tick hook is installed and bound in
     *     this session. Plugins use this to decide whether their own fallback
     *     mirroring (for example an AWT-level path) must stay active.
     */
    boolean nativeMirrorActive();
}
