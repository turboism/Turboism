package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;

/** Override-based lifecycle hooks for Warp and Rotation Deformer authoring writes. */
public interface DeformerHooks {
    /**
     * Runs before a deformer opacity write; the returned value is passed to the next hook and the
     * final value is sent to the native call.
     */
    default float beforeSetDeformerOpacity(final Deformer deformer, final float opacity) { return opacity; }
    /** Runs only when the deformer opacity actually changed. */
    default void onDeformerOpacityChanged(final Deformer deformer, final float oldOpacity, final float newOpacity) { }
    /** Runs after the opacity write completed with the value that was applied. */
    default void afterSetDeformerOpacity(final Deformer deformer, final float opacity) { }

    /**
     * Runs before a deformer visibility write; the returned value is passed to the next hook and
     * the final value is sent to the native call.
     */
    default boolean beforeSetDeformerVisible(final Deformer deformer, final boolean visible) { return visible; }
    /** Runs only when the deformer visibility actually changed. */
    default void onDeformerVisibilityChanged(final Deformer deformer, final boolean oldVisible, final boolean newVisible) { }
    /** Runs after the visibility write completed with the value that was applied. */
    default void afterSetDeformerVisible(final Deformer deformer, final boolean visible) { }

    /**
     * Runs before a deformer lock write; the returned value is passed to the next hook and the
     * final value is sent to the native call.
     */
    default boolean beforeSetDeformerLocked(final Deformer deformer, final boolean locked) { return locked; }
    /** Runs only when the deformer lock state actually changed. */
    default void onDeformerLockChanged(final Deformer deformer, final boolean oldLocked, final boolean newLocked) { }
    /** Runs after the lock write completed with the value that was applied. */
    default void afterSetDeformerLocked(final Deformer deformer, final boolean locked) { }

    /**
     * Runs before a Warp Deformer grid replacement; the returned grid is passed to the next hook
     * and the final grid is committed as one Editor operation.
     */
    default WarpGrid beforeReplaceWarpDeformerGrid(
        final WarpDeformer deformer, final WarpGrid grid
    ) { return grid; }
    /** Runs only when the Warp Deformer grid actually changed. */
    default void onWarpDeformerGridChanged(
        final WarpDeformer deformer, final WarpGrid oldGrid, final WarpGrid newGrid
    ) { }
    /** Runs after the grid replacement completed with the grid that was applied. */
    default void afterReplaceWarpDeformerGrid(final WarpDeformer deformer, final WarpGrid grid) { }

    /**
     * Runs before a Rotation Deformer base-angle write; the returned angle is passed to the
     * next hook and the final value is sent to the native call.
     */
    default float beforeSetRotationDeformerBaseAngle(
        final RotationDeformer deformer, final float angle
    ) { return angle; }
    /** Runs only when the Rotation Deformer base angle actually changed. */
    default void onRotationDeformerBaseAngleChanged(
        final RotationDeformer deformer, final float oldAngle, final float newAngle
    ) { }
    /** Runs after the base-angle write completed with the angle that was applied. */
    default void afterSetRotationDeformerBaseAngle(
        final RotationDeformer deformer, final float angle
    ) { }

    /**
     * Runs before a Rotation Deformer keyform replacement; the returned form is passed to the
     * next hook and the final form is committed as one Editor operation.
     */
    default RotationDeformerForm beforeReplaceRotationDeformerForm(
        final RotationDeformer deformer, final RotationDeformerForm form
    ) { return form; }
    /** Runs only when the Rotation Deformer keyform actually changed. */
    default void onRotationDeformerFormChanged(
        final RotationDeformer deformer,
        final RotationDeformerForm oldForm,
        final RotationDeformerForm newForm
    ) { }
    /** Runs after the keyform replacement completed with the form that was applied. */
    default void afterReplaceRotationDeformerForm(
        final RotationDeformer deformer, final RotationDeformerForm form
    ) { }
}
