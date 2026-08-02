package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.Optional;

/** One Cubism deformer. */
@PreviewApi
public interface Deformer {

    DeformerId id();

    default int index() { throw unavailable("Deformer index"); }

    default Optional<PartId> parentPartId() { throw unavailable("Deformer parent Part"); }

    default Optional<DeformerId> parentDeformerId() {
        throw unavailable("Deformer parent Deformer");
    }

    default List<ParameterId> parameterIds() { throw unavailable("Deformer parameters"); }

    default String name() {
        throw unavailable("Deformer name");
    }

    default boolean visible() {
        throw unavailable("Deformer visibility");
    }

    default void setVisible(final boolean visible) {
        throw unavailable("Deformer visibility editing");
    }

    default boolean locked() {
        throw unavailable("Deformer lock state");
    }

    default void setLocked(final boolean locked) {
        throw unavailable("Deformer lock editing");
    }

    default boolean visibleInHierarchy() {
        throw unavailable("Deformer effective visibility");
    }

    default boolean lockedInHierarchy() {
        throw unavailable("Deformer effective lock state");
    }

    default float getOpacity() {
        throw unavailable("Deformer opacity");
    }

    default void setOpacity(final float opacity) {
        throw unavailable("Deformer opacity editing");
    }

    default Color multiplyColor() {
        throw unavailable("Deformer multiply color");
    }

    default Color screenColor() {
        throw unavailable("Deformer screen color");
    }

    default int parentPartIndex() {
        throw unavailable("Deformer parent Part");
    }

    int parentDeformerIndex();

    IntSequence parameters();

    /** Returns this Deformer's generation-bound Editor authoring bindings. */
    default List<ParameterBinding> getParameterBindings() {
        throw unavailable("Deformer parameter binding projection");
    }


    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
