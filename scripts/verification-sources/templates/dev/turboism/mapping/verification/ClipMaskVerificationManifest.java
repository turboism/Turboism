package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Set;

/**
 * Runtime trust root for the reviewed Cubism clip-mask selector evidence.
 *
 * <p>Each supported Cubism version is declared as its own {@link ReviewedSliceRecord}; every other
 * artifact fails closed.</p>
 *
 * <p>The 5.2.03 and 5.3.03 records authorise the same read-only selector contract as 5.3.02 only
 * after exact-artifact verification. For 5.3.03, all 16 selector tuples and all eight invoked
 * method bodies were independently shown unchanged from 5.3.02. Nothing here admits writes, UI,
 * hooks, persistence, or broad 5.3.x compatibility, and code-level routing is not an exact-host
 * readiness claim.</p>
 */
public final class ClipMaskVerificationManifest {

    /** Cubism version reported for the reviewed 5.0.00 artifact. */
    public static final String CUBISM_VERSION_5_0_00 = "${record:cubism-5.0.00-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.01 artifact. */
    public static final String CUBISM_VERSION_5_0_01 = "${record:cubism-5.0.01-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.02 artifact. */
    public static final String CUBISM_VERSION_5_0_02 = "${record:cubism-5.0.02-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.03 artifact. */
    public static final String CUBISM_VERSION_5_0_03 = "${record:cubism-5.0.03-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.04 artifact. */
    public static final String CUBISM_VERSION_5_0_04 = "${record:cubism-5.0.04-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.05 artifact. */
    public static final String CUBISM_VERSION_5_0_05 = "${record:cubism-5.0.05-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.06 artifact. */
    public static final String CUBISM_VERSION_5_0_06 = "${record:cubism-5.0.06-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.00 artifact. */
    public static final String CUBISM_VERSION_5_1_00 = "${record:cubism-5.1.00-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.01 artifact. */
    public static final String CUBISM_VERSION_5_1_01 = "${record:cubism-5.1.01-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.02 artifact. */
    public static final String CUBISM_VERSION_5_1_02 = "${record:cubism-5.1.02-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.03 artifact. */
    public static final String CUBISM_VERSION_5_1_03 = "${record:cubism-5.1.03-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.00 artifact. */
    public static final String CUBISM_VERSION_5_2_00 = "${record:cubism-5.2.00-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.01 artifact. */
    public static final String CUBISM_VERSION_5_2_01 = "${record:cubism-5.2.01-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.02 artifact. */
    public static final String CUBISM_VERSION_5_2_02 = "${record:cubism-5.2.02-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.03 artifact. */
    public static final String CUBISM_VERSION_5_2_03 = "${record:cubism-5.2.03-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.00 artifact. */
    public static final String CUBISM_VERSION_5_3_00 = "${record:cubism-5.3.00-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.01 artifact. */
    public static final String CUBISM_VERSION_5_3_01 = "${record:cubism-5.3.01-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "${record:cubism-5.3.02-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.03 artifact. */
    public static final String CUBISM_VERSION_5_3_03 = "${record:cubism-5.3.03-clipmask.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.04 artifact. */
    public static final String CUBISM_VERSION_5_3_04 = "${record:cubism-5.3.04-clipmask.json:cubismVersion}";

    /** Reviewed record admitted for exact Cubism 5.0.00. */
    public static final ReviewedSliceRecord RECORD_5_0_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_00,
        "${record:cubism-5.0.00-clipmask.json:verificationId}",
        "${record:cubism-5.0.00-clipmask.json:sha256}",
        CUBISM_VERSION_5_0_00,
        "${record:cubism-5.0.00-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.01. */
    public static final ReviewedSliceRecord RECORD_5_0_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_01,
        "${record:cubism-5.0.01-clipmask.json:verificationId}",
        "${record:cubism-5.0.01-clipmask.json:sha256}",
        CUBISM_VERSION_5_0_01,
        "${record:cubism-5.0.01-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.02. */
    public static final ReviewedSliceRecord RECORD_5_0_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_02,
        "${record:cubism-5.0.02-clipmask.json:verificationId}",
        "${record:cubism-5.0.02-clipmask.json:sha256}",
        CUBISM_VERSION_5_0_02,
        "${record:cubism-5.0.02-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.03. */
    public static final ReviewedSliceRecord RECORD_5_0_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_03,
        "${record:cubism-5.0.03-clipmask.json:verificationId}",
        "${record:cubism-5.0.03-clipmask.json:sha256}",
        CUBISM_VERSION_5_0_03,
        "${record:cubism-5.0.03-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.04. */
    public static final ReviewedSliceRecord RECORD_5_0_04 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_04,
        "${record:cubism-5.0.04-clipmask.json:verificationId}",
        "${record:cubism-5.0.04-clipmask.json:sha256}",
        CUBISM_VERSION_5_0_04,
        "${record:cubism-5.0.04-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.05. */
    public static final ReviewedSliceRecord RECORD_5_0_05 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_05,
        "${record:cubism-5.0.05-clipmask.json:verificationId}",
        "${record:cubism-5.0.05-clipmask.json:sha256}",
        CUBISM_VERSION_5_0_05,
        "${record:cubism-5.0.05-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.06. */
    public static final ReviewedSliceRecord RECORD_5_0_06 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_06,
        "${record:cubism-5.0.06-clipmask.json:verificationId}",
        "${record:cubism-5.0.06-clipmask.json:sha256}",
        CUBISM_VERSION_5_0_06,
        "${record:cubism-5.0.06-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.00. */
    public static final ReviewedSliceRecord RECORD_5_1_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_00,
        "${record:cubism-5.1.00-clipmask.json:verificationId}",
        "${record:cubism-5.1.00-clipmask.json:sha256}",
        CUBISM_VERSION_5_1_00,
        "${record:cubism-5.1.00-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.01. */
    public static final ReviewedSliceRecord RECORD_5_1_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_01,
        "${record:cubism-5.1.01-clipmask.json:verificationId}",
        "${record:cubism-5.1.01-clipmask.json:sha256}",
        CUBISM_VERSION_5_1_01,
        "${record:cubism-5.1.01-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.02. */
    public static final ReviewedSliceRecord RECORD_5_1_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_02,
        "${record:cubism-5.1.02-clipmask.json:verificationId}",
        "${record:cubism-5.1.02-clipmask.json:sha256}",
        CUBISM_VERSION_5_1_02,
        "${record:cubism-5.1.02-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.03. */
    public static final ReviewedSliceRecord RECORD_5_1_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_03,
        "${record:cubism-5.1.03-clipmask.json:verificationId}",
        "${record:cubism-5.1.03-clipmask.json:sha256}",
        CUBISM_VERSION_5_1_03,
        "${record:cubism-5.1.03-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.00. */
    public static final ReviewedSliceRecord RECORD_5_2_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_00,
        "${record:cubism-5.2.00-clipmask.json:verificationId}",
        "${record:cubism-5.2.00-clipmask.json:sha256}",
        CUBISM_VERSION_5_2_00,
        "${record:cubism-5.2.00-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.01. */
    public static final ReviewedSliceRecord RECORD_5_2_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_01,
        "${record:cubism-5.2.01-clipmask.json:verificationId}",
        "${record:cubism-5.2.01-clipmask.json:sha256}",
        CUBISM_VERSION_5_2_01,
        "${record:cubism-5.2.01-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.02. */
    public static final ReviewedSliceRecord RECORD_5_2_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_02,
        "${record:cubism-5.2.02-clipmask.json:verificationId}",
        "${record:cubism-5.2.02-clipmask.json:sha256}",
        CUBISM_VERSION_5_2_02,
        "${record:cubism-5.2.02-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "${record:cubism-5.2.03-clipmask.json:verificationId}",
        "${record:cubism-5.2.03-clipmask.json:sha256}",
        CUBISM_VERSION_5_2_03,
        "${record:cubism-5.2.03-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.00. */
    public static final ReviewedSliceRecord RECORD_5_3_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_00,
        "${record:cubism-5.3.00-clipmask.json:verificationId}",
        "${record:cubism-5.3.00-clipmask.json:sha256}",
        CUBISM_VERSION_5_3_00,
        "${record:cubism-5.3.00-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.01. */
    public static final ReviewedSliceRecord RECORD_5_3_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_01,
        "${record:cubism-5.3.01-clipmask.json:verificationId}",
        "${record:cubism-5.3.01-clipmask.json:sha256}",
        CUBISM_VERSION_5_3_01,
        "${record:cubism-5.3.01-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "${record:cubism-5.3.02-clipmask.json:verificationId}",
        "${record:cubism-5.3.02-clipmask.json:sha256}",
        CUBISM_VERSION_5_3_02,
        "${record:cubism-5.3.02-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.03. */
    public static final ReviewedSliceRecord RECORD_5_3_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "${record:cubism-5.3.03-clipmask.json:verificationId}",
        "${record:cubism-5.3.03-clipmask.json:sha256}",
        CUBISM_VERSION_5_3_03,
        "${record:cubism-5.3.03-clipmask.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.04. */
    public static final ReviewedSliceRecord RECORD_5_3_04 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_04,
        "${record:cubism-5.3.04-clipmask.json:verificationId}",
        "${record:cubism-5.3.04-clipmask.json:sha256}",
        CUBISM_VERSION_5_3_04,
        "${record:cubism-5.3.04-clipmask.json:profileId}"
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





    /** Adapter slice identity for the read-only clip-mask family. */
    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-clipmask.json:adapterSliceId}";

    /** Capabilities the clip-mask slice exposes. */
    public static final Set<String> CAPABILITY_IDS = Set.of("cubism.clipmask.read");

    /** Selector aliases both reviewed versions must authorise in full. */
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.clipmask.app-controller.class",
        "cubism.clipmask.app-controller.instance",
        "cubism.clipmask.app-controller.current-document",
        "cubism.clipmask.document.class",
        "cubism.clipmask.modeling-document.class",
        "cubism.clipmask.modeling-document.model-source",
        "cubism.clipmask.model-source.class",
        "cubism.clipmask.model-source.all-art-meshes",
        "cubism.clipmask.art-mesh-source.class",
        "cubism.clipmask.drawable-source.class",
        "cubism.clipmask.drawable-source.guid",
        "cubism.clipmask.drawable-source.clip-guid-list",
        "cubism.clipmask.drawable-source.invert-clipping-mask",
        "cubism.clipmask.drawable-guid.class",
        "cubism.clipmask.guid.class",
        "cubism.clipmask.guid.value"
    );

    /** Reviewed exact Cubism versions this clip-mask trust root can serve. */
    public static Set<String> reviewedCubismVersions() {
        return Set.of(
            CUBISM_VERSION_5_0_00,
            CUBISM_VERSION_5_0_01,
            CUBISM_VERSION_5_0_02,
            CUBISM_VERSION_5_0_03,
            CUBISM_VERSION_5_0_04,
            CUBISM_VERSION_5_0_05,
            CUBISM_VERSION_5_0_06,
            CUBISM_VERSION_5_1_00,
            CUBISM_VERSION_5_1_01,
            CUBISM_VERSION_5_1_02,
            CUBISM_VERSION_5_1_03,
            CUBISM_VERSION_5_2_00,
            CUBISM_VERSION_5_2_01,
            CUBISM_VERSION_5_2_02,
            CUBISM_VERSION_5_2_03,
            CUBISM_VERSION_5_3_00,
            CUBISM_VERSION_5_3_01,
            CUBISM_VERSION_5_3_02,
            CUBISM_VERSION_5_3_03,
            CUBISM_VERSION_5_3_04
        );
    }

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        return ReviewedSliceRecord.requireReviewed(RECORDS, artifact, "clip-mask")
            .toManifest(ADAPTER_SLICE_ID, CAPABILITY_IDS, REQUIRED_ALIASES);
    }

    private ClipMaskVerificationManifest() {
    }
}
