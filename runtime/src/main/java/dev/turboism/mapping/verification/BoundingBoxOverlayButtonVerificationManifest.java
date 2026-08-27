package dev.turboism.mapping.verification;

import java.nio.file.Path;
import java.util.Set;

/** Exact reviewed selector admission for Cubism red-box overlay buttons. */
public final class BoundingBoxOverlayButtonVerificationManifest {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.bounding-box-overlay-button";
    public static final String CAPABILITY_ID = "cubism.editor-ui.bounding-box-overlay-button";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    private static final String RECORD_SHA_52 = "62df5f6ac28c3c362ecd2e425184305ff82e1a3967a804b49ce5f394dc1cc365";
    private static final String RECORD_SHA_5302 = "f4b910d06d20e383a3f8f3556fbc14a26e15b9d315c9e7b141338990ed44f528";
    private static final String RECORD_SHA_5303 = "add4f142ad6d84a04b7e1b6bbfa4e82107982352fddd4e49a6a85cc2fdfb0ae5";
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
     * @throws IllegalArgumentException if the artifact is not a reviewed
     *     Cubism build
     */
    public static String recordSha256ForArtifact(final HostArtifactDigest artifact) {
        return recordSha256ForVersion(admissionForArtifact(artifact).cubismVersion());
    }

    /**
     * @param cubismVersion exact reviewed version string
     * @return the reviewed record hash for that exact version
     * @throws IllegalArgumentException when the version is not reviewed for this slice
     */
    public static String recordSha256ForVersion(final String cubismVersion) {
        return switch (cubismVersion) {
            case "5.2.03" -> RECORD_SHA_52;
            case "5.3.02" -> RECORD_SHA_5302;
            case "5.3.03" -> RECORD_SHA_5303;
            default -> throw new IllegalArgumentException(
                "Unsupported Cubism version for overlay buttons: " + cubismVersion
            );
        };
    }

    /**
     * Admits a host artifact for overlay-button work by exact identity: only
     * the reviewed Cubism builds are recognized, and the evidence returned
     * repeats the artifact's own size and hash so downstream checks cannot
     * drift from what was measured.
     *
     * @param artifact digest of the host jar
     * @return evidence naming the exact admitted version
     * @throws IllegalArgumentException if the artifact is not reviewed
     */
    public static AdmissionEvidence admissionForArtifact(final HostArtifactDigest artifact) {
        if (ReviewedHostArtifacts.CUBISM_5_2_03.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_2_03_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_3_02_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_03.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_3_03_VERSION, artifact.size(), artifact.sha256()
            );
        }
        throw new IllegalArgumentException("Unsupported Cubism artifact for overlay buttons");
    }

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        final AdmissionEvidence admission = admissionForArtifact(artifact);
        final String cubismVersion = admission.cubismVersion();
        final String verificationId = switch (cubismVersion) {
            case "5.2.03" -> "cubism-5.2.03.ui-bounding-box-overlay.static";
            case "5.3.02" -> "cubism-5.3.02.ui-bounding-box-overlay.static";
            case "5.3.03" -> "cubism-5.3.03.ui-bounding-box-overlay.static";
            default -> throw new IllegalArgumentException(
                "Unsupported Cubism version for overlay buttons: " + cubismVersion
            );
        };
        return new PinnedVerifiedResolverWorkflow.Manifest(
            verificationId,
            recordSha256ForVersion(cubismVersion),
            cubismVersion,
            "cubism-" + cubismVersion,
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
        return directory.resolve(
            "cubism-" + admission.cubismVersion() + "-ui-bounding-box-overlay.json"
        );
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
