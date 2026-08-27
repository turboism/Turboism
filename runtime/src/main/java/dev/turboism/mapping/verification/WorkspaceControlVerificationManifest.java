package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact-version trust roots for Cubism workspace control. */
public final class WorkspaceControlVerificationManifest {
    public static final String CAPABILITY_ID = "cubism.workspace.control";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "workspace.app.class", "workspace.app.instance", "workspace.app.main-frame",
        "workspace.main-frame.dock", "workspace.dock.current", "workspace.dock.preset",
        "workspace.dock.custom", "workspace.workspace.id", "workspace.workspace.name",
        "workspace.id.value", "workspace.dock.change", "workspace.dock.update-default",
        "workspace.dock.reset-default"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        if (ReviewedHostArtifacts.CUBISM_5_2_03.equals(artifact)) {
            return manifest(
                "m.workspace-5.2.03.control.static",
                "f42efb4d878ac4dfb9398dfc978705217d1c55a21d9521690d77fede9af32fed",
                ReviewedHostArtifacts.CUBISM_5_2_03_VERSION, "cubism-5.2.03",
                ReviewedHostArtifacts.CUBISM_5_2_03.size(), artifact.sha256(),
                "adapter.workspace.control.v5_2"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02.equals(artifact)) {
            return manifest(
                "m.workspace-5.3.02.control.static",
                "7c675de8b23e63e6de14ae6c67403717d3b64fc8eefab54ac4124fffb3633f16",
                ReviewedHostArtifacts.CUBISM_5_3_02_VERSION, "cubism-5.3.02",
                ReviewedHostArtifacts.CUBISM_5_3_02.size(), artifact.sha256(),
                "adapter.workspace.control.v5_3"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_03.equals(artifact)) {
            return manifest(
                "m.workspace-5.3.03.control.static",
                "2cd67a76c2377e1ee2a60829091a824a0ea9487f562251360534fba1225a2690",
                ReviewedHostArtifacts.CUBISM_5_3_03_VERSION, "cubism-5.3.03",
                ReviewedHostArtifacts.CUBISM_5_3_03.size(), artifact.sha256(),
                "adapter.workspace.control.v5_3"
            );
        }
        throw new IllegalArgumentException("host artifact is not a reviewed Cubism workspace-control artifact");
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest(
        String verificationId, String recordSha256, String version, String profile,
        long size, String artifactSha256, String sliceId
    ) {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            verificationId, recordSha256, version, profile, size, artifactSha256,
            sliceId, Set.of(CAPABILITY_ID), REQUIRED_ALIASES
        );
    }

    private WorkspaceControlVerificationManifest() { }
}
