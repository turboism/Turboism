package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.cubism.model.Part;

/** Override-based lifecycle hooks for Cubism parts. */
public interface PartHooks {

    default String beforeSetPartName(final Part part, final String name) {
        return name;
    }

    default void onPartNameChanged(
        final Part part,
        final String oldName,
        final String newName
    ) {
    }

    default void afterSetPartName(final Part part, final String name) {
    }

    default float beforeSetPartOpacity(
        final Part part,
        final float opacity
    ) {
        return opacity;
    }

    default void onPartOpacityChanged(
        final Part part,
        final float oldOpacity,
        final float newOpacity
    ) {
    }

    default void afterSetPartOpacity(
        final Part part,
        final float opacity
    ) {
    }
}
