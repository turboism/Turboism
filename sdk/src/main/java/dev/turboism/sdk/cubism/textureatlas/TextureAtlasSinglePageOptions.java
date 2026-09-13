package dev.turboism.sdk.cubism.textureatlas;

/**
 * Native current-page packing policy. Only the issued input images may be placed; omitted
 * inputs are returned to the host as overflow, never planned onto another page.
 *
 * @param requestedScale fixed positive multiplier, or zero for automatic scale (at most 1)
 */
public record TextureAtlasSinglePageOptions(double requestedScale) {
    public TextureAtlasSinglePageOptions {
        if (!Double.isFinite(requestedScale) || requestedScale < 0) {
            throw new IllegalArgumentException("Requested scale must be finite and nonnegative.");
        }
    }
}
