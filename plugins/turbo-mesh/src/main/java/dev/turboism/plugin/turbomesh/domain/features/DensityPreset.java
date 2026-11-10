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
    RIGID_GEOMETRY;

    /** Separator that turns an enum constant into a catalog key token. */
    private static final char CONSTANT_SEPARATOR = '_';

    /** Replacement of {@link #CONSTANT_SEPARATOR} in a catalog key token. */
    private static final char TOKEN_SEPARATOR = '-';

    /**
     * Returns the catalog key token of this preset.
     *
     * @return the token the message catalogs spell as {@code turbo-mesh.preset.<token>}
     */
    public String keyToken() {
        return tokenOf(name());
    }

    /**
     * Returns the catalog key token of a preset name.
     *
     * <p>Plans record their preset as text, so the token must derive from that text; a name that
     * matches no constant keeps a readable token instead of failing the dialog.</p>
     *
     * @param presetName the recorded preset name
     * @return the lower-case, hyphen-separated form of {@code presetName}
     */
    public static String tokenOf(final String presetName) {
        return presetName.trim().toLowerCase(java.util.Locale.ROOT).replace(CONSTANT_SEPARATOR, TOKEN_SEPARATOR);
    }
}
