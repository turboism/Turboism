package dev.turboism.adapter.cubism.optimization.clipmask;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Objects;
import java.util.Optional;

/**
 * Reviewed selectors and descriptors for the zero-quality-loss clipping-mask
 * deduplication optimization across admitted Cubism versions.
 *
 * <p>The mask pass lives in {@code com/live2d/graphics3d/rendering/e} on 5.3.x and
 * in {@code .../rendering/d} on 5.0.x-5.2.x. Inside the mask pass
 * {@code a(AGRenderer, a, CRect)}, multiple clipped renderers in the same frame
 * often reference identical ordered mask sets. On a dedupe hit, the expensive FBO
 * acquire, bind, full-canvas clear and mask mesh draws are skipped; the cached
 * mask texture and uniforms are applied to the material directly.</p>
 */
public final class ClipMaskDeduplicationTarget {

    /** Render system owner on Cubism 5.0.x-5.2.x. */
    public static final String RENDER_SYSTEM_50 = "com/live2d/graphics3d/rendering/d";

    /** Render system owner on Cubism 5.3.x. */
    public static final String RENDER_SYSTEM_53 = "com/live2d/graphics3d/rendering/e";

    /** Mask pass method name in the render system. */
    public static final String MASK_PASS_METHOD = "a";

    /** Mask pass method descriptor. */
    public static final String MASK_PASS_DESCRIPTOR =
            "(Lcom/live2d/graphics3d/component/AGRenderer;Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;)V";

    /** End clipping companion method name in the render system. */
    public static final String END_CLIPPING_METHOD = "a";

    /** End clipping companion method descriptor. */
    public static final String END_CLIPPING_DESCRIPTOR =
            "(Lcom/live2d/graphics3d/component/AGRenderer;Lcom/live2d/graphics3d/a;)V";

    /** Top-level render method name in the render system (frame boundary). */
    public static final String RENDER_METHOD = "a";

    private ClipMaskDeduplicationTarget() {}

    /**
     * @param version exact reviewed Cubism version
     * @return the render system owner carrying the mask pass on that version
     */
    public static String renderSystemOwner(final String version) {
        Objects.requireNonNull(version, "version");
        return version.startsWith("5.3.") ? RENDER_SYSTEM_53 : RENDER_SYSTEM_50;
    }

    /**
     * @param version exact reviewed Cubism version
     * @return top-level scene render descriptor for that version
     */
    public static String renderMethodDescriptor(final String version) {
        Objects.requireNonNull(version, "version");
        return version.startsWith("5.3.")
                ? "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;ZZ)V"
                : "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Z)V";
    }

    /**
     * @param digest admitted host artifact
     * @return the render system owner for that artifact's version, or empty
     */
    public static Optional<String> renderSystemOwnerOf(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest).map(ClipMaskDeduplicationTarget::renderSystemOwner);
    }
}
