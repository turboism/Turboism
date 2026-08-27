package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ArtMeshId;

import java.util.List;
import java.util.Optional;

/** One Cubism Part. */
@CubismEditor({"5.2.03", "5.3.02"})
public interface Part extends PartAppearanceAccess {

    PartId id();

    default int index() { throw unavailable("Part index"); }

    /** Editor display name, or the ID text when no authoring name is available. */
    default String name() { return id().value(); }

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

    default Optional<String> shortName() { throw unavailable("Part short name"); }

    default void setShortName(final Optional<String> value) {
        throw unavailable("Part short-name editing");
    }

    default Optional<PartId> parentId() { throw unavailable("Part parent"); }

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

    default boolean visible() { throw unavailable("Part visibility"); }

    default void setVisible(final boolean value) { throw unavailable("Part visibility editing"); }

    default boolean visibleInHierarchy() { throw unavailable("Part effective visibility"); }

    default boolean locked() { throw unavailable("Part lock state"); }

    default void setLocked(final boolean value) { throw unavailable("Part lock editing"); }

    default boolean lockedInHierarchy() { throw unavailable("Part effective lock state"); }

    /**
     * Returns this Part's keyform Morph Targets in stable host order.
     *
     * @throws UnsupportedOperationException when the backend does not expose them
     */
    default MorphTargets morphTargets() {
        throw unavailable("Part Morph Targets");
    }

    default Optional<Color> editColor() { throw unavailable("Part edit color"); }

    default void setEditColor(final Optional<Color> value) {
        throw unavailable("Part edit-color editing");
    }

    default boolean sketch() { throw unavailable("Part sketch state"); }

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

    default int defaultOrder() { throw unavailable("Part default order"); }

    default void setDefaultOrder(final int value) { throw unavailable("Part default-order editing"); }

    float getOpacity();

    int parentIndex();

    @CubismEditor("5.3.02")
    void setOpacity(float opacity);

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
