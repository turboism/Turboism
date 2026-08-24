package dev.turboism.sdk.cubism.model;


import java.util.List;
import java.util.Optional;

/** Read-only projection of one animation file-content document. */
public interface AnimationDocument {

    String animationName();

    int sceneCount();

    Optional<String> currentSceneName();

    List<String> sceneNames();
}
