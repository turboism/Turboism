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

    /** Cubism version reported for the reviewed 5.2.03 artifact. */
    public static final String CUBISM_VERSION_5_2_03 = "5.2.03";

    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "5.3.02";

    /** Cubism version reported for the reviewed 5.3.03 artifact. */
    public static final String CUBISM_VERSION_5_3_03 = "5.3.03";

    /** Reviewed clip-mask record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "cubism-5.2.03.clipmask.static",
        "5133c670e5c6742a5a43eb60ec6c60581196c35534b37085ca18d441797d47b3",
        CUBISM_VERSION_5_2_03,
        "cubism-5.2.03"
    );

    /** Reviewed clip-mask record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "m15.cubism-5.3.02.clipmask.static",
        "8e4f5a5d9ea7896700a2b40293ba720b7a7df549216bfb6efdedb3d73c951232",
        CUBISM_VERSION_5_3_02,
        "cubism-5.3.02"
    );

    /** Reviewed clip-mask record admitted for exact Cubism 5.3.03. */
    public static final ReviewedSliceRecord RECORD_5_3_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "m15.cubism-5.3.03.clipmask.static",
        "1af607ece139f64700c7058225724503598730572606de86bd81d1a82077c194",
        CUBISM_VERSION_5_3_03,
        "cubism-5.3.03"
    );

    private static final List<ReviewedSliceRecord> RECORDS = List.of(
        RECORD_5_2_03,
        RECORD_5_3_02,
        RECORD_5_3_03
    );

    /** Adapter slice identity for the read-only clip-mask family. */
    public static final String ADAPTER_SLICE_ID = "adapter.clipmask.readonly";

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
            CUBISM_VERSION_5_2_03,
            CUBISM_VERSION_5_3_02,
            CUBISM_VERSION_5_3_03
        );
    }

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        return ReviewedSliceRecord.requireReviewed(RECORDS, artifact, "clip-mask")
            .toManifest(ADAPTER_SLICE_ID, CAPABILITY_IDS, REQUIRED_ALIASES);
    }

    private ClipMaskVerificationManifest() {
    }
}
