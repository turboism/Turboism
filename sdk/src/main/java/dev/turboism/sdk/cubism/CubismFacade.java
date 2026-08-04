package dev.turboism.sdk.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import java.util.Optional;

/** View of the Cubism host exposed to plugins. */
public interface CubismFacade {

    CubismRuntimeSnapshot runtime();

    Optional<ProjectSnapshot> activeProject();

    Optional<DocumentSnapshot> activeDocument();

    /**
     * Returns the model owned by the active MODEL document only.
     *
     * <p>This is empty for animation scene, game-data, physics, and other documents, even when
     * those documents reference or preview a Live2D model.</p>
     */
    Optional<ModelSnapshot> activeModel();

    /** Returns the animation file that owns the active ANIMATION_SCENE document. */
    default Optional<AnimationSnapshot> activeAnimation() {
        return activeDocument().flatMap(DocumentSnapshot::animation);
    }

    /** Returns the active document only when it is a modeling document. */
    default Optional<DocumentSnapshot> activeModelDocument() {
        return activeDocument().filter(DocumentSnapshot::isModelDocument);
    }

    /** Returns the active document only when it is an animation scene document. */
    default Optional<DocumentSnapshot> activeAnimationDocument() {
        return activeDocument().filter(DocumentSnapshot::isAnimationDocument);
    }

    /** Returns the active document only when it is a layered image/PSD document. */
    default Optional<DocumentSnapshot> activeImageDocument() {
        return activeDocument().filter(DocumentSnapshot::isImageDocument);
    }

    /** Returns the project entry that owns the active document. */
    default Optional<ProjectContentSnapshot> activeProjectContent() {
        final Optional<DocumentSnapshot> document = activeDocument();
        if (document.isEmpty() || document.orElseThrow().contentId().isEmpty()) {
            return Optional.empty();
        }
        final String contentId = document.orElseThrow().contentId().orElseThrow();
        return activeProject().flatMap(project -> project.content(contentId));
    }

    boolean isHostPresent();

    /** Returns permission-checked Cubism Core metadata and MOC inspection. */
    @PreviewApi
    default CoreRuntimeInfo coreRuntime() {
        throw new UnsupportedOperationException(
            "Cubism Core runtime metadata is unavailable."
        );
    }

    /**
     * Returns the unified model object API.
     *
     * <p>The default keeps existing implementations source-compatible until a
     * Runtime backend is installed.</p>
     */
    @PreviewApi
    default CubismModelAccess model() {
        throw new UnsupportedOperationException(
            "Unified Cubism model access is unavailable"
        );
    }

    /** Returns the legacy transaction manager for Preview compatibility. */
    @PreviewApi
    TransactionManager transactionManager();

    default boolean hasActiveProject() {
        return activeProject().isPresent();
    }

    default boolean hasActiveDocument() {
        return activeDocument().isPresent();
    }

    default boolean hasActiveModel() {
        return activeModel().isPresent();
    }

    default boolean hasActiveAnimation() {
        return activeAnimation().isPresent();
    }

    default boolean hasActiveImageDocument() {
        return activeImageDocument().isPresent();
    }
}
