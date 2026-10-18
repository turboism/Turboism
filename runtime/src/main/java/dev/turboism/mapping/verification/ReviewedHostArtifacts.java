package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

    /** Cubism Editor version string reported for {@link #CUBISM_5_0_00}. */
    public static final String CUBISM_5_0_00_VERSION = "5.0.00";

    /** Cubism Editor version string reported for {@link #CUBISM_5_0_01}. */
    public static final String CUBISM_5_0_01_VERSION = "5.0.01";

    /** Cubism Editor version string reported for {@link #CUBISM_5_0_02}. */
    public static final String CUBISM_5_0_02_VERSION = "5.0.02";

    /** Cubism Editor version string reported for {@link #CUBISM_5_0_03}. */
    public static final String CUBISM_5_0_03_VERSION = "5.0.03";

    /** Cubism Editor version string reported for {@link #CUBISM_5_0_04}. */
    public static final String CUBISM_5_0_04_VERSION = "5.0.04";

    /** Cubism Editor version string reported for {@link #CUBISM_5_0_05}. */
    public static final String CUBISM_5_0_05_VERSION = "5.0.05";

    /** Cubism Editor version string reported for {@link #CUBISM_5_0_06}. */
    public static final String CUBISM_5_0_06_VERSION = "5.0.06";

    /** Cubism Editor version string reported for {@link #CUBISM_5_1_00}. */
    public static final String CUBISM_5_1_00_VERSION = "5.1.00";

    /** Cubism Editor version string reported for {@link #CUBISM_5_1_01}. */
    public static final String CUBISM_5_1_01_VERSION = "5.1.01";

    /** Cubism Editor version string reported for {@link #CUBISM_5_1_02}. */
    public static final String CUBISM_5_1_02_VERSION = "5.1.02";

    /** Cubism Editor version string reported for {@link #CUBISM_5_1_03}. */
    public static final String CUBISM_5_1_03_VERSION = "5.1.03";

    /** Cubism Editor version string reported for {@link #CUBISM_5_2_00}. */
    public static final String CUBISM_5_2_00_VERSION = "5.2.00";

    /** Cubism Editor version string reported for {@link #CUBISM_5_2_01}. */
    public static final String CUBISM_5_2_01_VERSION = "5.2.01";

    /** Cubism Editor version string reported for {@link #CUBISM_5_2_02}. */
    public static final String CUBISM_5_2_02_VERSION = "5.2.02";

    /** Cubism Editor version string reported for {@link #CUBISM_5_2_03}. */
    public static final String CUBISM_5_2_03_VERSION = "5.2.03";

    /** Cubism Editor version string reported for {@link #CUBISM_5_3_00}. */
    public static final String CUBISM_5_3_00_VERSION = "5.3.00";

    /** Cubism Editor version string reported for {@link #CUBISM_5_3_01}. */
    public static final String CUBISM_5_3_01_VERSION = "5.3.01";

    /** Cubism Editor version string reported for {@link #CUBISM_5_3_02}. */
    public static final String CUBISM_5_3_02_VERSION = "5.3.02";

    /** Cubism Editor version string reported for {@link #CUBISM_5_3_03}. */
    public static final String CUBISM_5_3_03_VERSION = "5.3.03";

    /** Cubism Editor version string reported for {@link #CUBISM_5_3_04}. */
    public static final String CUBISM_5_3_04_VERSION = "5.3.04";

    /** Exact reviewed Cubism Editor 5.0.00 application artifact. */
    public static final HostArtifactDigest CUBISM_5_0_00 =
            new HostArtifactDigest(36_091_669L, "42ee222a05478f48739bb1fbf7e7bc7350b21004e506e1b555d1d55c584d99cd");

    /** Exact reviewed Cubism Editor 5.0.01 application artifact. */
    public static final HostArtifactDigest CUBISM_5_0_01 =
            new HostArtifactDigest(36_099_677L, "1aa93b1d9d8da88e8c28de06b680c41a73fc70243307d856d4a2967e6e487a0b");

    /** Exact reviewed Cubism Editor 5.0.02 application artifact. */
    public static final HostArtifactDigest CUBISM_5_0_02 =
            new HostArtifactDigest(36_100_830L, "39e73c8238acba5270422a6e0f228c66a733177d009c710721381285ab0af072");

    /** Exact reviewed Cubism Editor 5.0.03 application artifact. */
    public static final HostArtifactDigest CUBISM_5_0_03 =
            new HostArtifactDigest(36_098_490L, "05bc9b872527fed3dc2641ff833b03d86e6fdafbe6faa294eb3d0afcc0f88a66");

    /** Exact reviewed Cubism Editor 5.0.04 application artifact. */
    public static final HostArtifactDigest CUBISM_5_0_04 =
            new HostArtifactDigest(36_098_501L, "7298c5731525a2bd5ac4e6d5246f1f08603651b0ec37cf5dc3e8ad1802ad8758");

    /** Exact reviewed Cubism Editor 5.0.05 application artifact. */
    public static final HostArtifactDigest CUBISM_5_0_05 =
            new HostArtifactDigest(36_644_752L, "1225893a466d486415773b0a62ec61a75badb2cb5cb2fce8ae744b0123becec4");

    /** Exact reviewed Cubism Editor 5.0.06 application artifact. */
    public static final HostArtifactDigest CUBISM_5_0_06 =
            new HostArtifactDigest(36_677_328L, "cbb9d9ecddb9be0252733ece055ecae7d709de5c56647b7226f6c44607e646f2");

    /** Exact reviewed Cubism Editor 5.1.00 application artifact. */
    public static final HostArtifactDigest CUBISM_5_1_00 =
            new HostArtifactDigest(38_664_130L, "cdda37ed4105cf5e4ecb5811f7065df35b9b9a3319bcb74f8e9189d91e8d3f05");

    /** Exact reviewed Cubism Editor 5.1.01 application artifact. */
    public static final HostArtifactDigest CUBISM_5_1_01 =
            new HostArtifactDigest(38_682_375L, "ddb1f5569bc8da49f3c9f6b6eaec3fe57e4f7abec67c9c44079233ea51056c02");

    /** Exact reviewed Cubism Editor 5.1.02 application artifact. */
    public static final HostArtifactDigest CUBISM_5_1_02 =
            new HostArtifactDigest(38_910_839L, "0567130093015c969d32b91c6a48d4603c28c8224c6a14fa969491f8974f63ca");

    /** Exact reviewed Cubism Editor 5.1.03 application artifact. */
    public static final HostArtifactDigest CUBISM_5_1_03 =
            new HostArtifactDigest(38_910_843L, "a3d916077bd7a176345233da95f1eb0a8bbdf8967fd08162776d5bf6d746009b");

    /** Exact reviewed Cubism Editor 5.2.00 application artifact. */
    public static final HostArtifactDigest CUBISM_5_2_00 =
            new HostArtifactDigest(40_625_430L, "25c41c81ddb3af884b7f0d61a02eba9b09f0412bdc025997c419de7ab7158cf1");

    /** Exact reviewed Cubism Editor 5.2.01 application artifact. */
    public static final HostArtifactDigest CUBISM_5_2_01 =
            new HostArtifactDigest(40_625_435L, "c1b70ca8c571c4f4df4ebce63b3381f6bed1b132ab0ed539046fd0609e2b00ac");

    /** Exact reviewed Cubism Editor 5.2.02 application artifact. */
    public static final HostArtifactDigest CUBISM_5_2_02 =
            new HostArtifactDigest(40_626_770L, "ba1f31a1846490c3f111a4ae99887c36bcc0c1bc1a3cf970eb5a7aeed7902aff");

    /** Exact reviewed Cubism Editor 5.2.03 application artifact. */
    public static final HostArtifactDigest CUBISM_5_2_03 =
            new HostArtifactDigest(40_805_584L, "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd");

    /** Exact reviewed Cubism Editor 5.3.00 application artifact. */
    public static final HostArtifactDigest CUBISM_5_3_00 =
            new HostArtifactDigest(41_859_315L, "c89dcaf17b6bfad7227db30bffc2f29cdf49b8e4b0cec1f12af2d85b1c13e767");

    /** Exact reviewed Cubism Editor 5.3.01 application artifact. */
    public static final HostArtifactDigest CUBISM_5_3_01 =
            new HostArtifactDigest(41_862_401L, "694bb49c66ddcf15695dbd8f66e1cbaeb0097f651e2e77c327567244f801d2f4");

    /** Exact reviewed Cubism Editor 5.3.02 application artifact. */
    public static final HostArtifactDigest CUBISM_5_3_02 =
            new HostArtifactDigest(41_922_739L, "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21");

    /** Exact reviewed Cubism Editor 5.3.03 application artifact. */
    public static final HostArtifactDigest CUBISM_5_3_03 =
            new HostArtifactDigest(42_010_633L, "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166");

    /** Exact reviewed Cubism Editor 5.3.04 application artifact. */
    public static final HostArtifactDigest CUBISM_5_3_04 =
            new HostArtifactDigest(42_203_164L, "c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d");

    /**
     * Bundled JOGL dependency examined for the opt-in 5.3.03 uniform-location hook.
     * This is not an Editor artifact and is deliberately excluded from {@link #all()}.
     */
    public static final HostArtifactDigest CUBISM_5_3_03_JOGL =
            new HostArtifactDigest(3_616_948L, "7dbedb4ba89d9744aa8f8e3710436ef349547058c78e5b3beb813b27b382b0b9");

    private static final Set<String> REVIEWED_CUBISM_VERSION_SET = Set.of(
            CUBISM_5_0_00_VERSION,
            CUBISM_5_0_01_VERSION,
            CUBISM_5_0_02_VERSION,
            CUBISM_5_0_03_VERSION,
            CUBISM_5_0_04_VERSION,
            CUBISM_5_0_05_VERSION,
            CUBISM_5_0_06_VERSION,
            CUBISM_5_1_00_VERSION,
            CUBISM_5_1_01_VERSION,
            CUBISM_5_1_02_VERSION,
            CUBISM_5_1_03_VERSION,
            CUBISM_5_2_00_VERSION,
            CUBISM_5_2_01_VERSION,
            CUBISM_5_2_02_VERSION,
            CUBISM_5_2_03_VERSION,
            CUBISM_5_3_00_VERSION,
            CUBISM_5_3_01_VERSION,
            CUBISM_5_3_02_VERSION,
            CUBISM_5_3_03_VERSION,
            CUBISM_5_3_04_VERSION);

    /**
     * Returns every reviewed artifact, oldest supported Cubism version first.
     *
     * @return an immutable list used by callers that must cover the whole supported matrix
     */
    public static List<HostArtifactDigest> all() {
        return List.of(
                CUBISM_5_0_00,
                CUBISM_5_0_01,
                CUBISM_5_0_02,
                CUBISM_5_0_03,
                CUBISM_5_0_04,
                CUBISM_5_0_05,
                CUBISM_5_0_06,
                CUBISM_5_1_00,
                CUBISM_5_1_01,
                CUBISM_5_1_02,
                CUBISM_5_1_03,
                CUBISM_5_2_00,
                CUBISM_5_2_01,
                CUBISM_5_2_02,
                CUBISM_5_2_03,
                CUBISM_5_3_00,
                CUBISM_5_3_01,
                CUBISM_5_3_02,
                CUBISM_5_3_03,
                CUBISM_5_3_04);
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
        if (CUBISM_5_0_00.equals(artifact)) {
            return Optional.of(CUBISM_5_0_00_VERSION);
        }
        if (CUBISM_5_0_01.equals(artifact)) {
            return Optional.of(CUBISM_5_0_01_VERSION);
        }
        if (CUBISM_5_0_02.equals(artifact)) {
            return Optional.of(CUBISM_5_0_02_VERSION);
        }
        if (CUBISM_5_0_03.equals(artifact)) {
            return Optional.of(CUBISM_5_0_03_VERSION);
        }
        if (CUBISM_5_0_04.equals(artifact)) {
            return Optional.of(CUBISM_5_0_04_VERSION);
        }
        if (CUBISM_5_0_05.equals(artifact)) {
            return Optional.of(CUBISM_5_0_05_VERSION);
        }
        if (CUBISM_5_0_06.equals(artifact)) {
            return Optional.of(CUBISM_5_0_06_VERSION);
        }
        if (CUBISM_5_1_00.equals(artifact)) {
            return Optional.of(CUBISM_5_1_00_VERSION);
        }
        if (CUBISM_5_1_01.equals(artifact)) {
            return Optional.of(CUBISM_5_1_01_VERSION);
        }
        if (CUBISM_5_1_02.equals(artifact)) {
            return Optional.of(CUBISM_5_1_02_VERSION);
        }
        if (CUBISM_5_1_03.equals(artifact)) {
            return Optional.of(CUBISM_5_1_03_VERSION);
        }
        if (CUBISM_5_2_00.equals(artifact)) {
            return Optional.of(CUBISM_5_2_00_VERSION);
        }
        if (CUBISM_5_2_01.equals(artifact)) {
            return Optional.of(CUBISM_5_2_01_VERSION);
        }
        if (CUBISM_5_2_02.equals(artifact)) {
            return Optional.of(CUBISM_5_2_02_VERSION);
        }
        if (CUBISM_5_2_03.equals(artifact)) {
            return Optional.of(CUBISM_5_2_03_VERSION);
        }
        if (CUBISM_5_3_00.equals(artifact)) {
            return Optional.of(CUBISM_5_3_00_VERSION);
        }
        if (CUBISM_5_3_01.equals(artifact)) {
            return Optional.of(CUBISM_5_3_01_VERSION);
        }
        if (CUBISM_5_3_02.equals(artifact)) {
            return Optional.of(CUBISM_5_3_02_VERSION);
        }
        if (CUBISM_5_3_03.equals(artifact)) {
            return Optional.of(CUBISM_5_3_03_VERSION);
        }
        if (CUBISM_5_3_04.equals(artifact)) {
            return Optional.of(CUBISM_5_3_04_VERSION);
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
     * Returns every reviewed Cubism Editor version label.
     *
     * @return the immutable set of version strings covered by reviewed artifacts
     */
    public static Set<String> reviewedCubismVersions() {
        return REVIEWED_CUBISM_VERSION_SET;
    }

    /**
     * Returns whether an exact reviewed Editor version is admitted to the established full runtime.
     *
     * <p>Artifact review and full-runtime admission are separate decisions: a newly reviewed
     * identity remains closed until its runtime, UI, and SDK surfaces are enabled explicitly.</p>
     *
     * @param cubismVersion exact reviewed Editor version string
     * @return {@code true} only for the explicitly admitted reviewed profiles
     * @throws NullPointerException when {@code cubismVersion} is null
     */
    public static boolean admitsFullRuntime(final String cubismVersion) {
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        return REVIEWED_CUBISM_VERSION_SET.contains(cubismVersion);
    }

    private static final Set<String> REVIEWED_SHA256_SET = java.util.Collections.unmodifiableSet(
            all().stream().map(HostArtifactDigest::sha256).collect(java.util.stream.Collectors.toSet()));

    /**
     * Returns the immutable set of SHA-256 digests for all reviewed Cubism Editor host artifacts.
     *
     * @return the set of reviewed SHA-256 strings
     */
    public static Set<String> reviewedSha256s() {
        return REVIEWED_SHA256_SET;
    }

    private ReviewedHostArtifacts() {}
}
