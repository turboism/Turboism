package dev.turboism.plugin.boundingbox.b1.domain;

/**
 * The bounding-box plugin's user-facing feature switches, as a value.
 *
 * @param overlayButtonsEnabled whether the plugin's buttons are drawn on the viewport overlay
 * @param workspaceButtonsEnabled whether the plugin's buttons are drawn in the workspace palette
 * @param mirrorAndShrinkSuppressed whether the mirror and shrink actions are withheld; note the
 *     inverted sense — true means those actions are <em>not</em> offered
 */
public record BoundingBoxFeatureSettings(
    boolean overlayButtonsEnabled,
    boolean workspaceButtonsEnabled,
    boolean mirrorAndShrinkSuppressed
) {
    /** @return the shipped defaults: both button surfaces on, mirror and shrink not suppressed */
    public static BoundingBoxFeatureSettings defaults() {
        return new BoundingBoxFeatureSettings(true, true, false);
    }

    /**
     * @param value the new overlay-buttons setting
     * @return a settings value with that setting; {@code this} when the value is unchanged, so an
     *     idempotent set costs no allocation and compares identical
     */
    public BoundingBoxFeatureSettings withOverlayButtonsEnabled(final boolean value) {
        return value == overlayButtonsEnabled ? this
            : new BoundingBoxFeatureSettings(value, workspaceButtonsEnabled, mirrorAndShrinkSuppressed);
    }

    /**
     * @param value the new workspace-buttons setting
     * @return a settings value with that setting; {@code this} when the value is unchanged
     */
    public BoundingBoxFeatureSettings withWorkspaceButtonsEnabled(final boolean value) {
        return value == workspaceButtonsEnabled ? this
            : new BoundingBoxFeatureSettings(overlayButtonsEnabled, value, mirrorAndShrinkSuppressed);
    }

    /**
     * @param value true to withhold the mirror and shrink actions, false to offer them
     * @return a settings value with that setting; {@code this} when the value is unchanged
     */
    public BoundingBoxFeatureSettings withMirrorAndShrinkSuppressed(final boolean value) {
        return value == mirrorAndShrinkSuppressed ? this
            : new BoundingBoxFeatureSettings(overlayButtonsEnabled, workspaceButtonsEnabled, value);
    }
}
