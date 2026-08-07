package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.ui.appearance.model.DeformerAppearance;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.Optional;

/** One Cubism deformer. */
@PreviewApi
public interface Deformer {

    DeformerId id();

    /** Returns this Deformer's Cubism palette UI projection. */
    default DeformerAppearance ui() { return DeformerAppearance.unavailable(); }

    default int index() { throw unavailable("Deformer index"); }

    default Optional<PartId> parentPartId() { throw unavailable("Deformer parent Part"); }

    default Optional<DeformerId> parentDeformerId() {
        throw unavailable("Deformer parent Deformer");
    }

    default List<ParameterId> parameterIds() { throw unavailable("Deformer parameters"); }

    /** Editor display name, or the ID text when no authoring name is available. */
    default String name() {
        throw unavailable("Deformer name");
    }

    /** Renames this Deformer through the verified Editor authoring seam. */
    default void setName(String name) {
        throw unavailable("Deformer renaming");
    }

    /**
     * Moves this Deformer under a Part parent at {@code index} (negative = append).
     *
     * <p>The native Cubism Part tree owns the detach/attach semantics and Undo/Redo.</p>
     *
     * @throws IllegalArgumentException when {@code parent} is this Deformer or one of its descendants
     */
    default void setParent(Part parent, int index) {
        throw unavailable("Deformer reparenting");
    }

    /**
     * Moves this Deformer under another Deformer through the native target-deformer relation.
     *
     * @throws IllegalArgumentException when {@code parent} is this Deformer or one of its descendants
     */
    default void setParent(Deformer parent, int index) {
        throw unavailable("Deformer reparenting");
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
