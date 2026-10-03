package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.core.MocInfo;
import dev.turboism.sdk.cubism.id.ModelId;
import java.util.List;
import java.util.Optional;

/**
 * One Cubism model exposed as natural objects and methods.
 *
 * <p>The object graph is the single recommended parameter read/write plane:
 * {@link #parameters()} yields live {@link Parameter} objects whose {@link Parameter#getValue()} /
 * {@link Parameter#setValue(float)} read and write the Editor authoring value. Immutable
 * point-in-time views exist elsewhere — {@link dev.turboism.sdk.cubism.ParameterSnapshot} inside
 * facade snapshots, {@link dev.turboism.sdk.cubism.service.query.ParameterSummary} from the
 * parameter query service, and {@link dev.turboism.sdk.cubism.core.OwnedParameter} inside the
 * detached owned model — but none of them is a write handle.
 */
public interface CubismModel {

    /** Returns this model's stable identity when the model-read contract is admitted. */
    @CubismEditor(from = "5.2.03")
    ModelId id();

    /**
     * Returns the model's display name.
     *
     * @throws UnsupportedOperationException when the backend does not expose it
     */
    default String name() {
        throw new UnsupportedOperationException("Cubism model name is unavailable.");
    }

    /**
     * Renames the model through the Editor authoring path.
     *
     * @throws UnsupportedOperationException when the backend does not support name editing
     */
    default void setName(final String name) {
        throw new UnsupportedOperationException("Cubism model-name editing is unavailable.");
    }

    /**
     * Returns the model's MOC metadata.
     *
     * @throws UnsupportedOperationException when the backend does not expose MOC inspection
     */
    default MocInfo mocInfo() {
        throw new UnsupportedOperationException("Cubism MOC metadata is unavailable.");
    }

    /**
     * Returns the model's parameter-definition document projection.
     * @throws UnsupportedOperationException when the backend does not expose parameter definitions
     */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default ParameterDefinitions parameterDefinitions() {
        throw new UnsupportedOperationException("Cubism parameter-definition access is unavailable.");
    }

    /**
     * Returns the Editor model-instance list (host {@code CModelSource.getModelInstances()}).
     *
     * <p>Read-only: instance creation and switching are Editor-internal operations with
     * no verified authoring/undo evidence, so no write projection is declared.</p>
     * @throws UnsupportedOperationException when the backend does not expose model instances
     */
    default List<ModelInstance> modelInstances() {
        throw new UnsupportedOperationException("Cubism model-instance access is unavailable.");
    }

    /**
     * Returns the Editor's current model instance, when one is selected.
     * @throws UnsupportedOperationException when the backend does not expose model instances
     */
    default Optional<ModelInstance> currentModelInstance() {
        throw new UnsupportedOperationException("Cubism current-model-instance access is unavailable.");
    }

    /**
     * Returns whether the Editor is currently editing the model source.
     * @throws UnsupportedOperationException when the backend does not expose the editing state
     */
    default boolean modelEditing() {
        throw new UnsupportedOperationException("Cubism model-editing state is unavailable.");
    }

    /**
     * Returns the model's read-only physics settings document projection.
     * @throws UnsupportedOperationException when the backend does not expose the physics settings document
     */
    default PhysicsSettings physicsSettings() {
        throw new UnsupportedOperationException("Cubism physics-settings document access is unavailable.");
    }

    /**
     * Returns the model's evaluated auto-Yure state.
     * @throws UnsupportedOperationException when the backend does not expose auto-Yure evaluation
     */
    default AutoYure autoYure() {
        throw new UnsupportedOperationException("Cubism auto-Yure evaluation access is unavailable.");
    }

    /**
     * Returns the model's animation file-content documents.
     *
     * <p>No stable Editor document entry exists for auto-face evaluation state,
     * so no auto-face projection is declared; see the adapter evidence records.
     * Animation scene add/delete ({@code CAnimationFileContent.addScene /
     * deleteScene / setCurrentSceneDoc}) has no reviewed Undo registration in Cubism
     * 5.2.03, 5.3.02, or 5.3.03, so scene writes stay unavailable (fail closed).</p>
     * @throws UnsupportedOperationException when the backend does not expose animation documents
     */
    default List<AnimationDocument> animationDocuments() {
        throw new UnsupportedOperationException("Cubism animation-document access is unavailable.");
    }

    /**
     * Returns the model's texture library projection.
     *
     * <p>Reads expose the Editor's {@code CTextureManager} document state (raw
     * images, model image groups, texture atlases); writes are Editor-authoring
     * operations inside the native Undo envelope.</p>
     * @throws UnsupportedOperationException when the backend does not expose the texture library
     */
    default ModelTextures textures() {
        throw new UnsupportedOperationException("Cubism texture-library access is unavailable.");
    }

    /** Returns the model's structural and render-resource statistics. */
    default ModelStatistics statistics() {
        return ModelStatisticsCalculator.calculate(this);
    }

    /**
     * Immutable PSD resource snapshots associated with this Editor model.
     * @throws UnsupportedOperationException when the backend does not expose PSD snapshots
     */
    default List<PsdClipMaskDocumentSnapshot> psdDocuments() {
        throw new UnsupportedOperationException("Cubism PSD snapshot access is unavailable.");
    }

    /**
     * Returns whether the Editor's default keyform is locked.
     * @throws UnsupportedOperationException when the backend does not expose the default-keyform lock state
     */
    default boolean defaultKeyformLocked() {
        throw new UnsupportedOperationException("Cubism default-keyform lock state is unavailable.");
    }

    /**
     * Changes whether the Editor's default keyform is locked.
     * @throws UnsupportedOperationException when the backend does not support default-keyform lock editing
     */
    default void setDefaultKeyformLocked(final boolean locked) {
        throw new UnsupportedOperationException("Cubism default-keyform lock editing is unavailable.");
    }

    /**
     * Returns the active Cubism Editor model editing level.
     * @throws UnsupportedOperationException when the backend does not expose the edit level
     */
    default ModelEditLevel editLevel() {
        throw new UnsupportedOperationException("Cubism model edit-level state is unavailable.");
    }

    /**
     * Switches the active Cubism Editor model editing level.
     * @throws UnsupportedOperationException when the backend does not support edit-level switching
     */
    default void setEditLevel(final ModelEditLevel level) {
        throw new UnsupportedOperationException("Cubism model edit-level switching is unavailable.");
    }

    /**
     * Returns the model's immutable canvas metrics.
     * @throws UnsupportedOperationException when the backend does not expose canvas metrics
     */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
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

    /**
     * Returns the model's parameter collection.
     *
     * <p>This is the recommended read/write entry: {@code parameters().find(id).setValue(v)}
     * performs a single validated, undoable Editor write.
     */
    Parameters parameters();

    /**
     * Returns the model's parameter-group collection.
     *
     * @throws UnsupportedOperationException when the backend does not expose parameter groups
     */
    default ParameterGroups parameterGroups() {
        throw new UnsupportedOperationException("Cubism parameter-group access is unavailable.");
    }

    /**
     * Returns the binding-edit operations for one parameter.
     *
     * @param parameterId the parameter whose bindings are edited
     * @throws UnsupportedOperationException when the backend does not support binding edits
     */
    default ParameterBindingOperations parameterBindings(final dev.turboism.sdk.cubism.id.ParameterId parameterId) {
        throw new UnsupportedOperationException("Cubism parameter-binding editing is unavailable.");
    }

    /**
     * Returns the batch binding-edit operations spanning multiple parameters.
     *
     * @throws UnsupportedOperationException when the backend does not support batch edits
     */
    default ParameterBindingBatchOperations parameterBindingBatch() {
        throw new UnsupportedOperationException("Cubism parameter-binding batch editing is unavailable.");
    }

    /** Returns the model's part collection. */
    Parts parts();

    /** Returns the model's drawable (ArtMesh) collection. */
    Drawables drawables();

    /**
     * Applies one conditional clip-mask replacement batch as one Editor edit.
     * @throws UnsupportedOperationException when the backend does not support clip-mask authoring replacement
     */
    default void replaceArtMeshClipMasks(final java.util.List<ClipMaskReplacement> replacements) {
        throw new UnsupportedOperationException("Cubism clip-mask authoring replacement is unavailable.");
    }

    /** Returns the model's unified deformer collection. */
    Deformers deformers();

    /**
     * Returns the model's Warp Deformer collection.
     *
     * @throws UnsupportedOperationException when the backend does not expose Warp Deformers
     */
    default WarpDeformers warpDeformers() {
        throw new UnsupportedOperationException("Cubism Warp Deformer access is unavailable.");
    }

    /**
     * Returns the model's Rotation Deformer collection.
     *
     * @throws UnsupportedOperationException when the backend does not expose Rotation Deformers
     */
    default RotationDeformers rotationDeformers() {
        throw new UnsupportedOperationException("Cubism Rotation Deformer access is unavailable.");
    }

    /** Returns the model's glue collection. */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    Glues glues();

    /** Runs one host model-instance update pass, re-evaluating the model's current state. */
    void update();
}
