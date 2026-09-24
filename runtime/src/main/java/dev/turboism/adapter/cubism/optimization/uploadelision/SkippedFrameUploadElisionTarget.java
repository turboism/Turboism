package dev.turboism.adapter.cubism.optimization.uploadelision;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reviewed upload-elision target for the test-only skipped-frame experiment.
 *
 * <p>The persistent-VBO wrappers {@code mesh/a/b} (float attributes) and
 * {@code mesh/a/c} (integer/index data) share the identical private upload
 * method {@code b(GL2ES2,int)}: bind, then either {@code glBufferData} on the
 * reallocate path or {@code glBufferSubData} on the dirty path, followed by
 * dirty-flag clearing. The method body is bytecode-identical between the
 * reviewed 5.3.02 and 5.3.03 artifacts (verified by javap comparison). The
 * 5.2.03 artifact keeps the same wrapper layout but its {@code shader/A}
 * helper lacks the reviewed {@code a(Buffer)J} size method the bridge reads,
 * so it stays unsupported.</p>
 */
public record SkippedFrameUploadElisionTarget(
        HostArtifactDigest digest,
        String version) {

    /** Persistent float-attribute VBO wrapper. */
    public static final String FLOAT_OWNER = "com/live2d/graphics3d/mesh/a/b";
    /** Persistent integer/index VBO wrapper. */
    public static final String INDEX_OWNER = "com/live2d/graphics3d/mesh/a/c";
    /** Shared wrapper base owning the name holder, dirty flags and accessors. */
    public static final String BASE_OWNER = "com/live2d/graphics3d/mesh/a/a";
    /** Shader helper owning the reviewed payload-size method. */
    public static final String SIZE_OWNER = "com/live2d/graphics3d/shader/A";
    /** Private upload method inside both wrappers: {@code b(GL2ES2,int)}. */
    public static final String METHOD = "b";
    /** {@code b(GL2ES2,int) void}. */
    public static final String DESCRIPTOR = "(Lcom/jogamp/opengl/GL2ES2;I)V";

    /** Both wrapper owners rewritten by this experiment. */
    public static final List<String> OWNERS = List.of(FLOAT_OWNER, INDEX_OWNER);

    public SkippedFrameUploadElisionTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    private static final SkippedFrameUploadElisionTarget CUBISM_5302 =
        new SkippedFrameUploadElisionTarget(ReviewedHostArtifacts.CUBISM_5_3_02, "5.3.02");
    private static final SkippedFrameUploadElisionTarget CUBISM_5303 =
        new SkippedFrameUploadElisionTarget(ReviewedHostArtifacts.CUBISM_5_3_03, "5.3.03");

    /** The reviewed target for a host artifact digest, or empty when unsupported. */
    public static Optional<SkippedFrameUploadElisionTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        if (CUBISM_5302.digest().equals(digest)) return Optional.of(CUBISM_5302);
        if (CUBISM_5303.digest().equals(digest)) return Optional.of(CUBISM_5303);
        return Optional.empty();
    }

    /** Every reviewed target, oldest supported version first. */
    public static List<SkippedFrameUploadElisionTarget> all() {
        return List.of(CUBISM_5302, CUBISM_5303);
    }
}
