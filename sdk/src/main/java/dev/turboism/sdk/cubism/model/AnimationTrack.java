package dev.turboism.sdk.cubism.model;

import java.util.List;
import java.util.Optional;

/** One track on an {@link AnimationScene} timeline. */
public interface AnimationTrack {

    /** Host-assigned track guid string. */
    String guid();

    /** Track name shown on the timeline. */
    String name();

    AnimationTrackKind kind();

    /** First frame occupied by this track, in scene frames. */
    int startFrame();

    /** Track length in frames. */
    int durationFrames();

    /** Frame positions where this track's placement has explicit keys. */
    List<Integer> keyframeFrames();

    boolean visible();

    boolean editable();

    boolean muted();

    boolean repeat();

    /**
     * Child tracks in display order. Non-{@link AnimationTrackKind#GROUP}
     * tracks return an empty list.
     */
    List<AnimationTrack> children();

    /** Animated attributes contributed by this track's effects. */
    List<AnimationAttribute> attributes();

    /** Guid of the linked model source, for {@link AnimationTrackKind#LIVE2D_MODEL}. */
    Optional<String> linkedModelGuid();

    /** Guid of the referenced scene, for {@link AnimationTrackKind#SCENE}. */
    Optional<String> linkedSceneGuid();
}
