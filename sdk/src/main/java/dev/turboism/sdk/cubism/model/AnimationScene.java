package dev.turboism.sdk.cubism.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-only projection of one scene inside an {@link AnimationDocument}. */
public interface AnimationScene {

    /** Scene name shown in the scene list. */
    String name();

    /** Host-assigned scene guid string. */
    String guid();

    /** User tag attached to the scene, when set. */
    Optional<String> tag();

    /** Scene markers as frame → label, in marker order. */
    Map<Integer, String> markers();

    /** First timeline frame of the scene. */
    int startFrame();

    /** Scene length in frames. */
    int durationFrames();

    /** Timeline frames per second. */
    double framesPerSecond();

    /** Output frame width in pixels. */
    int width();

    /** Output frame height in pixels. */
    int height();

    /** Whether the scene loops during playback. */
    boolean loopMotion();

    /** Work-area start frame. */
    int workspaceStartFrame();

    /** Work-area end frame. */
    int workspaceEndFrame();

    /** Root-level timeline tracks in display order. */
    List<AnimationTrack> tracks();

    /**
     * Current playhead position in frames, read from the first live scene
     * instance.
     *
     * @throws IllegalStateException when the scene has no live instance
     * @throws UnsupportedOperationException when playback state access lacks
     *         exact verified host evidence
     */
    default int playheadFrame() {
        throw new UnsupportedOperationException(
            "Animation scene playhead access is unavailable without exact verified host evidence."
        );
    }

    /**
     * Moves the playhead to {@code frame} on every live scene instance and
     * repaints the canvas. Playhead position is view state, not an undoable
     * document edit.
     *
     * @throws UnsupportedOperationException when playback control lacks exact
     *         verified host evidence
     */
    default void seekTo(final int frame) {
        throw new UnsupportedOperationException(
            "Animation scene playback control is unavailable without exact verified host evidence."
        );
    }

    /**
     * Whether this scene is the animation file's current scene document.
     *
     * @throws UnsupportedOperationException when scene management lacks exact
     *         verified host evidence
     */
    default boolean current() {
        throw new UnsupportedOperationException(
            "Animation scene activation state is unavailable without exact verified host evidence."
        );
    }

    /**
     * Makes this scene the animation's current scene. Scene activation is
     * view state, not an undoable document edit.
     *
     * @throws UnsupportedOperationException when scene management lacks exact
     *         verified host evidence
     */
    default void activate() {
        throw new UnsupportedOperationException(
            "Animation scene activation is unavailable without exact verified host evidence."
        );
    }

    /**
     * The curve type the host applies to freshly created parameter keyframes
     * on this scene.
     *
     * @throws UnsupportedOperationException when scene curve defaults lack
     *         exact verified host evidence
     */
    default AnimationCurveType defaultCurveType() {
        throw new UnsupportedOperationException(
            "Animation scene curve defaults are unavailable without exact verified host evidence."
        );
    }

    /**
     * Sets the curve type the host applies to freshly created parameter
     * keyframes on this scene, in one host undo step.
     *
     * @throws UnsupportedOperationException when scene curve defaults lack
     *         exact verified host evidence
     */
    default void setDefaultCurveType(final AnimationCurveType curveType) {
        java.util.Objects.requireNonNull(curveType, "curveType");
        throw new UnsupportedOperationException(
            "Animation scene curve defaults are unavailable without exact verified host evidence."
        );
    }

    /**
     * Renames this scene in one host undo step.
     *
     * @throws IllegalArgumentException when the name is blank
     * @throws UnsupportedOperationException when scene writes lack exact
     *         verified host evidence
     */
    default void rename(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("scene name must be non-blank");
        }
        throw new UnsupportedOperationException(
            "Animation scene renaming is unavailable without exact verified host evidence."
        );
    }
}
