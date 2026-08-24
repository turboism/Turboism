package dev.turboism.sdk.cubism.textureatlas;


/** Host-independent bounds and policy for producing an atlas layout plan. */
public record TextureAtlasLayoutConstraints(
    int pageWidth,
    int pageHeight,
    int edgeMargin,
    int itemPadding,
    int maxPages,
    boolean allowRotation,
    boolean allowScaling
) {

    public TextureAtlasLayoutConstraints {
        if (pageWidth < 1 || pageHeight < 1) {
            throw new IllegalArgumentException("Atlas page dimensions must be positive.");
        }
        if (edgeMargin < 0 || itemPadding < 0) {
            throw new IllegalArgumentException("Atlas margin and padding must not be negative.");
        }
        if ((long) edgeMargin * 2 >= pageWidth || (long) edgeMargin * 2 >= pageHeight) {
            throw new IllegalArgumentException("Atlas edge margin must leave a positive usable area.");
        }
        if (maxPages < 1) {
            throw new IllegalArgumentException("Atlas maxPages must be positive.");
        }
        if (allowRotation || allowScaling) {
            throw new IllegalArgumentException("Rotation and scaling are not supported by this Preview tracer.");
        }
    }
}
