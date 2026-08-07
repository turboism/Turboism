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

    /** Renames this Drawable through the verified Editor authoring seam. */
    default void setName(String name) {
        throw unavailable("Drawable renaming");
    }

    /**
     * Moves this Drawable under a Part parent at {@code index} (negative = append).
     *
     * <p>The native Cubism Part tree owns the detach/attach semantics and Undo/Redo.</p>
     *
     * @throws IllegalArgumentException when {@code parent} is this Drawable or one of its descendants
     */
    default void setParent(Part parent, int index) {
        throw unavailable("Drawable reparenting");
    }

    /**
     * Moves this Drawable under a Deformer through the native target-deformer relation.
     *
     * <p>This is the canonical reparent entry; {@link #setTargetDeformer} is the
     * Inspector-shaped alias of {@code setParent(deformer, -1)}.</p>
     *
     * @throws IllegalArgumentException when {@code parent} is this Drawable or one of its descendants
     */
    default void setParent(Deformer parent, int index) {
        throw unavailable("Drawable reparenting");
    }

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


    /**
     * Renames this ArtMesh's Editor ID (Inspector {@code setId} envelope).
     *
     * @throws IllegalArgumentException when the ID is blank, malformed, or already used in the model
     */
    default void setId(final String id) {
        throw unavailable("ArtMesh ID editing");
    }

    /**
     * Moves this ArtMesh to another Deformer in the Editor hierarchy (Inspector
     * {@code targetDeformer} selection). Empty moves it to the model root.
     *
     * <p>Alias of {@link #setParent(Deformer, int)} (empty = model root detach).</p>
     *
     * @throws java.util.NoSuchElementException when the Deformer ID is absent
     */
    default void setTargetDeformer(final Optional<DeformerId> targetDeformer) {
        throw unavailable("ArtMesh target Deformer editing");
    }

    /**
     * Replaces this ArtMesh's clipping-mask list (Inspector {@code clippingMaskId} list).
     *
     * @throws IllegalArgumentException when any mask ID does not resolve to a Drawable in the model
     */
    default void setClippingMaskIds(final List<ArtMeshId> maskIds) {
        throw unavailable("ArtMesh clipping mask editing");
    }

    /** Toggles this ArtMesh's inverted clipping mask (Inspector {@code invertClippingMask}). */
    default void setInvertedMask(final boolean inverted) {
        throw unavailable("ArtMesh inverted-mask editing");
    }

    /** Sets this ArtMesh's keyform draw order, clamped to {@code [0, 1000]} (Inspector {@code drawOrder}). */
    default void setDrawOrder(final int drawOrder) {
        throw unavailable("ArtMesh draw order editing");
    }

    /** Sets this ArtMesh's multiply (base) color (Inspector {@code multiplyColor}). */
    default void setMultiplyColor(final Color color) {
        throw unavailable("ArtMesh multiply color editing");
    }

    /** Sets this ArtMesh's screen (effect) color (Inspector {@code screenColor}). */
    default void setScreenColor(final Color color) {
        throw unavailable("ArtMesh screen color editing");
    }

    /** Sets this ArtMesh's color composition (Inspector {@code colorComposition}). */
    default void setColorComposition(final ColorComposition composition) {
        throw unavailable("ArtMesh color composition editing");
    }

    /** Sets this ArtMesh's alpha composition (Inspector {@code alphaComposition}); unavailable on Cubism 5.2 hosts. */
    default void setAlphaComposition(final AlphaComposition composition) {
        throw unavailable("ArtMesh alpha composition editing");
    }

    /** Sets this ArtMesh's culling state (Inspector {@code culling}). */
    default void setCulling(final boolean culling) {
        throw unavailable("ArtMesh culling editing");
    }

    /** Sets this ArtMesh's user data (Inspector {@code userData}). */
    default void setUserData(final String userData) {
        throw unavailable("ArtMesh user data editing");
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
