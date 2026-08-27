package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The single production declaration of the exact Cubism Editor artifacts Turboism admits.
 *
 * <p>Every trust root, host profile and hook installer resolves its reviewed artifact identity
 * from this type. Repeating a size or SHA-256 literal anywhere else is rejected by
 * {@code scripts/test/check_code_quality.py}, because a second copy can drift from the reviewed
 * record and silently widen admission. The literals themselves are pinned by
 * {@code ReviewedHostArtifactsTest}, which is the one place allowed to restate them.</p>
 *
 * <p>Admission stays exact and fails closed: an artifact is reviewed only when both its byte size
 * and its SHA-256 match one of the constants below. Nothing here widens, normalises or infers a
 * version from a near match, and adding a Cubism version is a reviewed change to this type rather
 * than a local fallback in a caller.</p>
 */
public final class ReviewedHostArtifacts {

    /** Cubism Editor version string reported for {@link #CUBISM_5_2_03}. */
    public static final String CUBISM_5_2_03_VERSION = "5.2.03";

    /** Cubism Editor version string reported for {@link #CUBISM_5_3_02}. */
    public static final String CUBISM_5_3_02_VERSION = "5.3.02";

    /** Cubism Editor version string reported for {@link #CUBISM_5_3_03}. */
    public static final String CUBISM_5_3_03_VERSION = "5.3.03";

    /** Exact reviewed Cubism Editor 5.2.03 application artifact. */
    public static final HostArtifactDigest CUBISM_5_2_03 = new HostArtifactDigest(
        40_805_584L,
        "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"
    );

    /** Exact reviewed Cubism Editor 5.3.02 application artifact. */
    public static final HostArtifactDigest CUBISM_5_3_02 = new HostArtifactDigest(
        41_922_739L,
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
    );

    /** Exact reviewed Cubism Editor 5.3.03 application artifact. */
    public static final HostArtifactDigest CUBISM_5_3_03 = new HostArtifactDigest(
        42_010_633L,
        "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166"
    );

    /**
     * Returns every reviewed artifact, oldest supported Cubism version first.
     *
     * @return an immutable list used by callers that must cover the whole supported matrix
     */
    public static List<HostArtifactDigest> all() {
        return List.of(CUBISM_5_2_03, CUBISM_5_3_02, CUBISM_5_3_03);
    }

    /**
     * Returns the Cubism version string for a reviewed artifact.
     *
     * @param artifact the observed host artifact identity
     * @return the reviewed version, or empty when the artifact is not reviewed
     * @throws NullPointerException when {@code artifact} is null
     */
    public static Optional<String> cubismVersionOf(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (CUBISM_5_2_03.equals(artifact)) {
            return Optional.of(CUBISM_5_2_03_VERSION);
        }
        if (CUBISM_5_3_02.equals(artifact)) {
            return Optional.of(CUBISM_5_3_02_VERSION);
        }
        if (CUBISM_5_3_03.equals(artifact)) {
            return Optional.of(CUBISM_5_3_03_VERSION);
        }
        return Optional.empty();
    }

    /**
     * Returns whether an observed artifact is one of the reviewed Cubism artifacts.
     *
     * @param artifact the observed host artifact identity
     * @return {@code true} only on an exact size and SHA-256 match
     * @throws NullPointerException when {@code artifact} is null
     */
    public static boolean isReviewed(final HostArtifactDigest artifact) {
        return cubismVersionOf(artifact).isPresent();
    }

    /**
     * Returns whether an exact reviewed Editor version is admitted to the established full runtime.
     *
     * <p>Artifact review and full-runtime admission are separate decisions: a newly reviewed
     * identity remains closed until its runtime, UI, and SDK surfaces are enabled explicitly.</p>
     *
     * @param cubismVersion exact reviewed Editor version string
     * @return {@code true} only for the explicitly admitted 5.2.03, 5.3.02, and 5.3.03 profiles
     * @throws NullPointerException when {@code cubismVersion} is null
     */
    public static boolean admitsFullRuntime(final String cubismVersion) {
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        return CUBISM_5_2_03_VERSION.equals(cubismVersion)
            || CUBISM_5_3_02_VERSION.equals(cubismVersion)
            || CUBISM_5_3_03_VERSION.equals(cubismVersion);
    }

    private ReviewedHostArtifacts() {
    }
}
