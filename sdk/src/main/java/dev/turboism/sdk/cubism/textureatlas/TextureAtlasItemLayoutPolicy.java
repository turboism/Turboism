package dev.turboism.sdk.cubism.textureatlas;

import java.util.Objects;

/**
 * Per-item automatic-layout policy.
 *
 * <p>Mirrors the per-object controls Cubism 5.4 exposes for automatic layout:
 * {@code participate} is the layout-target toggle (when false the item is not moved
 * but still blocks page space wherever it currently sits), {@code preserveAngle}
 * fixes the issued rotation, {@code preserveScale} fixes the issued scale, and
 * {@code preservePosition} fixes the issued position (and therefore the angle as
 * well). Cubism 5.2/5.3 hosts cannot persist these flags, so callers store them in
 * Turboism-side configuration keyed by model and texture id.</p>
 */
public record TextureAtlasItemLayoutPolicy(
    String textureId,
    boolean participate,
    boolean preserveAngle,
    boolean preserveScale,
    boolean preservePosition
) {

    public TextureAtlasItemLayoutPolicy {
        Objects.requireNonNull(textureId, "textureId");
        if (textureId.isBlank()) {
            throw new IllegalArgumentException("textureId is required");
        }
    }

    /** Default policy: the item participates and may be moved, rotated and scaled. */
    public static TextureAtlasItemLayoutPolicy participating(final String textureId) {
        return new TextureAtlasItemLayoutPolicy(textureId, true, false, false, false);
    }

    /** Policy for an item that opts out of layout and keeps its issued transform. */
    public static TextureAtlasItemLayoutPolicy excluded(final String textureId) {
        return new TextureAtlasItemLayoutPolicy(textureId, false, false, false, false);
    }
}
