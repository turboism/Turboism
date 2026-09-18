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
                "${record:cubism-5.2.03-workspace-control.json:verificationId}",
                "${record:cubism-5.2.03-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_2_03_VERSION, "${record:cubism-5.2.03-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_2_03.size(), artifact.sha256(),
                "${record:cubism-5.2.03-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02.equals(artifact)) {
            return manifest(
                "${record:cubism-5.3.02-workspace-control.json:verificationId}",
                "${record:cubism-5.3.02-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_3_02_VERSION, "${record:cubism-5.3.02-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_3_02.size(), artifact.sha256(),
                "${record:cubism-5.3.02-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_03.equals(artifact)) {
            return manifest(
                "${record:cubism-5.3.03-workspace-control.json:verificationId}",
                "${record:cubism-5.3.03-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_3_03_VERSION, "${record:cubism-5.3.03-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_3_03.size(), artifact.sha256(),
                "${record:cubism-5.3.02-workspace-control.json:adapterSliceId}"
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
