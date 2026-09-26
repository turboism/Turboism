package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Prints the exact admitted Cubism Editor version for an application artifact. */
public final class ReviewedHostArtifactCli {

    /**
     * Resolves an application artifact to its exact reviewed Cubism version.
     *
     * @param artifact path to {@code Live2D_Cubism.jar}
     * @return the exact version, or empty for an unreviewed artifact
     * @throws IOException if the artifact cannot be read
     */
    public static Optional<String> versionOf(final Path artifact) throws IOException {
        return ReviewedHostArtifacts.cubismVersionOf(HostArtifactDigest.from(artifact));
    }

    /**
     * Exits 0 and prints the host's declared Cubism version on line 1 plus an
     * admission marker on line 2 — {@code reviewed} for a byte-identical
     * reviewed artifact, {@code declared} otherwise. The declared version is
     * the host's own release identity; it is never relabeled as reviewed.
     * Exits 1 when no coherent identity is declared and 2 for invalid input or
     * an unreadable file.
     *
     * @param arguments the path to {@code Live2D_Cubism.jar}
     */
    public static void main(final String[] arguments) {
        if (arguments.length != 1) {
            System.err.println("usage: ReviewedHostArtifactCli <Live2D_Cubism.jar>");
            System.exit(2);
        }
        try {
            final Path artifact = Path.of(arguments[0]);
            final HostIdentityProbe probe = CubismEditorReleaseDetector.probe(artifact);
            if (!probe.declared()) {
                System.err.println(probe.status());
                System.exit(1);
            }
            final CubismHostIdentity identity = probe.identity().orElseThrow();
            System.out.println(identity.version());
            System.out.println(
                ReviewedHostArtifacts.cubismVersionOf(identity.artifact()).isPresent()
                    ? "reviewed"
                    : "declared"
            );
        } catch (Exception failure) {
            System.err.println(failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    private ReviewedHostArtifactCli() {
    }
}
