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
                "88811e3a663e595d7b02675fc0e86e486132eb78c96772a90bef3e7d3c7abb94",
                ReviewedHostArtifacts.CUBISM_5_2_03_VERSION, "cubism-5.2.03",
                ReviewedHostArtifacts.CUBISM_5_2_03.size(), artifact.sha256(),
                "adapter.workspace.control.v5_2"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02.equals(artifact)) {
            return manifest(
                "m.workspace-5.3.02.control.static",
                "cbf5c201267d7aa70d3f82404e9125f61429c7a251457a5a23011c6d6bf27b4f",
                ReviewedHostArtifacts.CUBISM_5_3_02_VERSION, "cubism-5.3.02",
                ReviewedHostArtifacts.CUBISM_5_3_02.size(), artifact.sha256(),
                "adapter.workspace.control.v5_3"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_03.equals(artifact)) {
            return manifest(
                "m.workspace-5.3.03.control.static",
                "19a28870070b7f0e5c49060fef15dea087ebd37c7d20963d6aabd55fc5a464da",
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
