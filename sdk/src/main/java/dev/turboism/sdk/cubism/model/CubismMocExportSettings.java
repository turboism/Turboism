package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/** Explicit settings for official MOC 5.3 export; origin and scale use canvas pixels. */
@CubismEditor(from = "5.3.00")
public record CubismMocExportSettings(
        float originX,
        float originY,
        float pixelsPerUnit,
        boolean includeInvisibleParts,
        boolean includeSketchParts,
        boolean includeInvisibleMeshes,
        boolean includeInvisibleAliases,
        boolean extendEdgeColors,
        boolean includeArtMeshDisplayInfo) {
    /** Creates export settings with the original MOC3 and texture output. */
    public CubismMocExportSettings(
            final float originX,
            final float originY,
            final float pixelsPerUnit,
            final boolean includeInvisibleParts,
            final boolean includeSketchParts,
            final boolean includeInvisibleMeshes,
            final boolean includeInvisibleAliases,
            final boolean extendEdgeColors) {
        this(
                originX,
                originY,
                pixelsPerUnit,
                includeInvisibleParts,
                includeSketchParts,
                includeInvisibleMeshes,
                includeInvisibleAliases,
                extendEdgeColors,
                false);
    }

    public CubismMocExportSettings {
        if (!Float.isFinite(originX)
                || !Float.isFinite(originY)
                || !Float.isFinite(pixelsPerUnit)
                || pixelsPerUnit <= 0) {
            throw new IllegalArgumentException("Export origin must be finite and pixelsPerUnit positive and finite.");
        }
    }
}
