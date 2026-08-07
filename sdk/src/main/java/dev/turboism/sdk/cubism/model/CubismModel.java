package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.core.MocInfo;

import java.util.List;

/** One Cubism model exposed as natural objects and methods. */
@PreviewApi
public interface CubismModel {

    ModelId id();

    default String name() {
        throw new UnsupportedOperationException("Cubism model name is unavailable.");
    }

    default void setName(final String name) {
        throw new UnsupportedOperationException("Cubism model-name editing is unavailable.");
    }

    default MocInfo mocInfo() {
        throw new UnsupportedOperationException("Cubism MOC metadata is unavailable.");
    }

    default ParameterDefinitions parameterDefinitions() {
        throw new UnsupportedOperationException(
            "Cubism parameter-definition access is unavailable."
        );
    }

    /** Returns the model's read-only physics settings document projection. */
    default PhysicsSettings physicsSettings() {
        throw new UnsupportedOperationException(
            "Cubism physics-settings document access is unavailable."
        );
    }

    /** Returns the model's evaluated auto-Yure state. */
    default AutoYure autoYure() {
        throw new UnsupportedOperationException(
            "Cubism auto-Yure evaluation access is unavailable."
        );
    }

    /**
     * Returns the model's animation file-content documents.
     *
     * <p>No stable Editor document entry exists for auto-face evaluation state,
     * so no auto-face projection is declared; see the adapter evidence records.
     * Animation scene add/delete ({@code CAnimationFileContent.addScene /
     * deleteScene / setCurrentSceneDoc}) has no Undo registration in Cubism
     * 5.2.03 or 5.3.02, so scene writes stay unavailable (fail closed).</p>
     */
    default List<AnimationDocument> animationDocuments() {
        throw new UnsupportedOperationException(
            "Cubism animation-document access is unavailable."
        );
    }

    /**
     * Returns the model's texture library projection.
     *
     * <p>Reads expose the Editor's {@code CTextureManager} document state (raw
     * images, model image groups, texture atlases); writes are Editor-authoring
     * operations inside the native Undo envelope.</p>
     */
    default ModelTextures textures() {
        throw new UnsupportedOperationException(
            "Cubism texture-library access is unavailable."
        );
    }

    /** Returns the model's structural and render-resource statistics. */

    /** Returns the model's structural and render-resource statistics. */
    default ModelStatistics statistics() {
        return ModelStatisticsCalculator.calculate(this);
    }

    /** Returns whether the Editor's default keyform is locked. */
    default boolean defaultKeyformLocked() {
        throw new UnsupportedOperationException(
            "Cubism default-keyform lock state is unavailable."
        );
    }

    /** Changes whether the Editor's default keyform is locked. */
    default void setDefaultKeyformLocked(final boolean locked) {
        throw new UnsupportedOperationException(
            "Cubism default-keyform lock editing is unavailable."
        );
    }

    /** Returns the active Cubism Editor model editing level. */
    default ModelEditLevel editLevel() {
        throw new UnsupportedOperationException(
            "Cubism model edit-level state is unavailable."
        );
    }

    /** Switches the active Cubism Editor model editing level. */
    default void setEditLevel(final ModelEditLevel level) {
        throw new UnsupportedOperationException(
            "Cubism model edit-level switching is unavailable."
        );
    }

    default Canvas canvas() {
        throw new UnsupportedOperationException("Cubism canvas access is unavailable.");
    }

    /**
     * Returns the Editor model profile metrics (pixels-per-unit and origin).
     *
     * @throws UnsupportedOperationException when the backend does not expose them
     */
    default ModelProfile profile() {
        throw new UnsupportedOperationException("Cubism model profile is unavailable.");
    }

    Parameters parameters();

    default ParameterGroups parameterGroups() {
        throw new UnsupportedOperationException("Cubism parameter-group access is unavailable.");
    }

    default ParameterBindingOperations parameterBindings(final dev.turboism.sdk.cubism.id.ParameterId parameterId) {
        throw new UnsupportedOperationException("Cubism parameter-binding editing is unavailable.");
    }

    default ParameterBindingBatchOperations parameterBindingBatch() {
        throw new UnsupportedOperationException("Cubism parameter-binding batch editing is unavailable.");
    }

    Parts parts();

    Drawables drawables();

    Deformers deformers();

    default WarpDeformers warpDeformers() {
        throw new UnsupportedOperationException("Cubism Warp Deformer access is unavailable.");
    }

    default RotationDeformers rotationDeformers() {
        throw new UnsupportedOperationException("Cubism Rotation Deformer access is unavailable.");
    }

    Glues glues();

    void update();
}
