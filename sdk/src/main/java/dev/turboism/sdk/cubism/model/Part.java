package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ArtMeshId;

import java.util.List;
import java.util.Optional;

/** One Cubism Part. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface Part extends PartAppearanceAccess {

    /** Returns this Part's stable identity within the model. */
    PartId id();

    /** Returns this Part's position within its parent's child list. */
    default int index() { throw unavailable("Part index"); }

    /** Editor display name, or the ID text when no authoring name is available. */
    default String name() { return id().value(); }

    /** Renames this Part through the Editor authoring path. */
    void setName(String name);

    /**
     * Renames this Part's Cubism ID through the Editor Inspector {@code id} entry.
     * Mirrors the Inspector sequence (check id rules, undo envelope, model
     * instance refresh, verify, palette refresh).
     *
     * @throws IllegalArgumentException when the requested ID violates Cubism ID rules
     * @throws IllegalStateException    when the backend rejects the Undo entry
     */
    default void setId(final PartId id) {
        throw unavailable("Part ID editing");
    }

    /**
     * Moves this Part under {@code parent} in the Part tree at {@code index} (negative = append).
     *
     * <p>The native Cubism Part tree owns the detach/attach semantics (old-parent {@code removeChild},
     * {@code internal_setParent}) and Undo/Redo.</p>
     *
     * @throws IllegalArgumentException when {@code parent} is this Part or one of its descendants
     */
    default void setParent(Part parent, int index) {
        throw unavailable("Part reparenting");
    }

    /** Returns this Part's short display name, or empty when none is set. */
    default Optional<String> shortName() { throw unavailable("Part short name"); }

    /** Writes this Part's short display name through the Editor authoring path. */
    default void setShortName(final Optional<String> value) {
        throw unavailable("Part short-name editing");
    }

    /** Returns the parent Part identity, or empty when this Part is at the root. */
    default Optional<PartId> parentId() { throw unavailable("Part parent"); }

    /** Returns the identities of this Part's direct children, in order. */
    default List<PartId> childIds() { throw unavailable("Part children"); }

    /**
     * Returns the ArtMesh IDs this Part clips (Inspector {@code clippingMaskId}
     * entry), in stable host order.
     */
    @CubismEditor("5.3.02")
    default List<ArtMeshId> maskIds() { throw unavailable("Part clipping masks"); }

    /**
     * Replaces the Part's clipping mask list (Inspector {@code clippingMaskId}
     * entry) inside the native Undo envelope.
     *
     * @throws IllegalArgumentException when a referenced ArtMesh is absent from the model
     */
    @CubismEditor("5.3.02")
    default void setMaskIds(final List<ArtMeshId> ids) {
        throw unavailable("Part clipping-mask editing");
    }

    /** Returns this Part's own visibility flag. */
    default boolean visible() { throw unavailable("Part visibility"); }

    /** Writes this Part's visibility flag through the Editor authoring path. */
    default void setVisible(final boolean value) { throw unavailable("Part visibility editing"); }

    /** Returns whether this Part is effectively visible, including ancestor state. */
    default boolean visibleInHierarchy() { throw unavailable("Part effective visibility"); }

    /** Returns this Part's own lock flag. */
    default boolean locked() { throw unavailable("Part lock state"); }

    /** Writes this Part's lock flag through the Editor authoring path. */
    default void setLocked(final boolean value) { throw unavailable("Part lock editing"); }

    /** Returns whether this Part is effectively locked, including ancestor state. */
    default boolean lockedInHierarchy() { throw unavailable("Part effective lock state"); }

    /**
     * Returns this Part's keyform Morph Targets in stable host order.
     *
     * @throws UnsupportedOperationException when the backend does not expose them
     */
    default MorphTargets morphTargets() {
        throw unavailable("Part Morph Targets");
    }

    /** Returns this Part's palette edit color, or empty when none is assigned. */
    default Optional<Color> editColor() { throw unavailable("Part edit color"); }

    /** Writes this Part's palette edit color through the Editor authoring path. */
    default void setEditColor(final Optional<Color> value) {
        throw unavailable("Part edit-color editing");
    }

    /** Returns whether this Part is in sketch (draft) display state. */
    default boolean sketch() { throw unavailable("Part sketch state"); }

    /** Writes this Part's sketch (draft) display state through the Editor authoring path. */
    default void setSketch(final boolean value) { throw unavailable("Part sketch editing"); }

    /**
     * Returns this Part's alpha-composition mode (Inspector {@code alphaComposition}
     * entry), or {@link AlphaComposition#UNKNOWN} when the backend does not expose it.
     */
    @CubismEditor("5.3.02")
    default AlphaComposition alphaComposition() { return AlphaComposition.UNKNOWN; }

    /**
     * Writes this Part's alpha-composition mode (Inspector {@code alphaComposition}
     * entry) inside the native Undo envelope.
     */
    @CubismEditor("5.3.02")
    default void setAlphaComposition(final AlphaComposition composition) {
        throw unavailable("Part alpha-composition editing");
    }

    /** Returns this Part's default draw order. */
    default int defaultOrder() { throw unavailable("Part default order"); }

    /** Writes this Part's default draw order through the Editor authoring path. */
    default void setDefaultOrder(final int value) { throw unavailable("Part default-order editing"); }

    /** Returns this Part's opacity in {@code [0,1]}. */
    float getOpacity();

    /** Returns the Core index of the parent Part, or a negative value when there is none. */
    int parentIndex();

    /** Writes this Part's opacity through the Editor authoring path. */
    @CubismEditor("5.3.02")
    void setOpacity(float opacity);

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
