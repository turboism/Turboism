package dev.turboism.adapter.cubism.optimization.deferred;

import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer;
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
 * JARs at bytecode offset 16 on 5.3.00–5.3.04. The deferred path replaces that
 * per-call query with a frame-scoped
 * checkpoint and performs one real {@code glGetError} at the frame boundary
 * reported by {@code SGFramework/g.render3d(Lcom/live2d/graphics3d/a;)V}.
 * Both method bodies are shape-verified against the artifact's reference
 * bytes (plus the exact reviewed output of the upstream uniform-location
 * lifecycle transform when that hook is installed); any drift fails closed.
 */
public record DeferredGlErrorCheckTarget(
        HostArtifactDigest digest, String version, String errorOwner, String frameOwner) {

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

    /**
     * The reviewed target pair for a host artifact digest, or empty when
     * unsupported. Admission follows the uniform-location lifecycle seam: the
     * deferred checkpoint composes with that hook's rewrites, so every reviewed
     * generation whose lifecycle owners are pinned (
     * {@link UniformLocationLifecycleTransformer#supportedEditorVersion}) is
     * admitted and no other reviewed generation is. The per-artifact
     * {@code ReviewedMethodShape} gates still verify both bodies — plus the
     * probe-derived composed shape when the upstream transform is installed —
     * against the artifact's own reference bytes before any rewrite.
     */
    public static Optional<DeferredGlErrorCheckTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .filter(UniformLocationLifecycleTransformer::supportedEditorVersion)
                .map(version -> new DeferredGlErrorCheckTarget(
                        digest,
                        version,
                        UniformLocationLifecycleTransformer.Role.ERROR.ownerForVersion(version),
                        UniformLocationLifecycleTransformer.Role.FRAME.ownerForVersion(version)));
    }

    /** Every reviewed target, oldest supported version first. */
    public static List<DeferredGlErrorCheckTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }
}
