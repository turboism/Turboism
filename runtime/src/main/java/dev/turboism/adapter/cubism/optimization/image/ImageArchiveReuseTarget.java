package dev.turboism.adapter.cubism.optimization.image;

/**
 * Reviewed host classes the PNG archive-reuse weave and its bridge name.
 *
 * <p>The transform reads the resource class's own encoder call sites, and its callback writes the
 * image class's fields, so both classes must come from the one attested host loader. The names live
 * here instead of in the installer, which keeps the composition layer free of host-package
 * literals.</p>
 */
public final class ImageArchiveReuseTarget {

    /** Resource class whose PNG encoder call site the weave rewrites. */
    public static final String RESOURCE_OWNER = "com/live2d/graphics/CImageResource";

    /** Image class whose fields the reuse callback reads. */
    public static final String IMAGE_OWNER = "com/live2d/graphics/CWritableImage";

    private ImageArchiveReuseTarget() {}
}
