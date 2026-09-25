package dev.turboism.adapter.cubism.optimization.deferred;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reviewed deferred GL error-check targets for the production
 * {@code launcher.mesaGlThread} option.
 *
 * <p>The obfuscated shader error-check helper {@code shader/A.a(GL,String,Z)}
 * (named {@code shader/y.a} on Cubism 5.2.03) calls {@code GL.glGetError} once
 * per invocation on every supported artifact — reviewed against the official
 * JARs. The deferred path replaces that per-call query with a frame-scoped
 * checkpoint and performs one real {@code glGetError} at the frame boundary
 * reported by {@code SGFramework/g.render3d(Lcom/live2d/graphics3d/a;)V}.
 * Both method bodies are shape-verified against the artifact's reference
 * bytes (plus the exact reviewed output of the upstream uniform-location
 * lifecycle transform when that hook is installed); any drift fails closed.
 */
public record DeferredGlErrorCheckTarget(
        HostArtifactDigest digest,
        String version,
        String errorOwner,
        String frameOwner) {

    /** Obfuscated shader helper owning the per-draw error-check site (5.3.x). */
    public static final String ERROR_OWNER = "com/live2d/graphics3d/shader/A";
    /** Same helper on Cubism 5.2.03, where the class kept its earlier name. */
    public static final String ERROR_OWNER_5203 = "com/live2d/graphics3d/shader/y";
    /** Render-scope owner whose {@code render3d} exit is the frame boundary. */
    public static final String FRAME_OWNER = "com/live2d/cubism/view/gl/SGFramework/g";
    /** Obfuscated error-check method name, identical on all supported artifacts. */
    public static final String ERROR_METHOD = "a";
    /** {@code a(GL, String, boolean) int} — returns the checked error code. */
    public static final String ERROR_DESCRIPTOR = "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I";
    /** Frame-boundary method name on the render-scope owner. */
    public static final String FRAME_METHOD = "render3d";
    /** {@code render3d(graphics3d/a) void}. */
    public static final String FRAME_DESCRIPTOR = "(Lcom/live2d/graphics3d/a;)V";

    public DeferredGlErrorCheckTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(errorOwner, "errorOwner");
        Objects.requireNonNull(frameOwner, "frameOwner");
    }

    private static final DeferredGlErrorCheckTarget CUBISM_5203 = new DeferredGlErrorCheckTarget(
        ReviewedHostArtifacts.CUBISM_5_2_03, "5.2.03", ERROR_OWNER_5203, FRAME_OWNER);
    private static final DeferredGlErrorCheckTarget CUBISM_5302 = new DeferredGlErrorCheckTarget(
        ReviewedHostArtifacts.CUBISM_5_3_02, "5.3.02", ERROR_OWNER, FRAME_OWNER);
    private static final DeferredGlErrorCheckTarget CUBISM_5303 = new DeferredGlErrorCheckTarget(
        ReviewedHostArtifacts.CUBISM_5_3_03, "5.3.03", ERROR_OWNER, FRAME_OWNER);

    /** The reviewed target pair for a host artifact digest, or empty when unsupported. */
    public static Optional<DeferredGlErrorCheckTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        if (CUBISM_5203.digest().equals(digest)) return Optional.of(CUBISM_5203);
        if (CUBISM_5302.digest().equals(digest)) return Optional.of(CUBISM_5302);
        if (CUBISM_5303.digest().equals(digest)) return Optional.of(CUBISM_5303);
        return Optional.empty();
    }

    /** Every reviewed target, oldest supported version first. */
    public static List<DeferredGlErrorCheckTarget> all() {
        return List.of(CUBISM_5203, CUBISM_5302, CUBISM_5303);
    }
}
