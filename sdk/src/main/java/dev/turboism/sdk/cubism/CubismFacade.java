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
        return ActiveReadProjections.animationOf(activeDocument());
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
        return ActiveReadProjections.imageDocumentOf(activeDocument());
    }

    /** Returns the project entry that owns the active document. */
    default Optional<ProjectContentSnapshot> activeProjectContent() {
        return ActiveReadProjections.projectContentOf(activeProject(), activeDocument());
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

    /** Returns complete texture-atlas authoring layout access when installed. */
    @PreviewApi
    default dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService textureAtlasLayouts() {
        throw new UnsupportedOperationException(
            "Texture atlas layout service is unavailable"
        );
    }

    /** Returns read access to the active native texture-atlas editor session. */
    @PreviewApi
    default dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession textureAtlasEditorSession() {
        throw new UnsupportedOperationException(
            "Texture atlas editor session is unavailable"
        );
    }

    /** Returns UI contribution access to the native texture-atlas editor window. */
    @PreviewApi
    default dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi textureAtlasEditorUi() {
        throw new UnsupportedOperationException(
            "Texture atlas editor UI contribution is unavailable"
        );
    }

    /** Returns the registry of registered texture-atlas layout algorithms. */
    @PreviewApi
    default dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms() {
        throw new UnsupportedOperationException(
            "Texture atlas algorithm registry is unavailable"
        );
    }

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
