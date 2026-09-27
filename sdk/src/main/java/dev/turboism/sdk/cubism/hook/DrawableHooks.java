package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Drawable;

/** Override-based lifecycle hooks for ArtMesh authoring writes. */
public interface DrawableHooks {
    /**
     * Runs before an ArtMesh opacity write; the returned value is passed to the next hook and the
     * final value is sent to the native call.
     */
    default float beforeSetDrawableOpacity(final Drawable drawable, final float opacity) { return opacity; }
    /** Runs only when the ArtMesh opacity actually changed. */
    default void onDrawableOpacityChanged(final Drawable drawable, final float oldOpacity, final float newOpacity) { }
    /** Runs after the opacity write completed with the value that was applied. */
    default void afterSetDrawableOpacity(final Drawable drawable, final float opacity) { }

    /**
     * Runs before an ArtMesh visibility write; the returned value is passed to the next hook and
     * the final value is sent to the native call.
     */
    default boolean beforeSetDrawableVisible(final Drawable drawable, final boolean visible) { return visible; }
    /** Runs only when the ArtMesh visibility actually changed. */
    default void onDrawableVisibilityChanged(final Drawable drawable, final boolean oldVisible, final boolean newVisible) { }
    /** Runs after the visibility write completed with the value that was applied. */
    default void afterSetDrawableVisible(final Drawable drawable, final boolean visible) { }

    /**
     * Runs before an ArtMesh lock write; the returned value is passed to the next hook and the
     * final value is sent to the native call.
     */
    default boolean beforeSetDrawableLocked(final Drawable drawable, final boolean locked) { return locked; }
    /** Runs only when the ArtMesh lock state actually changed. */
    default void onDrawableLockChanged(final Drawable drawable, final boolean oldLocked, final boolean newLocked) { }
    /** Runs after the lock write completed with the value that was applied. */
    default void afterSetDrawableLocked(final Drawable drawable, final boolean locked) { }

    /**
     * Runs before an ArtMesh geometry replacement; the returned geometry is passed to the next
     * hook and the final geometry is committed as one Editor operation.
     */
    default ArtMeshGeometry beforeReplaceDrawableGeometry(
        final Drawable drawable, final ArtMeshGeometry geometry
    ) { return geometry; }
    /** Runs only when the ArtMesh geometry actually changed. */
    default void onDrawableGeometryChanged(
        final Drawable drawable, final ArtMeshGeometry oldGeometry, final ArtMeshGeometry newGeometry
    ) { }
    /** Runs after the geometry replacement completed with the geometry that was applied. */
    default void afterReplaceDrawableGeometry(
        final Drawable drawable, final ArtMeshGeometry geometry
    ) { }
}
