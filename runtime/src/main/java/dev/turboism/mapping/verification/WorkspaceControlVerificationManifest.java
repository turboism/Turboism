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
        if (artifact.size() == 40_805_584L && artifact.sha256().equals(
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd")) {
            return manifest(
                "m.workspace-5.2.03.control.static",
                "8b001802fa672ce2f053ab516af9c38b2a2a08296fc663e9adf352e88c7dbf36",
                "5.2.03", "cubism-5.2", 40_805_584L, artifact.sha256(),
                "adapter.workspace.control.v5_2"
            );
        }
        if (artifact.size() == 41_922_739L && artifact.sha256().equals(
            "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21")) {
            return manifest(
                "m.workspace-5.3.02.control.static",
                "7c675de8b23e63e6de14ae6c67403717d3b64fc8eefab54ac4124fffb3633f16",
                "5.3.02", "cubism-5.3.02", 41_922_739L, artifact.sha256(),
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
