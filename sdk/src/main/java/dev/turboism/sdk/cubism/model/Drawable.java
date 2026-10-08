package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import java.util.List;
import java.util.Optional;

/** One evaluated Cubism drawable/ArtMesh. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface Drawable {

    /** Returns this ArtMesh's stable identity within the model. */
    ArtMeshId id();

    /** Returns this ArtMesh's Cubism palette UI projection. */
    default DrawableAppearance ui() {
        return DrawableAppearance.unavailable();
    }

    /** Returns this ArtMesh's position within its owner's child list. */
    default int index() {
        throw unavailable("ArtMesh index");
    }

    /** Returns whether this ArtMesh renders on both faces (no back-face culling). */
    default boolean doubleSided() {
        throw unavailable("ArtMesh double-sided state");
    }

    /** Returns this ArtMesh's current evaluation state. */
    default DrawableEvaluationState evaluationState() {
        throw unavailable("ArtMesh evaluation state");
    }

    /** Returns the parent Part identity, or empty when this ArtMesh is not under a Part. */
    default Optional<PartId> parentPartId() {
        throw unavailable("ArtMesh parent Part");
    }

    /** Returns the parent Deformer identity, or empty when this ArtMesh is at the model root. */
    default Optional<DeformerId> parentDeformerId() {
        throw unavailable("ArtMesh parent Deformer");
    }

    /** Returns the identities of the parameters bound to this ArtMesh. */
    default List<ParameterId> parameterIds() {
        throw unavailable("ArtMesh parameters");
    }

    /** Returns the identities of the ArtMeshes in this ArtMesh's clipping-mask list. */
    default List<ArtMeshId> maskIds() {
        throw unavailable("ArtMesh masks");
    }

    /** Stable ArtMesh GUID (distinct from {@link #id()}); unavailable until the host access implements it. */
    default String guid() {
        throw unavailable("ArtMesh guid");
    }

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

    /** Returns this ArtMesh's Editor display name. */
    default String name() {
        throw unavailable("ArtMesh name");
    }

    /** Returns this ArtMesh's own visibility flag. */
    default boolean visible() {
        throw unavailable("ArtMesh visibility");
    }

    /** Writes this ArtMesh's visibility flag through the Editor authoring path. */
    default void setVisible(final boolean visible) {
        throw unavailable("ArtMesh visibility editing");
    }

    /** Returns this ArtMesh's own lock flag. */
    default boolean locked() {
        throw unavailable("ArtMesh lock state");
    }

    /** Writes this ArtMesh's lock flag through the Editor authoring path. */
    default void setLocked(final boolean locked) {
        throw unavailable("ArtMesh lock editing");
    }

    /** Returns whether this ArtMesh is effectively visible, including ancestor state. */
    default boolean visibleInHierarchy() {
        throw unavailable("ArtMesh effective visibility");
    }

    /** Returns whether this ArtMesh is effectively locked, including ancestor state. */
    default boolean lockedInHierarchy() {
        throw unavailable("ArtMesh effective lock state");
    }

    /** Returns the Core constant flag bitmask for this ArtMesh. */
    byte constantFlag();

    /** Returns the Core dynamic flag bitmask for this ArtMesh. */
    byte dynamicFlag();

    /** Returns this ArtMesh's blend mode. */
    BlendMode blendMode();

    /** Returns the index of the texture this ArtMesh samples. */
    int textureIndex();

    /** Returns this ArtMesh's keyform draw order. */
    int drawOrder();

    /** Returns this ArtMesh's evaluated render order. */
    int renderOrder();

    /** Returns this ArtMesh's opacity in {@code [0,1]}. */
    float getOpacity();

    /** Writes this ArtMesh's opacity through the Editor authoring path. */
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

    /** Sets this ArtMesh's alpha composition (Inspector {@code alphaComposition}). */
    @CubismEditor({"5.3.02", "5.3.03"})
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

    /**
     * Reads detached source geometry, stored forms, grid rows and morph identities together.
     * Unlike {@link #geometry()}, this does not substitute the currently evaluated keyform.
     * The result is historical data, not a writable target or proof of morph-key semantics.
     *
     * @throws UnsupportedOperationException when exact-version stored authoring reads are unavailable
     */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default ArtMeshAuthoringSnapshot authoringSnapshot() {
        throw unavailable("ArtMesh stored authoring snapshot");
    }

    /**
     * Observes the Editor's last calculated form and parameter-consumption metadata without
     * triggering evaluation. The detached result does not assert freshness or success.
     * Requires the verified 5.3.02 default Offscreen/EDT rendering configuration.
     *
     * @throws UnsupportedOperationException when this read or rendering configuration is unsupported
     */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default ArtMeshEvaluationSnapshot evaluationSnapshot() {
        throw unavailable("ArtMesh calculated-form observation");
    }

    /**
     * Locates one existing ordinary keyform by its complete parameter-GUID tuple.
     * Coordinate order is irrelevant; omitted, duplicate and undeclared keys are rejected.
     * The exact default tuple must exist, and the selected form must not be shared with
     * another ordinary cell or morph target. No keys are created and no sliders are moved.
     * A default reference is readable, but its positions cannot be edited through this method.
     *
     * @param coordinates every bound ordinary parameter's GUID and exact key value
     * @return a generation-bound stored-form reference, not an evaluated shape
     * @throws IllegalArgumentException for invalid or ambiguous coordinates/layout
     * @throws java.util.NoSuchElementException when the existing tuple is missing
     * @throws UnsupportedOperationException when exact-version keyform access is unavailable
     */
    @CubismEditor({"5.3.02", "5.3.03"})
    default ArtMeshKeyform ordinaryKeyform(final List<ArtMeshAuthoringSnapshot.Coordinate> coordinates) {
        throw unavailable("ArtMesh ordinary keyform");
    }

    /**
     * Writes a bounded batch of existing non-default ordinary forms in one authoring contribution.
     * The complete current authoring state must equal {@code expected}; every edit must identify
     * an independent form by both its complete parameter-GUID tuple and existing form GUID.
     * Source geometry, topology, UVs, draw orders and unselected forms remain unchanged.
     * All targets are validated before mutation. Equal positions are a no-op. Native Undo/Redo
     * and transaction rollback cover the batch together. No keys or objects are created.
     *
     * @param expected detached state read from this mesh before planning the edits
     * @param edits at most 4096 distinct targets and 2000000 total vertex positions
     * @throws IllegalStateException when the expected state or owning generation is stale
     * @throws IllegalArgumentException for invalid, duplicate, shared or mismatched targets
     * @throws UnsupportedOperationException for default forms or unavailable verified authoring
     */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default void setOrdinaryKeyformPositions(final ArtMeshAuthoringSnapshot expected,
            final List<ArtMeshKeyformPositions> edits) {
        throw unavailable("ArtMesh ordinary keyform position batch");
    }

    /**
     * Writes only existing non-default ordinary form draw orders in one bounded
     * compare-and-set contribution. Positions, geometry and other forms stay unchanged.
     */
    default void setOrdinaryKeyformDrawOrders(final ArtMeshAuthoringSnapshot expected,
            final List<ArtMeshKeyformDrawOrder> edits) {
        throw unavailable("ArtMesh ordinary keyform draw order batch");
    }

    /**
     * Locates an existing, non-basic morph key by native parameter GUID and exact key value.
     * Requires the supported root/ordinary-grid domain described by {@link ArtMeshMorphKeyform}.
     * Does not create keys, move sliders, or interpret zero/null as a writable basic key.
     * @param parameterGuid native parameter GUID in this model, not its display ID
     * @param keyValue positive non-basic key in (0, 1], outside the native snap epsilon
     * @return a generation- and membership-bound reference
     * @throws java.util.NoSuchElementException when the parameter or key does not exist
     * @throws IllegalArgumentException for an invalid key or identity
     * @throws UnsupportedOperationException when this exact-version domain is unsupported
     */
    @CubismEditor({"5.3.02", "5.3.03"})
    default ArtMeshMorphKeyform morphKeyform(final String parameterGuid, final float keyValue) {
        throw unavailable("ArtMesh morph keyform");
    }

    /**
     * Creates one absent non-basic morph key using native absolute-form interpolation for its seed.
     * Requires the member-snapshot authoring transaction protocol; never silently reuses a key.
     * Parameter creation is a separate operation. Reacquire earlier references after creating siblings.
     * @param parameterGuid native GUID of an existing normalized, non-repeating morph parameter
     * @param keyValue positive key in (0, 1], not conflicting within native snap epsilon
     * @return a reference to the new member, not an evaluated shape
     * @throws java.util.NoSuchElementException when the parameter does not exist
     * @throws IllegalArgumentException for an invalid or conflicting key
     * @throws UnsupportedOperationException when the member transaction protocol is unavailable
     */
    @CubismEditor({"5.3.02", "5.3.03"})
    default ArtMeshMorphKeyform createMorphKeyform(final String parameterGuid, final float keyValue) {
        throw unavailable("ArtMesh morph key creation");
    }
    /**
     * Returns this ArtMesh's authoring geometry (Inspector {@code PointInfo} projection).
     *
     * <p>{@code positions()} is read from the ArtMesh's <em>current keyform</em>
     * ({@code CArtMeshForm.positions}), exactly the array the Inspector PointInfo widget
     * moves vertices on; {@code uvs()} and {@code triangleIndices()} are read from the
     * ArtMesh source ({@code CArtMeshSource.uvs/indices}), which is static per mesh.
     * Vertex count is {@code geometry().positions().size()}.</p>
     *
     * @throws UnsupportedOperationException when the backend does not expose authoring geometry
     */
    default ArtMeshGeometry geometry() {
        throw unavailable("ArtMesh authoring geometry");
    }

    /**
     * Commits a complete ArtMesh geometry snapshot as one Editor edit (Inspector {@code PointInfo}
     * projection).
     *
     * <p>The Inspector's PointInfo widget edits <em>selected vertices</em> of the current keyform:
     * a single-vertex move is {@code replaceGeometry(geometry().withVertexPosition(i, x, y))}
     * (absolute) or {@code withVertexPosition(i, x + dx, y + dy)} (relative); multi-selection is
     * one {@code replaceGeometry} with several vertices moved. This method is the SDK projection of
     * that primitive: it writes positions to both the ArtMesh source and the current keyform form
     * (keeping the base geometry consistent) and uvs/indices to the source, inside the verified
     * Editor undo envelope.</p>
     *
     * <p>Undo semantics: the write is committed through {@code createUndoForAllEdit} — the generic
     * Editor edit undo — because the snapshot covers source-level arrays (uvs/indices/positions)
     * in addition to the keyform positions. The Inspector widget itself routes {@code pointInfo}
     * through {@code createUndoForKeyformEdit}; both are host {@code ACUndoable} entries added to
     * the same edit group, and both replay {@code updateModelInstances()} on undo/redo. The host's
     * default-keyform edit lock (a UI guard checked by the widget) is not mirrored: the SDK write
     * is unconditional. No-op when the geometry is unchanged.</p>
     *
     * @throws UnsupportedOperationException when the backend does not expose authoring geometry
     */
    default void replaceGeometry(final ArtMeshGeometry geometry) {
        throw unavailable("ArtMesh geometry editing");
    }
    /** Returns the Core drawable indexes in this ArtMesh's clipping-mask list. */
    IntSequence masks();

    /** Returns whether this ArtMesh's clipping mask is inverted. */
    default boolean invertedMask() {
        throw unavailable("ArtMesh inverted-mask state");
    }

    /** Returns whether this ArtMesh is culled. */
    default boolean culling() {
        throw unavailable("ArtMesh culling state");
    }

    /** Returns this ArtMesh's free-form user data string. */
    default String userData() {
        throw unavailable("ArtMesh user data");
    }

    /** Returns the evaluated vertex positions as flat x/y pairs. */
    FloatSequence vertexPositions();

    /** Returns the vertex UV coordinates as flat u/v pairs. */
    FloatSequence vertexUvs();

    /** Returns the triangle index list into the vertex arrays. */
    IntSequence indices();

    /** Returns this ArtMesh's multiply (base) color, channels in {@code [0,1]}. */
    Color multiplyColor();

    /** Returns this ArtMesh's screen (effect) color, channels in {@code [0,1]}. */
    Color screenColor();

    /** Returns the Core index of the parent Part, or a negative value when there is none. */
    int parentPartIndex();

    /** Returns the Core index of the parent Deformer, or a negative value when there is none. */
    int parentDeformerIndex();

    /** Returns the Core parameter indexes bound to this ArtMesh. */
    IntSequence parameters();

    /**
     * Returns this ArtMesh's generation-bound Editor authoring bindings: keyform
     * grid bindings ({@link ParameterBindingFamily#KEYFORM_GRID}) followed by
     * morph-target bindings ({@link ParameterBindingFamily#BLEND_SHAPE}), in
     * stable host order. Use {@link #getNormalParameterBindings()}, {@link
     * #getMorphParameterBindings()} and {@link #getCombinedParameterBindings()}
     * to select one family.
     */
    default List<ParameterBinding> getParameterBindings() {
        throw unavailable("ArtMesh parameter binding projection");
    }

    /** Returns this ArtMesh's morph-target (BLEND_SHAPE) bindings only. */
    default List<ParameterBinding> getMorphParameterBindings() {
        return getParameterBindings().stream()
                .filter(binding -> binding.family() == ParameterBindingFamily.BLEND_SHAPE)
                .toList();
    }

    /**
     * Returns this ArtMesh's normal bindings: keyform-grid bindings whose
     * parameter is neither a morph target nor Combined in the Editor. Requires
     * parameter-combined knowledge, so implementations that only project
     * bindings throw {@link UnsupportedOperationException}.
     */
    default List<ParameterBinding> getNormalParameterBindings() {
        throw unavailable("Normal ArtMesh parameter binding projection");
    }

    /**
     * Returns this ArtMesh's keyform bindings whose parameter is Combined in the
     * Editor. Requires parameter-combined knowledge, so implementations that
     * only project bindings throw {@link UnsupportedOperationException}.
     */
    default List<ParameterBinding> getCombinedParameterBindings() {
        throw unavailable("Combined ArtMesh parameter binding projection");
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
