package dev.turboism.mapping.verification;

import java.util.Set;

/** Runtime trust root for the Cubism 5.3.02 native main-toolbar provider. */
public final class MainToolbarVerificationManifest {

    public static final String VERIFICATION_ID =
        "cubism-5.3.02.ui-main-toolbar.static";
    public static final String RECORD_SHA256 =
        "bd0eed7d67cf3bbed0ca3a2367c74c010d47384ae20cac5cf236379bad379d30";
    public static final String CUBISM_VERSION = "5.3.02";
    public static final String PROFILE_ID = "cubism-5.3.02";
    public static final long ARTIFACT_SIZE = 41_922_739L;
    public static final String ARTIFACT_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.main-toolbar";
    public static final String CAPABILITY_ID = "cubism.editor-ui.main-toolbar";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-main-toolbar.app-controller.class",
        "cubism.ui-main-toolbar.app-controller.instance",
        "cubism.ui-main-toolbar.app-controller.main-frame",
        "cubism.ui-main-toolbar.main-frame.class",
        "cubism.ui-main-toolbar.main-frame.view",
        "cubism.ui-main-toolbar.main-frame-view.class",
        "cubism.ui-main-toolbar.main-frame-view.home-button",
        "cubism.ui-main-toolbar.main-frame-view.main-container",
        "cubism.ui-main-toolbar.vbox.create",
        "cubism.ui-main-toolbar.widget.class",
        "cubism.ui-main-toolbar.widget.jcomponent",
        "cubism.ui-main-toolbar.widget.parent",
        "cubism.ui-main-toolbar.widget.name",
        "cubism.ui-main-toolbar.widget.set-name",
        "cubism.ui-main-toolbar.widget.set-tooltip",
        "cubism.ui-main-toolbar.widget.set-pref-width",
        "cubism.ui-main-toolbar.widget.set-pref-height",
        "cubism.ui-main-toolbar.widget.revalidate",
        "cubism.ui-main-toolbar.widget.repaint",
        "cubism.ui-main-toolbar.container.class",
        "cubism.ui-main-toolbar.container.children",
        "cubism.ui-main-toolbar.container.add",
        "cubism.ui-main-toolbar.container.remove",
        "cubism.ui-main-toolbar.icon-button.class",
        "cubism.ui-main-toolbar.icon-button.create",
        "cubism.ui-main-toolbar.icon-button.set-rollover-icon",
        "cubism.ui-main-toolbar.icon.class",
        "cubism.ui-main-toolbar.icon.create"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(
        final HostArtifactDigest artifact
    ) {
        if (artifact.size() == MainToolbarVerificationManifest52.ARTIFACT_SIZE
            && artifact.sha256().equals(MainToolbarVerificationManifest52.ARTIFACT_SHA256)) {
            return manifest(
                MainToolbarVerificationManifest52.VERIFICATION_ID,
                MainToolbarVerificationManifest52.RECORD_SHA256,
                MainToolbarVerificationManifest52.CUBISM_VERSION,
                MainToolbarVerificationManifest52.PROFILE_ID,
                MainToolbarVerificationManifest52.ARTIFACT_SIZE,
                MainToolbarVerificationManifest52.ARTIFACT_SHA256
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
            "host artifact is not a reviewed Cubism main-toolbar artifact"
        );
    }

    public static AdmissionEvidence admissionForArtifact(
        final HostArtifactDigest artifact
    ) {
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

    private MainToolbarVerificationManifest() {
    }
}
