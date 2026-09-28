package dev.turboism.mapping.verification;

import java.util.Objects;

/** Reviewed Editor declarations, independent of archive packaging and mapping provenance. */
public final class ReviewedCubismReleases {

    private ReviewedCubismReleases() {}

    /**
     * Tests the declared version and build against the releases reviewed together.
     * A familiar version with a different build still requires compatibility probing.
     * Build numbers are observed declarations, never calculated from a version.
     *
     * @param version the host's declared version
     * @param build the host's declared build
     * @return whether this exact release declaration was reviewed
     */
    public static boolean isReviewed(final String version, final int build) {
        Objects.requireNonNull(version, "version");
        return switch (version) {
            case ReviewedHostArtifacts.CUBISM_5_2_03_VERSION -> build == 502030002;
            case ReviewedHostArtifacts.CUBISM_5_3_02_VERSION -> build == 503020001;
            case ReviewedHostArtifacts.CUBISM_5_3_03_VERSION -> build == 503030001;
            default -> false;
        };
    }
}
