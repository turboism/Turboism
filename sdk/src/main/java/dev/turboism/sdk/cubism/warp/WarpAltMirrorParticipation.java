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

    /**
     * Joins the alt-mirror policy for this session.
     *
     * @return a registration whose close withdraws participation and disarms
     *     any axis this participant armed
     */
    Registration participate();

    /**
     * Arms or disarms the mirror axis: 0 = off, 1 = vertical grid axis,
     * 2 = horizontal grid axis. While armed, every committed Warp control-point
     * drag is mirrored across the armed axis.
     */
    default void setArmedAxis(final int axis) {
    }

    /**
     * @return whether the reviewed native drag-tick hook is installed and bound in
     *     this session. Plugins use this to decide whether their own fallback
     *     mirroring (for example an AWT-level path) must stay active.
     */
    boolean nativeMirrorActive();

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static WarpAltMirrorParticipation unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
    enum Unavailable implements WarpAltMirrorParticipation {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public Registration participate() {
            throw unavailableFailure();
        }

        @Override public void setArmedAxis(final int axis) {
            throw unavailableFailure();
        }

        @Override public boolean nativeMirrorActive() {
            throw unavailableFailure();
        }

        private static UnsupportedOperationException unavailableFailure() {
            return new UnsupportedOperationException(
                "warpAltMirrorParticipation service is not available");
        }
    }
}
