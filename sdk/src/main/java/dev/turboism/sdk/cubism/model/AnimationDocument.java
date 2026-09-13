package dev.turboism.sdk.cubism.model;


import java.util.List;
import java.util.Optional;

/** Read-only projection of one animation file-content document. */
public interface AnimationDocument {

    String animationName();

    int sceneCount();

    Optional<String> currentSceneName();

    List<String> sceneNames();

    /**
     * Returns the scenes with their timelines, tracks, and keyframes, in the
     * animation's scene order. The default rejects the deep read when the
     * active provider lacks exact animation-timeline host mapping.
     */
    default List<AnimationScene> scenes() {
        throw new UnsupportedOperationException(
            "Animation scene timelines are unavailable without exact verified host evidence."
        );
    }
}
