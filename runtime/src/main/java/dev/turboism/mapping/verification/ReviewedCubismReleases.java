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
            case ReviewedHostArtifacts.CUBISM_5_0_00_VERSION -> build == 500000005;
            case ReviewedHostArtifacts.CUBISM_5_0_01_VERSION -> build == 500010000;
            case ReviewedHostArtifacts.CUBISM_5_0_02_VERSION -> build == 500020000;
            case ReviewedHostArtifacts.CUBISM_5_0_03_VERSION -> build == 500030001;
            case ReviewedHostArtifacts.CUBISM_5_0_04_VERSION -> build == 500040000;
            case ReviewedHostArtifacts.CUBISM_5_0_05_VERSION -> build == 500050001;
            case ReviewedHostArtifacts.CUBISM_5_0_06_VERSION -> build == 500060001;
            case ReviewedHostArtifacts.CUBISM_5_1_00_VERSION -> build == 501000004;
            case ReviewedHostArtifacts.CUBISM_5_1_01_VERSION -> build == 501010000;
            case ReviewedHostArtifacts.CUBISM_5_1_02_VERSION -> build == 501020001;
            case ReviewedHostArtifacts.CUBISM_5_1_03_VERSION -> build == 501030000;
            case ReviewedHostArtifacts.CUBISM_5_2_00_VERSION -> build == 502000003;
            case ReviewedHostArtifacts.CUBISM_5_2_01_VERSION -> build == 502010000;
            case ReviewedHostArtifacts.CUBISM_5_2_02_VERSION -> build == 502020000;
            case ReviewedHostArtifacts.CUBISM_5_2_03_VERSION -> build == 502030002;
            case ReviewedHostArtifacts.CUBISM_5_3_00_VERSION -> build == 503000005;
            case ReviewedHostArtifacts.CUBISM_5_3_01_VERSION -> build == 503010000;
            case ReviewedHostArtifacts.CUBISM_5_3_02_VERSION -> build == 503020001;
            case ReviewedHostArtifacts.CUBISM_5_3_03_VERSION -> build == 503030001;
            case ReviewedHostArtifacts.CUBISM_5_3_04_VERSION -> build == 503040001;
            default -> false;
        };
    }
}
