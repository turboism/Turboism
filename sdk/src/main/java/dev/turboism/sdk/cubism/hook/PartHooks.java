package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.cubism.model.Part;

/** Override-based lifecycle hooks for Cubism parts. */
public interface PartHooks {

    /**
     * Runs before a part rename; the returned name is passed to the next hook and the final name
     * is sent to the native call.
     */
    default String beforeSetPartName(final Part part, final String name) {
        return name;
    }

    /** Runs only when the part name actually changed. */
    default void onPartNameChanged(
        final Part part,
        final String oldName,
        final String newName
    ) {
    }

    /** Runs after the rename completed with the name that was applied. */
    default void afterSetPartName(final Part part, final String name) {
    }

    /**
     * Runs before a part opacity write; the returned value is passed to the next hook and the
     * final value is sent to the native call.
     */
    default float beforeSetPartOpacity(
        final Part part,
        final float opacity
    ) {
        return opacity;
    }

    /** Runs only when the part opacity actually changed. */
    default void onPartOpacityChanged(
        final Part part,
        final float oldOpacity,
        final float newOpacity
    ) {
    }

    /** Runs after the opacity write completed with the value that was applied. */
    default void afterSetPartOpacity(
        final Part part,
        final float opacity
    ) {
    }
}
