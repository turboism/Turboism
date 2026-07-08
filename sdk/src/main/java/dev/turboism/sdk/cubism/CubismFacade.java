package dev.turboism.sdk.cubism;

import java.util.Optional;

/**
 * Read-only view of the Cubism host exposed to plugins.
 */
public interface CubismFacade {

    CubismRuntimeSnapshot runtime();

    Optional<ProjectSnapshot> activeProject();

    Optional<DocumentSnapshot> activeDocument();

    Optional<ModelSnapshot> activeModel();

    boolean isHostPresent();

    default boolean hasActiveProject() {
        return activeProject().isPresent();
    }

    default boolean hasActiveDocument() {
        return activeDocument().isPresent();
    }

    default boolean hasActiveModel() {
        return activeModel().isPresent();
    }
}
