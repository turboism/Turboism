package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Drawable;

/** Override-based lifecycle hooks for ArtMesh authoring writes. */
@PreviewApi
public interface DrawableHooks {
    default float beforeSetDrawableOpacity(final Drawable drawable, final float opacity) { return opacity; }
    default void onDrawableOpacityChanged(final Drawable drawable, final float oldOpacity, final float newOpacity) { }
    default void afterSetDrawableOpacity(final Drawable drawable, final float opacity) { }

    default boolean beforeSetDrawableVisible(final Drawable drawable, final boolean visible) { return visible; }
    default void onDrawableVisibilityChanged(final Drawable drawable, final boolean oldVisible, final boolean newVisible) { }
    default void afterSetDrawableVisible(final Drawable drawable, final boolean visible) { }

    default boolean beforeSetDrawableLocked(final Drawable drawable, final boolean locked) { return locked; }
    default void onDrawableLockChanged(final Drawable drawable, final boolean oldLocked, final boolean newLocked) { }
    default void afterSetDrawableLocked(final Drawable drawable, final boolean locked) { }

    default ArtMeshGeometry beforeReplaceDrawableGeometry(
        final Drawable drawable, final ArtMeshGeometry geometry
    ) { return geometry; }
    default void onDrawableGeometryChanged(
        final Drawable drawable, final ArtMeshGeometry oldGeometry, final ArtMeshGeometry newGeometry
    ) { }
    default void afterReplaceDrawableGeometry(
        final Drawable drawable, final ArtMeshGeometry geometry
    ) { }
}
