package dev.turboism.mapping.verification;

import java.nio.file.Path;
import java.util.Set;

/** Exact reviewed selector admission for Cubism red-box overlay buttons. */
public final class BoundingBoxOverlayButtonVerificationManifest {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.bounding-box-overlay-button";
    public static final String CAPABILITY_ID = "cubism.editor-ui.bounding-box-overlay-button";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    private static final String RECORD_SHA_52 = "cbe781be54f7c1d67f97c5143a2375932cbe97ec77209ce4320232290af1a81a";
    private static final String RECORD_SHA_5302 = "ceeaa56bbb690a93170a37427a9fd12a0252b9f34079863048eb7fd89e6c64cc";
    private static final String RECORD_SHA_5303 = "d7653ef266f1273822458e7a5bb8736895c88c2b0248d33a5b8e63061c655797";
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
