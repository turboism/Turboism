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
     * Exits 0 and prints the exact Cubism version for a reviewed artifact.
     * Exits 1 for an unreviewed artifact and 2 for invalid input or an unreadable file.
     *
     * @param arguments the path to {@code Live2D_Cubism.jar}
     */
    public static void main(final String[] arguments) {
        if (arguments.length != 1) {
            System.err.println("usage: ReviewedHostArtifactCli <Live2D_Cubism.jar>");
            System.exit(2);
        }
        try {
            final Optional<String> version = versionOf(Path.of(arguments[0]))
                .filter(ReviewedHostArtifacts::admitsFullRuntime);
            if (version.isEmpty()) {
                System.exit(1);
            }
            System.out.println(version.orElseThrow());
        } catch (Exception failure) {
            System.err.println(failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    private ReviewedHostArtifactCli() {
    }
}
