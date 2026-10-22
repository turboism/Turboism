package dev.turboism.mapping.verification;

import dev.turboism.internal.ReviewedVersions;
import java.util.List;
import java.util.Set;

/**
 * Runtime-owned allowlist for the reviewed startup-suppression hook evidence, plus the
 * task-scoped runtime admission for exact-host 5.3.03 startup-suppression validation.
 *
 * <p>All admitted Cubism versions are declared symmetrically as {@link ReviewedSliceRecord}
 * data; every other artifact fails closed. The premain installer binds these records through
 * reviewed class pins in {@code StartupSuppressionProfile} rather than through a member
 * resolver, so this manifest pins the record surface for admission preflight instead of
 * serving selector lookups.</p>
 */
public final class StartupSuppressionVerificationManifest {

    /** Adapter slice the startup-suppression records prove. */
    public static final String ADAPTER_SLICE_ID = "adapter.cubism.startup-suppression";

    /** The single capability the reviewed records admit. */
    public static final String CAPABILITY_ID = "cubism.startup.suppression";

    /** Every capability the family admits. */
    public static final Set<String> CAPABILITY_IDS = Set.of(
        CAPABILITY_ID
    );

    /**
     * Selector aliases verified on generations whose startup entry invokes
     * {@code CEAppCtrl.showInformation} — every reviewed generation except 5.0.x.
     */
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.startup-suppression.app.class",
        "cubism.startup-suppression.app.splash-factory",
        "cubism.startup-suppression.app.startup",
        "cubism.startup-suppression.app-controller.class",
        "cubism.startup-suppression.app-controller.show-information",
        "cubism.startup-suppression.app-controller.update-check"
    );

    /**
     * Selector aliases verified on the 5.0.x generations, whose startup entry never
     * invokes an information dialog: {@code CEAppCtrl.showInformation} does not exist
     * there, so the reviewed record carries the reduced surface and the suppression
     * profile declares no information selector.
     */
    public static final Set<String> LEGACY_REQUIRED_ALIASES = Set.of(
        "cubism.startup-suppression.app.class",
        "cubism.startup-suppression.app.splash-factory",
        "cubism.startup-suppression.app.startup",
        "cubism.startup-suppression.app-controller.class",
        "cubism.startup-suppression.app-controller.update-check"
    );

    private static final String VALIDATION_PROPERTY =
        "turboism.validation.startupSuppression";
    private static final String VALIDATION_TOKEN =
        "EXACT_5303_STARTUP_SUPPRESSION_CANDIDATE";
    private static final String VALIDATION_HOST_VERSION_PROPERTY =
        "turboism.validation.hostVersion";
    private static final String VALIDATION_HOST_VERSION = "5303";
    private static final String VALIDATION_MODE_PROPERTY =
        "turboism.validation.startupSuppression.mode";
    private static final String VALIDATION_MODE = "splash-update-information";
    private static final String VALIDATION_RUN_ID_PROPERTY =
        "turboism.validation.runId";

    /** Cubism version reported for the reviewed 5.0.00 artifact. */
    public static final String CUBISM_VERSION_5_0_00 = "${record:cubism-5.0.00-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.01 artifact. */
    public static final String CUBISM_VERSION_5_0_01 = "${record:cubism-5.0.01-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.02 artifact. */
    public static final String CUBISM_VERSION_5_0_02 = "${record:cubism-5.0.02-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.03 artifact. */
    public static final String CUBISM_VERSION_5_0_03 = "${record:cubism-5.0.03-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.04 artifact. */
    public static final String CUBISM_VERSION_5_0_04 = "${record:cubism-5.0.04-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.05 artifact. */
    public static final String CUBISM_VERSION_5_0_05 = "${record:cubism-5.0.05-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.06 artifact. */
    public static final String CUBISM_VERSION_5_0_06 = "${record:cubism-5.0.06-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.00 artifact. */
    public static final String CUBISM_VERSION_5_1_00 = "${record:cubism-5.1.00-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.01 artifact. */
    public static final String CUBISM_VERSION_5_1_01 = "${record:cubism-5.1.01-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.02 artifact. */
    public static final String CUBISM_VERSION_5_1_02 = "${record:cubism-5.1.02-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.03 artifact. */
    public static final String CUBISM_VERSION_5_1_03 = "${record:cubism-5.1.03-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.00 artifact. */
    public static final String CUBISM_VERSION_5_2_00 = "${record:cubism-5.2.00-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.01 artifact. */
    public static final String CUBISM_VERSION_5_2_01 = "${record:cubism-5.2.01-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.02 artifact. */
    public static final String CUBISM_VERSION_5_2_02 = "${record:cubism-5.2.02-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.03 artifact. */
    public static final String CUBISM_VERSION_5_2_03 = "${record:cubism-5.2.03-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.00 artifact. */
    public static final String CUBISM_VERSION_5_3_00 = "${record:cubism-5.3.00-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.01 artifact. */
    public static final String CUBISM_VERSION_5_3_01 = "${record:cubism-5.3.01-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "${record:cubism-5.3.02-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.03 artifact. */
    public static final String CUBISM_VERSION_5_3_03 = "${record:cubism-5.3.03-startup-suppression.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.04 artifact. */
    public static final String CUBISM_VERSION_5_3_04 = "${record:cubism-5.3.04-startup-suppression.json:cubismVersion}";

    /** Reviewed record admitted for exact Cubism 5.0.00. */
    public static final ReviewedSliceRecord RECORD_5_0_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_00,
        "${record:cubism-5.0.00-startup-suppression.json:verificationId}",
        "${record:cubism-5.0.00-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_0_00,
        "${record:cubism-5.0.00-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.01. */
    public static final ReviewedSliceRecord RECORD_5_0_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_01,
        "${record:cubism-5.0.01-startup-suppression.json:verificationId}",
        "${record:cubism-5.0.01-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_0_01,
        "${record:cubism-5.0.01-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.02. */
    public static final ReviewedSliceRecord RECORD_5_0_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_02,
        "${record:cubism-5.0.02-startup-suppression.json:verificationId}",
        "${record:cubism-5.0.02-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_0_02,
        "${record:cubism-5.0.02-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.03. */
    public static final ReviewedSliceRecord RECORD_5_0_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_03,
        "${record:cubism-5.0.03-startup-suppression.json:verificationId}",
        "${record:cubism-5.0.03-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_0_03,
        "${record:cubism-5.0.03-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.04. */
    public static final ReviewedSliceRecord RECORD_5_0_04 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_04,
        "${record:cubism-5.0.04-startup-suppression.json:verificationId}",
        "${record:cubism-5.0.04-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_0_04,
        "${record:cubism-5.0.04-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.05. */
    public static final ReviewedSliceRecord RECORD_5_0_05 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_05,
        "${record:cubism-5.0.05-startup-suppression.json:verificationId}",
        "${record:cubism-5.0.05-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_0_05,
        "${record:cubism-5.0.05-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.06. */
    public static final ReviewedSliceRecord RECORD_5_0_06 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_06,
        "${record:cubism-5.0.06-startup-suppression.json:verificationId}",
        "${record:cubism-5.0.06-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_0_06,
        "${record:cubism-5.0.06-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.00. */
    public static final ReviewedSliceRecord RECORD_5_1_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_00,
        "${record:cubism-5.1.00-startup-suppression.json:verificationId}",
        "${record:cubism-5.1.00-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_1_00,
        "${record:cubism-5.1.00-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.01. */
    public static final ReviewedSliceRecord RECORD_5_1_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_01,
        "${record:cubism-5.1.01-startup-suppression.json:verificationId}",
        "${record:cubism-5.1.01-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_1_01,
        "${record:cubism-5.1.01-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.02. */
    public static final ReviewedSliceRecord RECORD_5_1_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_02,
        "${record:cubism-5.1.02-startup-suppression.json:verificationId}",
        "${record:cubism-5.1.02-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_1_02,
        "${record:cubism-5.1.02-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.03. */
    public static final ReviewedSliceRecord RECORD_5_1_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_03,
        "${record:cubism-5.1.03-startup-suppression.json:verificationId}",
        "${record:cubism-5.1.03-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_1_03,
        "${record:cubism-5.1.03-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.00. */
    public static final ReviewedSliceRecord RECORD_5_2_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_00,
        "${record:cubism-5.2.00-startup-suppression.json:verificationId}",
        "${record:cubism-5.2.00-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_2_00,
        "${record:cubism-5.2.00-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.01. */
    public static final ReviewedSliceRecord RECORD_5_2_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_01,
        "${record:cubism-5.2.01-startup-suppression.json:verificationId}",
        "${record:cubism-5.2.01-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_2_01,
        "${record:cubism-5.2.01-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.02. */
    public static final ReviewedSliceRecord RECORD_5_2_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_02,
        "${record:cubism-5.2.02-startup-suppression.json:verificationId}",
        "${record:cubism-5.2.02-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_2_02,
        "${record:cubism-5.2.02-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "${record:cubism-5.2.03-startup-suppression.json:verificationId}",
        "${record:cubism-5.2.03-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_2_03,
        "${record:cubism-5.2.03-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.00. */
    public static final ReviewedSliceRecord RECORD_5_3_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_00,
        "${record:cubism-5.3.00-startup-suppression.json:verificationId}",
        "${record:cubism-5.3.00-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_3_00,
        "${record:cubism-5.3.00-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.01. */
    public static final ReviewedSliceRecord RECORD_5_3_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_01,
        "${record:cubism-5.3.01-startup-suppression.json:verificationId}",
        "${record:cubism-5.3.01-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_3_01,
        "${record:cubism-5.3.01-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "${record:cubism-5.3.02-startup-suppression.json:verificationId}",
        "${record:cubism-5.3.02-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_3_02,
        "${record:cubism-5.3.02-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.03. */
    public static final ReviewedSliceRecord RECORD_5_3_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "${record:cubism-5.3.03-startup-suppression.json:verificationId}",
        "${record:cubism-5.3.03-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_3_03,
        "${record:cubism-5.3.03-startup-suppression.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.04. */
    public static final ReviewedSliceRecord RECORD_5_3_04 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_04,
        "${record:cubism-5.3.04-startup-suppression.json:verificationId}",
        "${record:cubism-5.3.04-startup-suppression.json:sha256}",
        CUBISM_VERSION_5_3_04,
        "${record:cubism-5.3.04-startup-suppression.json:profileId}"
    );

    private static final List<ReviewedSliceRecord> RECORDS = List.of(
        RECORD_5_0_00,
        RECORD_5_0_01,
        RECORD_5_0_02,
        RECORD_5_0_03,
        RECORD_5_0_04,
        RECORD_5_0_05,
        RECORD_5_0_06,
        RECORD_5_1_00,
        RECORD_5_1_01,
        RECORD_5_1_02,
        RECORD_5_1_03,
        RECORD_5_2_00,
        RECORD_5_2_01,
        RECORD_5_2_02,
        RECORD_5_2_03,
        RECORD_5_3_00,
        RECORD_5_3_01,
        RECORD_5_3_02,
        RECORD_5_3_03,
        RECORD_5_3_04
    );

    /** The 5.0.x records carrying the reduced alias surface. */
    private static final List<ReviewedSliceRecord> LEGACY_RECORDS = List.of(
        RECORD_5_0_00,
        RECORD_5_0_01,
        RECORD_5_0_02,
        RECORD_5_0_03,
        RECORD_5_0_04,
        RECORD_5_0_05,
        RECORD_5_0_06
    );

    /**
     * Returns the reviewed Cubism versions admitted for startup suppression.
     *
     * @return immutable set of exact reviewed version strings
     */
    public static Set<String> reviewedCubismVersions() {
        return ReviewedVersions.since("5.0.00");
    }

    /**
     * Authorizes the complete startup-suppression slice for an exact reviewed Cubism version.
     *
     * @param resolver the resolver to test, may be null
     * @return {@code true} only when the resolver reports one reviewed version and authorises
     *     the full slice, capability and alias set
     */
    public static boolean authorizes(final VerifiedMemberResolver resolver) {
        if (resolver == null) {
            return false;
        }
        final boolean reviewedVersion = reviewedCubismVersions().stream()
            .anyMatch(resolver::isAdmittedCubismVersion);
        return reviewedVersion
            && (resolver.authorizes(ADAPTER_SLICE_ID, CAPABILITY_IDS, REQUIRED_ALIASES)
                || resolver.authorizes(ADAPTER_SLICE_ID, CAPABILITY_IDS, LEGACY_REQUIRED_ALIASES));
    }

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(
        final HostArtifactDigest artifact
    ) {
        final ReviewedSliceRecord record =
            ReviewedSliceRecord.requireReviewed(RECORDS, artifact, "startup-suppression");
        // The 5.0.x records carry the reduced alias surface their reviewed bytes prove.
        final Set<String> aliases =
            LEGACY_RECORDS.contains(record) ? LEGACY_REQUIRED_ALIASES : REQUIRED_ALIASES;
        return record.toManifest(ADAPTER_SLICE_ID, CAPABILITY_IDS, aliases);
    }

    /**
     * Returns the exact reviewed Cubism version for an admitted startup-suppression artifact.
     *
     * @param artifact the observed host artifact identity
     * @return the reviewed Cubism version string for that artifact
     * @throws IllegalArgumentException when the artifact is not reviewed for this family
     */
    public static String versionForArtifact(final HostArtifactDigest artifact) {
        return ReviewedSliceRecord.requireReviewed(RECORDS, artifact, "startup-suppression")
            .cubismVersion();
    }

    /**
     * Returns whether this JVM is the exact task-scoped 5.3.03 startup-suppression lane.
     *
     * <p>The installer still requires premain, the exact reviewed artifact digest, the exact
     * transformer profile, requested suppression policy, and an unloaded target class. This
     * identity opens only startup-suppression installation; broad runtime admission remains
     * closed.</p>
     */
    public static boolean admits5303ValidationCandidate() {
        return admits5303ValidationCandidate(
            System.getProperty(VALIDATION_PROPERTY),
            System.getProperty(VALIDATION_HOST_VERSION_PROPERTY),
            System.getProperty(VALIDATION_MODE_PROPERTY),
            System.getProperty(VALIDATION_RUN_ID_PROPERTY)
        );
    }

    static boolean admits5303ValidationCandidate(
        final String token,
        final String hostVersion,
        final String mode,
        final String runId
    ) {
        return VALIDATION_TOKEN.equals(token)
            && VALIDATION_HOST_VERSION.equals(hostVersion)
            && VALIDATION_MODE.equals(mode)
            && runId != null
            && !runId.isBlank();
    }

    private StartupSuppressionVerificationManifest() {
    }
}
