package dev.turboism.mapping.verification;

import java.util.Set;

/** Runtime trust root for exact-version Cubism top-menu providers. */
public final class TopMenuVerificationManifest {

    public static final String VERIFICATION_ID = "cubism-5.3.02.ui-top-menu.static";
    public static final String RECORD_SHA256 =
        "dbfd0310ee2e8a1b38c264a51bc19db64b81effdf3af65e49e682d5fce039bc7";
    public static final String CUBISM_VERSION = "5.3.02";
    public static final String PROFILE_ID = "cubism-5.3.02";
    public static final long ARTIFACT_SIZE = 41_922_739L;
    public static final String ARTIFACT_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.top-menu";
    public static final String CAPABILITY_ID = "cubism.editor-ui.top-menu";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-top-menu.app-controller.instance",
        "cubism.ui-top-menu.app-controller.main-frame",
        "cubism.ui-top-menu.main-frame.window",
        "cubism.ui-top-menu.window.menu-bar",
        "cubism.ui-top-menu.menu-bar.menus",
        "cubism.ui-top-menu.menu-bar.add",
        "cubism.ui-top-menu.menu-bar.swing",
        "cubism.ui-top-menu.widget.name",
        "cubism.ui-top-menu.widget.set-name",
        "cubism.ui-top-menu.widget.revalidate",
        "cubism.ui-top-menu.widget.repaint",
        "cubism.ui-top-menu.menu.create",
        "cubism.ui-top-menu.menu.add",
        "cubism.ui-top-menu.menu.swing",
        "cubism.ui-top-menu.menu-item.create"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        if (artifact.size() == TopMenuVerificationManifest52.ARTIFACT_SIZE
            && artifact.sha256().equals(TopMenuVerificationManifest52.ARTIFACT_SHA256)) {
            return manifest(
                TopMenuVerificationManifest52.VERIFICATION_ID,
                TopMenuVerificationManifest52.RECORD_SHA256,
                TopMenuVerificationManifest52.CUBISM_VERSION,
                TopMenuVerificationManifest52.PROFILE_ID,
                TopMenuVerificationManifest52.ARTIFACT_SIZE,
                TopMenuVerificationManifest52.ARTIFACT_SHA256
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
        throw new IllegalArgumentException("host artifact is not a reviewed Cubism top-menu artifact");
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

    private TopMenuVerificationManifest() {
    }
}
