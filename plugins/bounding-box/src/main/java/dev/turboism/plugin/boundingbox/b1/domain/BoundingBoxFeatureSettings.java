package dev.turboism.plugin.boundingbox.b1.domain;

public record BoundingBoxFeatureSettings(
    boolean overlayButtonsEnabled,
    boolean workspaceButtonsEnabled,
    boolean mirrorAndShrinkSuppressed
) {
    public static BoundingBoxFeatureSettings defaults() {
        return new BoundingBoxFeatureSettings(true, true, false);
    }

    public BoundingBoxFeatureSettings withOverlayButtonsEnabled(final boolean value) {
        return value == overlayButtonsEnabled ? this
            : new BoundingBoxFeatureSettings(value, workspaceButtonsEnabled, mirrorAndShrinkSuppressed);
    }

    public BoundingBoxFeatureSettings withWorkspaceButtonsEnabled(final boolean value) {
        return value == workspaceButtonsEnabled ? this
            : new BoundingBoxFeatureSettings(overlayButtonsEnabled, value, mirrorAndShrinkSuppressed);
    }

    public BoundingBoxFeatureSettings withMirrorAndShrinkSuppressed(final boolean value) {
        return value == mirrorAndShrinkSuppressed ? this
            : new BoundingBoxFeatureSettings(overlayButtonsEnabled, workspaceButtonsEnabled, value);
    }
}
