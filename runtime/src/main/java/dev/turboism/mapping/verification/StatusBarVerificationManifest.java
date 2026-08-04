package dev.turboism.mapping.verification;

import java.util.Set;

/**
 * Runtime trust root for the exact Cubism 5.3.02 native bottom status bar.
 *
 * <p>5.3.02-only by design: there is no reviewed 5.2 record or manifest for the
 * platform-owned status region, and 5.2 must keep failing closed.</p>
 */
public final class StatusBarVerificationManifest {

    public static final String VERIFICATION_ID = "cubism-5.3.02.ui-status-bar.static";
    public static final String RECORD_SHA256 =
        "afdc21fa80c62f3359d998aac8f8afbe6b6d8ebbbae2a1c24c9754225b53f8d2";
    public static final String CUBISM_VERSION = "5.3.02";
    public static final String PROFILE_ID = "cubism-5.3.02";
    public static final long ARTIFACT_SIZE = 41_922_739L;
    public static final String ARTIFACT_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.status-bar";
    public static final String CAPABILITY_ID = "ui.status.notify";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-status-bar.app-controller.class",
        "cubism.ui-status-bar.app-controller.instance",
        "cubism.ui-status-bar.app-controller.main-frame",
        "cubism.ui-status-bar.main-frame-controller.class",
        "cubism.ui-status-bar.main-frame-controller.frame",
        "cubism.ui-status-bar.frame.class",
        "cubism.ui-status-bar.frame.content-pane",
        "cubism.ui-status-bar.widget.class",
        "cubism.ui-status-bar.widget.set-name",
        "cubism.ui-status-bar.widget.set-tooltip",
        "cubism.ui-status-bar.widget.revalidate",
        "cubism.ui-status-bar.widget.repaint",
        "cubism.ui-status-bar.container.class",
        "cubism.ui-status-bar.container.children",
        "cubism.ui-status-bar.container.add",
        "cubism.ui-status-bar.container.remove",
        "cubism.ui-status-bar.label.class",
        "cubism.ui-status-bar.label.create",
        "cubism.ui-status-bar.label.text",
        "cubism.ui-status-bar.label.set-text",
        "cubism.ui-status-bar.memory-viewer.class"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        if (artifact.size() == ARTIFACT_SIZE && artifact.sha256().equals(ARTIFACT_SHA256)) {
            return manifest();
        }
        throw new IllegalArgumentException(
            "host artifact is not the reviewed Cubism 5.3.02 status-bar artifact"
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

    private static PinnedVerifiedResolverWorkflow.Manifest manifest() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID,
            RECORD_SHA256,
            CUBISM_VERSION,
            PROFILE_ID,
            ARTIFACT_SIZE,
            ARTIFACT_SHA256,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private StatusBarVerificationManifest() {
    }
}
