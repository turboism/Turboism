package dev.turboism.mapping.verification;

import java.nio.file.Path;
import java.util.Set;

/** Exact reviewed selector admission for Cubism red-box overlay buttons. */
public final class BoundingBoxOverlayButtonVerificationManifest {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.bounding-box-overlay-button";
    public static final String CAPABILITY_ID = "cubism.editor-ui.bounding-box-overlay-button";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    private static final String RECORD_SHA_52 = "4eb89fba8a44cf15e4f0be6818a57ba2c0a7847cc73a7c97855d595c913aa81f";
    private static final String RECORD_SHA_53 = "606a1837c03b00c62c8711dcb5eb53fe04eb7025f78736281a2e2afacd21ce54";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-bounding-box-overlay.bounding-box.update",
        "cubism.ui-bounding-box-overlay.button.create",
        "cubism.ui-bounding-box-overlay.bounding-box.buttons",
        "cubism.ui-bounding-box-overlay.bounding-box.last-bounding-box",
        "cubism.ui-bounding-box-overlay.bounding-box.hide-button-position",
        "cubism.ui-bounding-box-overlay.action.view-context",
        "cubism.ui-bounding-box-overlay.action.scale",
        "cubism.ui-bounding-box-overlay.view.camera",
        "cubism.ui-bounding-box-overlay.view.complete-pack",
        "cubism.ui-bounding-box-overlay.complete-pack.main-view",
        "cubism.ui-bounding-box-overlay.main-view.dpi-scale",
        "cubism.ui-bounding-box-overlay.camera.document-to-component",
        "cubism.ui-bounding-box-overlay.vector.x",
        "cubism.ui-bounding-box-overlay.vector.y",
        "cubism.ui-bounding-box-overlay.vector.plus",
        "cubism.ui-bounding-box-overlay.vector.times",
        "cubism.ui-bounding-box-overlay.vector.create",
        "cubism.ui-bounding-box-overlay.rect.create",
        "cubism.ui-bounding-box-overlay.button.set-bounds",
        "cubism.ui-bounding-box-overlay.button.set-enabled",
        "cubism.ui-bounding-box-overlay.entity.enabled",
        "cubism.ui-bounding-box-overlay.scene.component-objects",
        "cubism.ui-bounding-box-overlay.entity.children",
        "cubism.ui-bounding-box-overlay.entities.add",
        "cubism.ui-bounding-box-overlay.scene.volatile",
        "cubism.ui-bounding-box-overlay.writable-image.create",
        "cubism.ui-bounding-box-overlay.icon-set.create"
    );

    private BoundingBoxOverlayButtonVerificationManifest() {
    }

    /**
     * @param artifact digest of the host jar in hand
     * @return the SHA-256 of the reviewed verification record that governs that
     *     artifact
     * @throws IllegalArgumentException if the artifact is neither reviewed
     *     Cubism build
     */
    public static String recordSha256ForArtifact(final HostArtifactDigest artifact) {
        return admissionForArtifact(artifact).cubismVersion().equals("5.3.02")
            ? RECORD_SHA_53
            : RECORD_SHA_52;
    }

    /**
     * @param cubismVersion version string to select by
     * @return the reviewed record hash for 5.3.02, otherwise the 5.2 hash; an
     *     unrecognized version falls back to 5.2 rather than failing, so prefer
     *     {@link #recordSha256ForArtifact} where the artifact is available
     */
    public static String recordSha256ForVersion(final String cubismVersion) {
        return "5.3.02".equals(cubismVersion) ? RECORD_SHA_53 : RECORD_SHA_52;
    }

    /**
     * Admits a host artifact for overlay-button work by exact identity: only
     * the two reviewed Cubism builds are recognized, and the evidence returned
     * repeats the artifact's own size and hash so downstream checks cannot
     * drift from what was measured.
     *
     * @param artifact digest of the host jar
     * @return evidence naming the admitted version; note that 5.2.03 is
     *     reported as {@code "5.2.0"}, kept byte-for-byte deliberately
     * @throws IllegalArgumentException if the artifact is not reviewed
     */
    public static AdmissionEvidence admissionForArtifact(final HostArtifactDigest artifact) {
        if (ReviewedHostArtifacts.CUBISM_5_2_03.equals(artifact)) {
            // Reported as "5.2.0" rather than "5.2.03"; kept byte-for-byte to preserve the
            // existing admission evidence value. Normalising it is a behaviour change.
            return new AdmissionEvidence("5.2.0", artifact.size(), artifact.sha256());
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_3_02_VERSION, artifact.size(), artifact.sha256()
            );
        }
        throw new IllegalArgumentException("Unsupported Cubism artifact for overlay buttons");
    }

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        final AdmissionEvidence admission = admissionForArtifact(artifact);
        return new PinnedVerifiedResolverWorkflow.Manifest(
            admission.cubismVersion().equals("5.3.02")
                ? "cubism-5.3.02.ui-bounding-box-overlay.static"
                : "cubism-5.2.ui-bounding-box-overlay.static",
            admission.cubismVersion().equals("5.3.02") ? RECORD_SHA_53 : RECORD_SHA_52,
            admission.cubismVersion(),
            admission.cubismVersion().equals("5.3.02") ? "cubism-5.3.02" : "cubism-5.2",
            admission.artifactSize(),
            admission.artifactSha256(),
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    /**
     * @param artifact digest of the host jar, which selects the profile
     * @param directory directory the reviewed records live in
     * @return the record file for that artifact's profile; the file is not
     *     checked for existence here
     * @throws IllegalArgumentException if the artifact is not reviewed
     */
    public static Path verifiedRecordForArtifact(
        final HostArtifactDigest artifact,
        final Path directory
    ) {
        final AdmissionEvidence admission = admissionForArtifact(artifact);
        final String profile = admission.cubismVersion().equals("5.3.02") ? "5.3.02" : "5.2";
        return directory.resolve("cubism-" + profile + "-ui-bounding-box-overlay.json");
    }

    /**
     * What was actually measured when the artifact was admitted.
     *
     * @param cubismVersion admitted version, as this manifest reports it
     * @param artifactSize size in bytes of the admitted jar
     * @param artifactSha256 SHA-256 of the admitted jar
     */
    public record AdmissionEvidence(
        String cubismVersion,
        long artifactSize,
        String artifactSha256
    ) {
        /**
         * @return the adapter slice this evidence admits, always
         *     {@code adapter.editor-ui.bounding-box-overlay-button}
         */
        public String adapterSliceId() {
            return ADAPTER_SLICE_ID;
        }
    }
}
