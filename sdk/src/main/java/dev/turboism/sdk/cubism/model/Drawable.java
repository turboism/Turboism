package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ArtMeshId;

import java.util.List;

/** One evaluated Cubism drawable/ArtMesh. */
@PreviewApi
public interface Drawable {

    ArtMeshId id();

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


    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
