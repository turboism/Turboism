package dev.turboism.mapping.verification;

import java.nio.file.Path;
import java.util.Set;

/** Exact reviewed selector admission for Cubism red-box overlay buttons. */
public final class BoundingBoxOverlayButtonVerificationManifest {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.bounding-box-overlay-button";
    public static final String CAPABILITY_ID = "cubism.editor-ui.bounding-box-overlay-button";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    private static final String RECORD_SHA_52 = "3a4c23ebf6f20d991596e3959a11df53b9a363f9749e045691ca93087f897fa1";
    private static final String RECORD_SHA_53 = "dd7df8bb72f753eba385fe9b3880addca3b7b03e4deb958d2bfd3c5ad23863b9";
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

    public static String recordSha256ForArtifact(final HostArtifactDigest artifact) {
        return admissionForArtifact(artifact).cubismVersion().equals("5.3.02")
            ? RECORD_SHA_53
            : RECORD_SHA_52;
    }

    public static String recordSha256ForVersion(final String cubismVersion) {
        return "5.3.02".equals(cubismVersion) ? RECORD_SHA_53 : RECORD_SHA_52;
    }

    public static AdmissionEvidence admissionForArtifact(final HostArtifactDigest artifact) {
        if (artifact.size() == 40_805_584L
            && artifact.sha256().equals(
                "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"
            )) {
            return new AdmissionEvidence("5.2.0", artifact.size(), artifact.sha256());
        }
        if (artifact.size() == 41_922_739L
            && artifact.sha256().equals(
                "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
            )) {
            return new AdmissionEvidence("5.3.02", artifact.size(), artifact.sha256());
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

    public static Path verifiedRecordForArtifact(
        final HostArtifactDigest artifact,
        final Path directory
    ) {
        final AdmissionEvidence admission = admissionForArtifact(artifact);
        final String profile = admission.cubismVersion().equals("5.3.02") ? "5.3.02" : "5.2";
        return directory.resolve("cubism-" + profile + "-ui-bounding-box-overlay.json");
    }

    public record AdmissionEvidence(
        String cubismVersion,
        long artifactSize,
        String artifactSha256
    ) {
        public String adapterSliceId() {
            return ADAPTER_SLICE_ID;
        }
    }
}
