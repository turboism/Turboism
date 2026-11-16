package dev.turboism.adapter.cubism.optimization.image;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Reviewed JOGL artifact and profile class the texture upload-preparation weave depends on.
 *
 * <p>The weave rewrites a shader factory whose own body consults {@code GLProfile}, and it is only
 * admitted on the JOGL build that ships beside the reviewed Editor artifact. Both facts are host
 * knowledge, so they live here rather than in the installer.</p>
 */
public final class TextureUploadPreparationTarget {

    /** JOGL profile class the rewritten factory consults; loaded from the bundled JOGL artifact. */
    public static final String PROFILE_OWNER = "com/jogamp/opengl/GLProfile";

    /** Directory of the JOGL artifact that ships beside the Editor artifact. */
    private static final String JOGL_DIRECTORY = "jogl";

    /** File name of the JOGL artifact that ships beside the Editor artifact. */
    private static final String JOGL_FILE = "jogl-all.jar";

    private TextureUploadPreparationTarget() {}

    /**
     * Resolves the JOGL artifact that ships beside {@code artifact}.
     *
     * @param artifact the located Editor artifact
     * @return the sibling JOGL artifact path
     */
    public static Path joglArtifact(final Path artifact) {
        return artifact.toAbsolutePath()
                .normalize()
                .getParent()
                .resolve(JOGL_DIRECTORY)
                .resolve(JOGL_FILE);
    }

    /**
     * Requires the sibling JOGL artifact to be the reviewed build.
     *
     * @param artifact the located Editor artifact
     * @return the reviewed JOGL artifact path
     * @throws IOException when the sibling artifact cannot be read
     * @throws IllegalArgumentException when the sibling JOGL build is not the reviewed one
     */
    public static Path requireReviewedJogl(final Path artifact) throws IOException {
        final Path jogl = joglArtifact(artifact);
        if (!ReviewedHostArtifacts.CUBISM_5_3_03_JOGL.equals(HostArtifactDigest.cached(jogl))) {
            throw new IllegalArgumentException("texture preparation requires reviewed JOGL artifact");
        }
        return jogl;
    }
}
