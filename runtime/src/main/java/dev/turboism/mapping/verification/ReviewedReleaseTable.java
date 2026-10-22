package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable release facts generated from reviewed-releases.json; do not edit. */
public final class ReviewedReleaseTable {

    /** Exact observed build, Core tuple and explicit full-runtime decision for one release. */
    public record Release(
            String version, int build, int coreMajor, int coreMinor, int corePatch, boolean fullRuntime) {}

    private static final List<Release> RELEASES = List.of(
            new Release(ReviewedHostArtifacts.CUBISM_5_0_00_VERSION, 500000005, 5, 0, 0, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_0_01_VERSION, 500010000, 5, 0, 0, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_0_02_VERSION, 500020000, 5, 0, 0, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_0_03_VERSION, 500030001, 5, 0, 0, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_0_04_VERSION, 500040000, 5, 0, 0, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_0_05_VERSION, 500050001, 5, 0, 0, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_0_06_VERSION, 500060001, 5, 0, 0, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_1_00_VERSION, 501000004, 5, 0, 0, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_1_01_VERSION, 501010000, 5, 0, 0, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_1_02_VERSION, 501020001, 5, 0, 0, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_1_03_VERSION, 501030000, 5, 0, 0, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_2_00_VERSION, 502000003, 5, 0, 256, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_2_01_VERSION, 502010000, 5, 0, 256, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_2_02_VERSION, 502020000, 5, 0, 256, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_2_03_VERSION, 502030002, 5, 0, 256, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_3_00_VERSION, 503000005, 6, 0, 257, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_3_01_VERSION, 503010000, 6, 0, 257, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_3_02_VERSION, 503020001, 6, 0, 257, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_3_03_VERSION, 503030001, 6, 0, 257, true),
            new Release(ReviewedHostArtifacts.CUBISM_5_3_04_VERSION, 503040001, 6, 0, 513, true));

    /** @return all reviewed releases in table order */
    public static List<Release> all() {
        return RELEASES;
    }

    /** @return all exact reviewed version labels, oldest first */
    public static List<String> versions() {
        return RELEASES.stream().map(Release::version).toList();
    }

    /** @return the exact reviewed release, or empty for an unknown version */
    public static Optional<Release> find(final String version) {
        Objects.requireNonNull(version, "version");
        return RELEASES.stream()
                .filter(release -> release.version().equals(version))
                .findFirst();
    }

    private ReviewedReleaseTable() {}
}
