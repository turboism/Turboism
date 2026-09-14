package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.ui.appearance.model.DeformerAppearance;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.Optional;

/** One Cubism deformer. */
public interface Deformer {

    /** Returns this Deformer's stable identity within the model. */
    DeformerId id();

    /** Returns this Deformer's Cubism palette UI projection. */
    default DeformerAppearance ui() { return DeformerAppearance.unavailable(); }

    /** Returns this Deformer's position within its owner's child list. */
    default int index() { throw unavailable("Deformer index"); }

    /** Returns the parent Part identity, or empty when this Deformer is not under a Part. */
    default Optional<PartId> parentPartId() { throw unavailable("Deformer parent Part"); }

    /** Returns the parent Deformer identity, or empty when this Deformer is at the model root. */
    default Optional<DeformerId> parentDeformerId() {
        throw unavailable("Deformer parent Deformer");
    }

    /** Returns the identities of the parameters bound to this Deformer. */
    default List<ParameterId> parameterIds() { throw unavailable("Deformer parameters"); }

    /** Editor display name, or the ID text when no authoring name is available. */
    default String name() {
        throw unavailable("Deformer name");
    }

    /**
     * Renames this Deformer (Inspector {@code name} entry) inside the native Undo
     * envelope, then refreshes the Part and Deformer palettes.
     */
    default void setName(final String name) {
        throw unavailable("Deformer name editing");
    }

    /**
     * Renames this Deformer's Cubism ID (Inspector {@code id} entry) mirroring the
     * Inspector sequence (id rules, undo envelope, model instance refresh, verify,
     * palette refresh).
     */
    default void setId(final DeformerId id) {
        throw unavailable("Deformer ID editing");
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
     * <p>This is the canonical reparent entry; {@link #setTargetDeformer} is the
     * Inspector-shaped alias of {@code setParent(deformer, -1)}.</p>
     *
     * @throws IllegalArgumentException when {@code parent} is this Deformer or one of its descendants
     */
    default void setParent(Deformer parent, int index) {
        throw unavailable("Deformer reparenting");
    }

    /**
     * Reparents this Deformer to another Deformer (Inspector {@code targetDeformer}
     * entry, "所属变形器"). An empty value detaches it to the model root. The host's
     * undo-aware {@code changeTargetDeformer} is used, so nested Undo entries are
     * preserved; self-assignment, no-ops and descendant targets fail closed.
     *
     * <p>Alias of {@link #setParent(Deformer, int)} (empty = model root detach).</p>
     */
    default void setTargetDeformer(final Optional<DeformerId> target) {
        throw unavailable("Deformer target-deformer editing");
    }
    /** Returns this Deformer's own visibility flag. */
    default boolean visible() {
        throw unavailable("Deformer visibility");
    }

    /** Writes this Deformer's visibility flag through the Editor authoring path. */
    default void setVisible(final boolean visible) {
        throw unavailable("Deformer visibility editing");
    }

    /** Returns this Deformer's own lock flag. */
    default boolean locked() {
        throw unavailable("Deformer lock state");
    }

    /** Writes this Deformer's lock flag through the Editor authoring path. */
    default void setLocked(final boolean locked) {
        throw unavailable("Deformer lock editing");
    }

    /** Returns whether this Deformer is effectively visible, including ancestor state. */
    default boolean visibleInHierarchy() {
        throw unavailable("Deformer effective visibility");
    }

    /** Returns whether this Deformer is effectively locked, including ancestor state. */
    default boolean lockedInHierarchy() {
        throw unavailable("Deformer effective lock state");
    }

    /** Returns this Deformer's opacity in {@code [0,1]}. */
    default float getOpacity() {
        throw unavailable("Deformer opacity");
    }

    /** Writes this Deformer's opacity through the Editor authoring path. */
    default void setOpacity(final float opacity) {
        throw unavailable("Deformer opacity editing");
    }

    /** Returns this Deformer's multiply (blend) color, channels in {@code [0,1]}. */
    default Color multiplyColor() {
        throw unavailable("Deformer multiply color");
    }

    /** Returns this Deformer's screen (additive) color, channels in {@code [0,1]}. */
    default Color screenColor() {
        throw unavailable("Deformer screen color");
    }

    /**
     * Writes the Deformer multiply color (Inspector {@code multiplyColor} entry)
     * inside the native Undo envelope. Requires a Cubism 4.2+ target version
     * (CUB3-3264 gate); channels must be within {@code [0,1]}.
     */
    default void setMultiplyColor(final Color color) {
        throw unavailable("Deformer multiply-color editing");
    }

    /**
     * Writes the Deformer screen color (Inspector {@code screenColor} entry) inside
     * the native Undo envelope. Requires a Cubism 4.2+ target version (CUB3-3265
     * gate); channels must be within {@code [0,1]}.
     */
    default void setScreenColor(final Color color) {
        throw unavailable("Deformer screen-color editing");
    }

    /** Returns the Core index of the parent Part, or a negative value when there is none. */
    default int parentPartIndex() {
        throw unavailable("Deformer parent Part");
    }

    /** Returns the Core index of the parent Deformer, or a negative value when there is none. */
    int parentDeformerIndex();

    /** Returns the Core parameter indexes bound to this Deformer. */
    IntSequence parameters();

    /**
     * Returns this Deformer's generation-bound Editor authoring bindings: keyform
     * grid bindings ({@link ParameterBindingFamily#KEYFORM_GRID}) followed by
     * morph-target bindings ({@link ParameterBindingFamily#BLEND_SHAPE}), in
     * stable host order (Deformers carry no morph container in the Editor, so the
     * morph portion is normally empty). Use {@link #getNormalParameterBindings()},
     * {@link #getMorphParameterBindings()} and {@link #getCombinedParameterBindings()}
     * to select one family.
     */
    default List<ParameterBinding> getParameterBindings() {
        throw unavailable("Deformer parameter binding projection");
    }

    /** Returns this Deformer's morph-target (BLEND_SHAPE) bindings only. */
    default List<ParameterBinding> getMorphParameterBindings() {
        return getParameterBindings().stream()
            .filter(binding -> binding.family() == ParameterBindingFamily.BLEND_SHAPE)
            .toList();
    }

    /**
     * Returns this Deformer's normal bindings: keyform-grid bindings whose
     * parameter is neither a morph target nor Combined in the Editor. Requires
     * parameter-combined knowledge, so implementations that only project
     * bindings throw {@link UnsupportedOperationException}.
     */
    default List<ParameterBinding> getNormalParameterBindings() {
        throw unavailable("Normal Deformer parameter binding projection");
    }

    /**
     * Returns this Deformer's keyform bindings whose parameter is Combined in the
     * Editor. Requires parameter-combined knowledge, so implementations that
     * only project bindings throw {@link UnsupportedOperationException}.
     */
    default List<ParameterBinding> getCombinedParameterBindings() {
        throw unavailable("Combined Deformer parameter binding projection");
    }


    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
