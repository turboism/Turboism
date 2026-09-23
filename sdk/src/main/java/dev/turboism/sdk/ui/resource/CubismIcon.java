package dev.turboism.sdk.ui.resource;

/**
 * Reviewed native object-icon meanings, independent of host resource paths and themes.
 *
 * <p>A key is not a capability claim. Runtime selects and validates the installed host's
 * resource; plugins must retain a localized textual fallback.</p>
 */
public enum CubismIcon {
    ART_MESH,
    WARP_DEFORMER,
    ROTATION_DEFORMER,
    PART
}
