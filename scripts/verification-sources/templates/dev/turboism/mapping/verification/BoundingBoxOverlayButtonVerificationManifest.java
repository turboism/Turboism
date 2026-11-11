package dev.turboism.mapping.verification;

import java.nio.file.Path;
import java.util.Set;

/** Exact reviewed selector admission for Cubism red-box overlay buttons. */
public final class BoundingBoxOverlayButtonVerificationManifest {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-ui-bounding-box-overlay.json:adapterSliceId}";
    public static final String CAPABILITY_ID = "cubism.editor-ui.bounding-box-overlay-button";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-bounding-box-overlay.bounding-box.update",
        "cubism.ui-bounding-box-overlay.bounding-box.setup-button",
        "cubism.ui-bounding-box-overlay.button.create",
        "cubism.ui-bounding-box-overlay.vector.times",
        "cubism.ui-bounding-box-overlay.vector.plus",
        "cubism.ui-bounding-box-overlay.button.set-enabled",
        "cubism.ui-bounding-box-overlay.scene.component-objects",
        "cubism.ui-bounding-box-overlay.entity.children",
        "cubism.ui-bounding-box-overlay.scene.remove-volatile",
        "cubism.ui-bounding-box-overlay.entities.remove",
        "cubism.ui-bounding-box-overlay.writable-image.create",
        "cubism.ui-bounding-box-overlay.icon-set.create"
    );

    private BoundingBoxOverlayButtonVerificationManifest() {
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
        // <version-matrix:bb-admission>
        if (ReviewedHostArtifacts.CUBISM_5_0_00.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_0_00_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_01.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_0_01_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_02.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_0_02_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_03.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_0_03_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_04.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_0_04_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_05.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_0_05_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_06.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_0_06_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_00.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_1_00_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_01.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_1_01_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_02.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_1_02_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_03.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_1_03_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_00.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_2_00_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_01.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_2_01_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_02.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_2_02_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_00.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_3_00_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_01.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_3_01_VERSION, artifact.size(), artifact.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_04.equals(artifact)) {
            return new AdmissionEvidence(
                ReviewedHostArtifacts.CUBISM_5_3_04_VERSION, artifact.size(), artifact.sha256()
            );
        }
        // </version-matrix:bb-admission>
        throw new IllegalArgumentException("Unsupported Cubism artifact for overlay buttons");
    }

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        final AdmissionEvidence admission = admissionForArtifact(artifact);
        final String cubismVersion = admission.cubismVersion();
        final String verificationId = switch (cubismVersion) {
            // <version-matrix:bb-vid>
            case "${record:cubism-5.0.00-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.0.00-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.0.01-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.0.01-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.0.02-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.0.02-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.0.03-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.0.03-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.0.04-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.0.04-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.0.05-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.0.05-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.0.06-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.0.06-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.1.00-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.1.00-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.1.01-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.1.01-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.1.02-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.1.02-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.1.03-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.1.03-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.2.00-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.2.00-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.2.01-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.2.01-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.2.02-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.2.02-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.3.00-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.3.00-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.3.01-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.3.01-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.3.04-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.3.04-ui-bounding-box-overlay.json:verificationId}";
            // </version-matrix:bb-vid>
            case "${record:cubism-5.2.03-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.2.03-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.3.02-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.3.02-ui-bounding-box-overlay.json:verificationId}";
            case "${record:cubism-5.3.03-ui-bounding-box-overlay.json:cubismVersion}" -> "${record:cubism-5.3.03-ui-bounding-box-overlay.json:verificationId}";
            default -> throw new IllegalArgumentException(
                "Unsupported Cubism version for overlay buttons: " + cubismVersion
            );
        };
        return new PinnedVerifiedResolverWorkflow.Manifest(
            verificationId,
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
