package dev.turboism.adapter.cubism.optimization.glerror;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reviewed {@code glGetError} elision target for the test-only elision experiment.
 *
 * <p>The obfuscated shader error-check marker {@code shader/A.a(GL,String,Z)}
 * unconditionally calls {@code GL.glGetError} once per invocation (bytecode offset 16
 * on both supported 5.3.x artifacts; reviewed against the official JARs). The marker
 * method's own bytecode shape is verified at install time against the artifact's
 * reference bytes, so a version whose method drifts fails closed at the shape gate.
 * Cubism 5.2.03 has no such method on this owner and is intentionally absent.</p>
 */
public record GlGetErrorElisionTarget(
        HostArtifactDigest digest, String version, String owner, String method, String descriptor) {

    /** Obfuscated shader helper owning the unconditional error-check marker. */
    public static final String OWNER = "com/live2d/graphics3d/shader/A";
    /** Obfuscated marker method name, identical on both supported artifacts. */
    public static final String METHOD = "a";
    /** {@code a(GL, String, boolean) int} — returns the checked error code. */
    public static final String DESCRIPTOR = "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I";

    public GlGetErrorElisionTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(descriptor, "descriptor");
    }

    private static final GlGetErrorElisionTarget CUBISM_5302 =
            new GlGetErrorElisionTarget(ReviewedHostArtifacts.CUBISM_5_3_02, "5.3.02", OWNER, METHOD, DESCRIPTOR);

    private static final GlGetErrorElisionTarget CUBISM_5303 =
            new GlGetErrorElisionTarget(ReviewedHostArtifacts.CUBISM_5_3_03, "5.3.03", OWNER, METHOD, DESCRIPTOR);

    /** The reviewed target for a host artifact digest, or empty when unsupported. */
    public static Optional<GlGetErrorElisionTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        if (CUBISM_5302.digest().equals(digest)) return Optional.of(CUBISM_5302);
        if (CUBISM_5303.digest().equals(digest)) return Optional.of(CUBISM_5303);
        return Optional.empty();
    }

    /** Every reviewed target, oldest supported version first. */
    public static List<GlGetErrorElisionTarget> all() {
        return List.of(CUBISM_5302, CUBISM_5303);
    }
}
