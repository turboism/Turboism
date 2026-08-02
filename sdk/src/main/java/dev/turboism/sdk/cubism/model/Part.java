package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Optional;

/** One Cubism Part. */
@PreviewApi
public interface Part {

    PartId id();

    default int index() { throw unavailable("Part index"); }

    /** Editor display name, or the ID text when no authoring name is available. */
    default String name() { return id().value(); }

    void setName(String name);

    default Optional<String> shortName() { throw unavailable("Part short name"); }

    default void setShortName(final Optional<String> value) {
        throw unavailable("Part short-name editing");
    }

    default Optional<PartId> parentId() { throw unavailable("Part parent"); }

    default List<PartId> childIds() { throw unavailable("Part children"); }

    default boolean visible() { throw unavailable("Part visibility"); }

    default void setVisible(final boolean value) { throw unavailable("Part visibility editing"); }

    default boolean visibleInHierarchy() { throw unavailable("Part effective visibility"); }

    default boolean locked() { throw unavailable("Part lock state"); }

    default void setLocked(final boolean value) { throw unavailable("Part lock editing"); }

    default boolean lockedInHierarchy() { throw unavailable("Part effective lock state"); }

    default Optional<Color> editColor() { throw unavailable("Part edit color"); }

    default void setEditColor(final Optional<Color> value) {
        throw unavailable("Part edit-color editing");
    }

    default boolean sketch() { throw unavailable("Part sketch state"); }

    default void setSketch(final boolean value) { throw unavailable("Part sketch editing"); }

    default int defaultOrder() { throw unavailable("Part default order"); }

    default void setDefaultOrder(final int value) { throw unavailable("Part default-order editing"); }

    float getOpacity();

    int parentIndex();

    void setOpacity(float opacity);

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
