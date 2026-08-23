package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;

/** Override-based lifecycle hooks for Warp and Rotation Deformer authoring writes. */
public interface DeformerHooks {
    default float beforeSetDeformerOpacity(final Deformer deformer, final float opacity) { return opacity; }
    default void onDeformerOpacityChanged(final Deformer deformer, final float oldOpacity, final float newOpacity) { }
    default void afterSetDeformerOpacity(final Deformer deformer, final float opacity) { }

    default boolean beforeSetDeformerVisible(final Deformer deformer, final boolean visible) { return visible; }
    default void onDeformerVisibilityChanged(final Deformer deformer, final boolean oldVisible, final boolean newVisible) { }
    default void afterSetDeformerVisible(final Deformer deformer, final boolean visible) { }

    default boolean beforeSetDeformerLocked(final Deformer deformer, final boolean locked) { return locked; }
    default void onDeformerLockChanged(final Deformer deformer, final boolean oldLocked, final boolean newLocked) { }
    default void afterSetDeformerLocked(final Deformer deformer, final boolean locked) { }

    default WarpGrid beforeReplaceWarpDeformerGrid(
        final WarpDeformer deformer, final WarpGrid grid
    ) { return grid; }
    default void onWarpDeformerGridChanged(
        final WarpDeformer deformer, final WarpGrid oldGrid, final WarpGrid newGrid
    ) { }
    default void afterReplaceWarpDeformerGrid(final WarpDeformer deformer, final WarpGrid grid) { }

    default float beforeSetRotationDeformerBaseAngle(
        final RotationDeformer deformer, final float angle
    ) { return angle; }
    default void onRotationDeformerBaseAngleChanged(
        final RotationDeformer deformer, final float oldAngle, final float newAngle
    ) { }
    default void afterSetRotationDeformerBaseAngle(
        final RotationDeformer deformer, final float angle
    ) { }

    default RotationDeformerForm beforeReplaceRotationDeformerForm(
        final RotationDeformer deformer, final RotationDeformerForm form
    ) { return form; }
    default void onRotationDeformerFormChanged(
        final RotationDeformer deformer,
        final RotationDeformerForm oldForm,
        final RotationDeformerForm newForm
    ) { }
    default void afterReplaceRotationDeformerForm(
        final RotationDeformer deformer, final RotationDeformerForm form
    ) { }
}
