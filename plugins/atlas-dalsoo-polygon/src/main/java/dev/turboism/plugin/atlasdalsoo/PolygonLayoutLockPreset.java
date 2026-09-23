package dev.turboism.plugin.atlasdalsoo;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;

/**
 * Global lock preset applied as the default layer beneath per-item policies.
 *
 * <p>Mirrors the Cubism 5.4 automatic-layout {@code AutoLayoutLock} flag
 * combinations: {@code fixPosition} maps to {@code preservePosition},
 * {@code fixRotate} to {@code preserveAngle} and {@code fixScale} to
 * {@code preserveScale}. Every preset keeps items participating; a per-item
 * {@link TextureAtlasItemLayoutPolicy} entry overrides the preset entirely.</p>
 */
public enum PolygonLayoutLockPreset {

    /** No locks: items may be moved, rotated and scaled. */
    NONE(false, false, false),
    /** fixPosition + fixRotate + fixScale: items keep their issued transform. */
    ALL(true, true, true),
    /** fixRotate only. */
    ANGLE(false, true, false),
    /** fixScale only. */
    SCALE(false, false, true),
    /** fixRotate + fixScale. */
    ANGLE_SCALE(false, true, true),
    /** fixPosition + fixRotate (position lock implies the angle lock). */
    POS_ANGLE(true, true, false);

    private final boolean fixPosition;
    private final boolean fixRotate;
    private final boolean fixScale;

    PolygonLayoutLockPreset(final boolean fixPosition, final boolean fixRotate,
        final boolean fixScale) {
        this.fixPosition = fixPosition;
        this.fixRotate = fixRotate;
        this.fixScale = fixScale;
    }

    /** The default per-item policy this preset implies. */
    public TextureAtlasItemLayoutPolicy toPolicy(final String textureId) {
        return new TextureAtlasItemLayoutPolicy(textureId, true,
            fixRotate, fixScale, fixPosition);
    }
}
