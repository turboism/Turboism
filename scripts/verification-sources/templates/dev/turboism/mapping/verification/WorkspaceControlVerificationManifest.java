package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact-version trust roots for Cubism workspace control. */
public final class WorkspaceControlVerificationManifest {
    public static final String CAPABILITY_ID = "cubism.workspace.control";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "workspace.app.class", "workspace.app.instance", "workspace.app.main-frame",
        "workspace.main-frame.dock", "workspace.dock.palette-manager", "workspace.palette-manager.current", "workspace.dock.preset",
        "workspace.dock.custom", "workspace.workspace.id", "workspace.workspace.name",
        "workspace.id.value", "workspace.dock.change", "workspace.dock.update-default",
        "workspace.dock.reset-default", "workspace.workspace.default-layout"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        if (ReviewedHostArtifacts.CUBISM_5_0_00.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.00-workspace-control.json:verificationId}",
                "${record:cubism-5.0.00-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_00_VERSION, "${record:cubism-5.0.00-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_00.size(), artifact.sha256(),
                "${record:cubism-5.0.00-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_01.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.01-workspace-control.json:verificationId}",
                "${record:cubism-5.0.01-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_01_VERSION, "${record:cubism-5.0.01-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_01.size(), artifact.sha256(),
                "${record:cubism-5.0.01-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_02.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.02-workspace-control.json:verificationId}",
                "${record:cubism-5.0.02-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_02_VERSION, "${record:cubism-5.0.02-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_02.size(), artifact.sha256(),
                "${record:cubism-5.0.02-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_03.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.03-workspace-control.json:verificationId}",
                "${record:cubism-5.0.03-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_03_VERSION, "${record:cubism-5.0.03-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_03.size(), artifact.sha256(),
                "${record:cubism-5.0.03-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_04.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.04-workspace-control.json:verificationId}",
                "${record:cubism-5.0.04-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_04_VERSION, "${record:cubism-5.0.04-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_04.size(), artifact.sha256(),
                "${record:cubism-5.0.04-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_05.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.05-workspace-control.json:verificationId}",
                "${record:cubism-5.0.05-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_05_VERSION, "${record:cubism-5.0.05-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_05.size(), artifact.sha256(),
                "${record:cubism-5.0.05-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_06.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.06-workspace-control.json:verificationId}",
                "${record:cubism-5.0.06-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_06_VERSION, "${record:cubism-5.0.06-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_06.size(), artifact.sha256(),
                "${record:cubism-5.0.06-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_00.equals(artifact)) {
            return manifest(
                "${record:cubism-5.1.00-workspace-control.json:verificationId}",
                "${record:cubism-5.1.00-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_1_00_VERSION, "${record:cubism-5.1.00-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_1_00.size(), artifact.sha256(),
                "${record:cubism-5.1.00-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_01.equals(artifact)) {
            return manifest(
                "${record:cubism-5.1.01-workspace-control.json:verificationId}",
                "${record:cubism-5.1.01-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_1_01_VERSION, "${record:cubism-5.1.01-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_1_01.size(), artifact.sha256(),
                "${record:cubism-5.1.01-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_02.equals(artifact)) {
            return manifest(
                "${record:cubism-5.1.02-workspace-control.json:verificationId}",
                "${record:cubism-5.1.02-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_1_02_VERSION, "${record:cubism-5.1.02-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_1_02.size(), artifact.sha256(),
                "${record:cubism-5.1.02-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_03.equals(artifact)) {
            return manifest(
                "${record:cubism-5.1.03-workspace-control.json:verificationId}",
                "${record:cubism-5.1.03-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_1_03_VERSION, "${record:cubism-5.1.03-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_1_03.size(), artifact.sha256(),
                "${record:cubism-5.1.03-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_00.equals(artifact)) {
            return manifest(
                "${record:cubism-5.2.00-workspace-control.json:verificationId}",
                "${record:cubism-5.2.00-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_2_00_VERSION, "${record:cubism-5.2.00-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_2_00.size(), artifact.sha256(),
                "${record:cubism-5.2.00-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_01.equals(artifact)) {
            return manifest(
                "${record:cubism-5.2.01-workspace-control.json:verificationId}",
                "${record:cubism-5.2.01-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_2_01_VERSION, "${record:cubism-5.2.01-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_2_01.size(), artifact.sha256(),
                "${record:cubism-5.2.01-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_02.equals(artifact)) {
            return manifest(
                "${record:cubism-5.2.02-workspace-control.json:verificationId}",
                "${record:cubism-5.2.02-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_2_02_VERSION, "${record:cubism-5.2.02-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_2_02.size(), artifact.sha256(),
                "${record:cubism-5.2.02-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_03.equals(artifact)) {
            return manifest(
                "${record:cubism-5.2.03-workspace-control.json:verificationId}",
                "${record:cubism-5.2.03-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_2_03_VERSION, "${record:cubism-5.2.03-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_2_03.size(), artifact.sha256(),
                "${record:cubism-5.2.03-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_00.equals(artifact)) {
            return manifest(
                "${record:cubism-5.3.00-workspace-control.json:verificationId}",
                "${record:cubism-5.3.00-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_3_00_VERSION, "${record:cubism-5.3.00-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_3_00.size(), artifact.sha256(),
                "${record:cubism-5.3.00-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_01.equals(artifact)) {
            return manifest(
                "${record:cubism-5.3.01-workspace-control.json:verificationId}",
                "${record:cubism-5.3.01-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_3_01_VERSION, "${record:cubism-5.3.01-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_3_01.size(), artifact.sha256(),
                "${record:cubism-5.3.01-workspace-control.json:adapterSliceId}"
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
                "${record:cubism-5.3.03-workspace-control.json:adapterSliceId}"
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_04.equals(artifact)) {
            return manifest(
                "${record:cubism-5.3.04-workspace-control.json:verificationId}",
                "${record:cubism-5.3.04-workspace-control.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_3_04_VERSION, "${record:cubism-5.3.04-workspace-control.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_3_04.size(), artifact.sha256(),
                "${record:cubism-5.3.04-workspace-control.json:adapterSliceId}"
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
