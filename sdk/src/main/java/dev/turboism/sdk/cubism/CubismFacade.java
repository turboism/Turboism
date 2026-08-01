package dev.turboism.sdk.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import java.util.Optional;

/** View of the Cubism host exposed to plugins. */
public interface CubismFacade {

    CubismRuntimeSnapshot runtime();

    Optional<ProjectSnapshot> activeProject();

    Optional<DocumentSnapshot> activeDocument();

    Optional<ModelSnapshot> activeModel();

    boolean isHostPresent();

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
