package dev.turboism.adapter.cubism.optimization.meshsetup;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reviewed host seam for the skipped-frame {@code GMesh2.setup} elision.
 *
 * <p>{@code GMesh2.setup(GFrame)} is called by {@code GMeshRenderer.render} for
 * every visible mesh on every paint. Its whole body is nine persistent-VBO
 * refresh calls: positions, normals, uvs, uvs2, uvs3, colors32, colors, alpha
 * and indices. The reviewed body has no other side effect. When the native
 * model update was skipped, the mesh arrays and their uploaded buffers are
 * still current, so the nine refreshes are redundant.</p>
 *
 * <p>The transformer only rewrites the exact reviewed method shape read from
 * the running official artifact. The body was byte-identical across the
 * sampled 5.0.01, 5.0.05, 5.1.00, 5.2.03, 5.3.00, 5.3.02 and 5.3.04 artifacts; the
 * hook is admitted for every reviewed artifact and still fails closed if the
 * runtime shape differs from the official reference bytes.</p>
 */
public record SkippedFrameMeshSetupElisionTarget(HostArtifactDigest digest, String version) {

    /** The final mesh class whose setup method is rewritten. */
    public static final String OWNER = "com/live2d/graphics3d/mesh/GMesh2";

    /** Reviewed setup method name. */
    public static final String METHOD = "setup";

    /** {@code setup(GFrame) void}. */
    public static final String DESCRIPTOR = "(Lcom/live2d/graphics3d/a;)V";

    /** Persistent buffer base class owning the protected name/dirty accessors. */
    public static final String BASE_OWNER = "com/live2d/graphics3d/mesh/a/a";

    /** Reviewed frame owner exposing {@code a()GL3}. */
    public static final String FRAME_OWNER = "com/live2d/graphics3d/a";

    /** Reviewed frame accessor used for GL-context validation. */
    public static final String FRAME_METHOD = "a";

    /** {@code a()GL3}. */
    public static final String FRAME_DESCRIPTOR = "()Lcom/jogamp/opengl/GL3;";

    /** Protected buffer-name accessor {@code i()IntBuffer}. */
    public static final String NAME_METHOD = "i";

    /** {@code i()IntBuffer}. */
    public static final String NAME_DESCRIPTOR = "()Ljava/nio/IntBuffer;";

    /** Protected dirty-flag accessor {@code k()Z}. */
    public static final String DIRTY_METHOD = "k";

    /** {@code k()Z}. */
    public static final String DIRTY_DESCRIPTOR = "()Z";

    /**
     * SHA-256 over the constant-pool-independent setup instruction shape,
     * observed byte-identical on 5.0.01, 5.0.05, 5.1.00, 5.2.03, 5.3.00,
     * 5.3.02 and 5.3.04. A reviewed generation with a different body fails admission
     * closed instead of skipping unknown side effects.
     */
    public static final String SETUP_SHAPE_SHA256 = "902b0b7d91631b73c7a293696bf46d21ecfa6283e92204eaca82933d56698be7";

    /** Every buffer getter called by the reviewed setup body, in visit order. */
    public static final List<String> BUFFER_GETTERS = List.of(
            "getPositionsBuffer",
            "getNormalsBuffer",
            "getUvsBuffer",
            "getUvs2Buffer",
            "getUvs3Buffer",
            "getColors32Buffer",
            "getColorsBuffer",
            "getAlphaBuffer",
            "getIndicesBuffer");

    /** Every reviewed target, oldest supported version first. */
    private static final List<SkippedFrameMeshSetupElisionTarget> TARGETS = ReviewedHostArtifacts.all().stream()
            .flatMap(
                    digest -> ReviewedHostArtifacts.cubismVersionOf(digest)
                            .map(version -> new SkippedFrameMeshSetupElisionTarget(digest, version))
                            .stream())
            .toList();

    public SkippedFrameMeshSetupElisionTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    /** Returns whether the observed setup shape is the reviewed version-compatible body. */
    public static boolean hasReviewedSetupShape(final List<String> shape) {
        return shape != null && SETUP_SHAPE_SHA256.equals(setupShapeSha256(shape));
    }

    /** Hashes one {@link dev.turboism.adapter.cubism.optimization.ReviewedMethodShape} instruction list. */
    public static String setupShapeSha256(final List<String> shape) {
        Objects.requireNonNull(shape, "shape");
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (final String instruction : shape) {
                digest.update(instruction.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** The reviewed target for a host artifact digest, or empty when unsupported. */
    public static Optional<SkippedFrameMeshSetupElisionTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return TARGETS.stream().filter(target -> target.digest().equals(digest)).findFirst();
    }

    /** Every reviewed target, oldest supported version first. */
    public static List<SkippedFrameMeshSetupElisionTarget> all() {
        return TARGETS;
    }
}
