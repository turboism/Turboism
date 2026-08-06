package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.Optional;

/** One evaluated Cubism drawable/ArtMesh. */
@PreviewApi
public interface Drawable {

    ArtMeshId id();

    /** Returns this ArtMesh's Cubism palette UI projection. */
    default DrawableAppearance ui() { return DrawableAppearance.unavailable(); }

    default int index() { throw unavailable("ArtMesh index"); }

    default boolean doubleSided() { throw unavailable("ArtMesh double-sided state"); }

    default DrawableEvaluationState evaluationState() {
        throw unavailable("ArtMesh evaluation state");
    }

    default Optional<PartId> parentPartId() { throw unavailable("ArtMesh parent Part"); }

    default Optional<DeformerId> parentDeformerId() {
        throw unavailable("ArtMesh parent Deformer");
    }

    default List<ParameterId> parameterIds() { throw unavailable("ArtMesh parameters"); }

    default List<ArtMeshId> maskIds() { throw unavailable("ArtMesh masks"); }

    /** Stable ArtMesh GUID (distinct from {@link #id()}); unavailable until the host access implements it. */
    default String guid() { throw unavailable("ArtMesh guid"); }

    default String name() {
        throw unavailable("ArtMesh name");
    }

    default boolean visible() {
        throw unavailable("ArtMesh visibility");
    }

    default void setVisible(final boolean visible) {
        throw unavailable("ArtMesh visibility editing");
    }

    default boolean locked() {
        throw unavailable("ArtMesh lock state");
    }

    default void setLocked(final boolean locked) {
        throw unavailable("ArtMesh lock editing");
    }

    default boolean visibleInHierarchy() {
        throw unavailable("ArtMesh effective visibility");
    }

    default boolean lockedInHierarchy() {
        throw unavailable("ArtMesh effective lock state");
    }

    byte constantFlag();

    byte dynamicFlag();

    BlendMode blendMode();

    int textureIndex();

    int drawOrder();

    int renderOrder();

    float getOpacity();

    default void setOpacity(final float opacity) {
        throw unavailable("ArtMesh opacity editing");
    }

    default ArtMeshGeometry geometry() {
        throw unavailable("ArtMesh authoring geometry");
    }

    default void replaceGeometry(final ArtMeshGeometry geometry) {
        throw unavailable("ArtMesh geometry editing");
    }

    IntSequence masks();

    default boolean invertedMask() {
        throw unavailable("ArtMesh inverted-mask state");
    }

    default boolean culling() {
        throw unavailable("ArtMesh culling state");
    }

    default String userData() {
        throw unavailable("ArtMesh user data");
    }

    FloatSequence vertexPositions();

    FloatSequence vertexUvs();

    IntSequence indices();

    Color multiplyColor();

    Color screenColor();

    int parentPartIndex();

    int parentDeformerIndex();

    IntSequence parameters();

    /** Returns this ArtMesh's generation-bound Editor authoring bindings. */
    default List<ParameterBinding> getParameterBindings() {
        throw unavailable("ArtMesh parameter binding projection");
    }

    /**
     * Returns this ArtMesh's keyform Morph Targets in stable host order.
     *
     * @throws UnsupportedOperationException when the backend does not expose them
     */
    default MorphTargets morphTargets() {
        throw unavailable("ArtMesh Morph Targets");
    }


    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
