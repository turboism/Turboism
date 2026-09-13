package dev.turboism.sdk.cubism.textureatlas;


/**
 * Host-independent bounds and policy for producing an atlas layout plan.
 *
 * <p>A {@code null} {@code singlePageOptions} retains complete-atlas authoring: every issued
 * input must be placed, with no rotation or scaling. Non-null options describe only the
 * current native invocation's page: omitted inputs are overflow, not a request to plan
 * another page. Options do not grant access to a different target or additional images.</p>
 *
 * @param pageWidth final page width in pixels
 * @param pageHeight final page height in pixels
 * @param edgeMargin minimum content inset at page boundaries, in final pixels
 * @param itemPadding minimum gap between content rectangles, in final pixels
 * @param maxPages allowed page count; exactly one for current-page requests
 * @param allowRotation whether current-page placements may rotate by 90 degrees
 * @param allowScaling whether current-page scale may differ from one
 * @param singlePageOptions current-page scale policy, or {@code null} for complete-atlas authoring
 */
public record TextureAtlasLayoutConstraints(
    int pageWidth,
    int pageHeight,
    int edgeMargin,
    int itemPadding,
    int maxPages,
    boolean allowRotation,
    boolean allowScaling,
    TextureAtlasSinglePageOptions singlePageOptions
) {

    /** Retains the complete-atlas, fixed-size contract of the original constructor. */
    public TextureAtlasLayoutConstraints(
        int pageWidth, int pageHeight, int edgeMargin, int itemPadding,
        int maxPages, boolean allowRotation, boolean allowScaling
    ) {
        this(pageWidth, pageHeight, edgeMargin, itemPadding, maxPages, allowRotation, allowScaling, null);
    }

    /**
     * Creates the native single-page contract. Margin is per image side: images have
     * margin at the page edge and twice that gap between them, independent of scale.
     */
    public static TextureAtlasLayoutConstraints currentPage(
        int width, int height, int margin, boolean rotate, double requestedScale
    ) {
        return new TextureAtlasLayoutConstraints(width, height, margin, Math.multiplyExact(margin, 2),
            1, rotate, requestedScale != 1D, new TextureAtlasSinglePageOptions(requestedScale));
    }
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
        if (singlePageOptions == null && (allowRotation || allowScaling)) {
            throw new IllegalArgumentException("Complete-atlas authoring does not support rotation or scaling.");
        }
        if (singlePageOptions != null && (maxPages != 1
            || (!allowScaling && singlePageOptions.requestedScale() != 1D))) {
            throw new IllegalArgumentException("Current-page options require one page and consistent scale policy.");
        }
    }
}
