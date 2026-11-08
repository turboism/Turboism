package dev.turboism.plugin.turbomesh.domain.features;

/**
 * Density presets for the 0.2.0 placement paths. A preset fixes the feature weights; it is a
 * starting point, never a semantic classification of the artwork.
 */
public enum DensityPreset {
    /** Balanced mix of boundary, corner, narrow-region and texture signals. */
    AUTO_BLEND,
    /** Elongated soft geometry: strong direction guidance, densified narrow spans. */
    SOFT_STRIP,
    /** Broad soft sheets: even coverage, mild texture following, no aggressive thinning. */
    SOFT_SHEET,
    /** Rigid decorative geometry: corners and holes dominate, texture barely matters. */
    RIGID_GEOMETRY
}
