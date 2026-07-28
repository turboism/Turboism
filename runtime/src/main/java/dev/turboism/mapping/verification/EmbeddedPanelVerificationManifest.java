package dev.turboism.mapping.verification;

import java.util.Set;

/** Runtime trust root for exact-version Cubism embedded-panel providers. */
public final class EmbeddedPanelVerificationManifest {

    public static final String VERIFICATION_ID =
        "cubism-5.3.02.ui-embedded-panel.static";
    public static final String RECORD_SHA256 =
        "374de8a2831b3c214cfa326764da39676b1e6350aed30843e954c3e56ff98c09";
    public static final String CUBISM_VERSION = "5.3.02";
    public static final String PROFILE_ID = "cubism-5.3.02";
    public static final long ARTIFACT_SIZE = 41_922_739L;
    public static final String ARTIFACT_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.embedded-panel";
    public static final String CAPABILITY_ID = "cubism.editor-ui.embedded-panel";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-panel.app-controller.class",
        "cubism.ui-panel.app-controller.instance",
        "cubism.ui-panel.app-controller.main-frame",
        "cubism.ui-panel.app-controller.repaint",
        "cubism.ui-panel.main-frame.class",
        "cubism.ui-panel.main-frame.dock-manager",
        "cubism.ui-panel.dock.class",
        "cubism.ui-panel.dock.palette-manager",
        "cubism.ui-panel.dock.set-palette-visible",
        "cubism.ui-panel.dock.update-window-menu",
        "cubism.ui-panel.palette-manager.class",
        "cubism.ui-panel.palette-manager.get",
        "cubism.ui-panel.palette-manager.add",
        "cubism.ui-panel.palette-manager.close",
        "cubism.ui-panel.palette-manager.current-workspace",
        "cubism.ui-panel.workspace.class",
        "cubism.ui-panel.workspace.activate",
        "cubism.ui-panel.palette-id.class",
        "cubism.ui-panel.palette-id.create",
        "cubism.ui-panel.palette.class",
        "cubism.ui-panel.palette.create",
        "cubism.ui-panel.palette.set-panel",
        "cubism.ui-panel.swing-container.class",
        "cubism.ui-panel.swing-container.create"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(
        final HostArtifactDigest artifact
    ) {
        if (artifact.size() == EmbeddedPanelVerificationManifest52.ARTIFACT_SIZE
            && artifact.sha256().equals(EmbeddedPanelVerificationManifest52.ARTIFACT_SHA256)) {
            return manifest(
                EmbeddedPanelVerificationManifest52.VERIFICATION_ID,
                EmbeddedPanelVerificationManifest52.RECORD_SHA256,
                EmbeddedPanelVerificationManifest52.CUBISM_VERSION,
                EmbeddedPanelVerificationManifest52.PROFILE_ID,
                EmbeddedPanelVerificationManifest52.ARTIFACT_SIZE,
                EmbeddedPanelVerificationManifest52.ARTIFACT_SHA256
            );
        }
        if (artifact.size() == ARTIFACT_SIZE && artifact.sha256().equals(ARTIFACT_SHA256)) {
            return manifest(
                VERIFICATION_ID,
                RECORD_SHA256,
                CUBISM_VERSION,
                PROFILE_ID,
                ARTIFACT_SIZE,
                ARTIFACT_SHA256
            );
        }
        throw new IllegalArgumentException(
            "host artifact is not a reviewed Cubism embedded-panel artifact"
        );
    }

    public static AdmissionEvidence admissionForArtifact(final HostArtifactDigest artifact) {
        final PinnedVerifiedResolverWorkflow.Manifest manifest = forArtifact(artifact);
        return new AdmissionEvidence(
            manifest.cubismVersion(),
            manifest.artifactSize(),
            manifest.artifactSha256(),
            manifest.adapterSliceId(),
            manifest.recordSha256()
        );
    }

    public record AdmissionEvidence(
        String cubismVersion,
        long artifactSize,
        String artifactSha256,
        String adapterSliceId,
        String recordSha256
    ) {
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest(
        final String verificationId,
        final String recordSha256,
        final String cubismVersion,
        final String profileId,
        final long artifactSize,
        final String artifactSha256
    ) {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            verificationId,
            recordSha256,
            cubismVersion,
            profileId,
            artifactSize,
            artifactSha256,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private EmbeddedPanelVerificationManifest() {
    }
}
