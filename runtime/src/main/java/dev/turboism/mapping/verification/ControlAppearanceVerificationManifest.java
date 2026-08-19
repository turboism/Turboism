package dev.turboism.mapping.verification;

import java.util.Set;

/** Runtime trust root for exact Cubism 5.2.03/5.3.02 control-appearance hooks. */
public final class ControlAppearanceVerificationManifest {
    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.control-appearance";
    public static final Set<String> CAPABILITY_IDS = Set.of(
        "cubism.editor-ui.control-appearance.parameter-label",
        "cubism.editor-ui.control-appearance.parameter-folder",
        "cubism.editor-ui.control-appearance.deformer-label",
        "cubism.editor-ui.control-appearance.deformer-control-row",
        "cubism.editor-ui.control-appearance.part-label",
        "cubism.editor-ui.control-appearance.part-folder"
    );
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-control-appearance.deformer-label.renderer",
        "cubism.ui-control-appearance.deformer-row.source",
        "cubism.ui-control-appearance.deformer-source.class",
        "cubism.ui-control-appearance.art-mesh.source-class",
        "cubism.ui-control-appearance.art-mesh.source-id",
        "cubism.ui-control-appearance.deformer-source.id",
        "cubism.ui-control-appearance.deformer-control.renderer",
        "cubism.ui-control-appearance.deformer-control.outer",
        "cubism.ui-control-appearance.deformer-control.outer-class",
        "cubism.ui-control-appearance.deformer-control.tree",
        "cubism.ui-control-appearance.deformer-control.tree-class",
        "cubism.ui-control-appearance.parameter.single-class",
        "cubism.ui-control-appearance.parameter.double-class",
        "cubism.ui-control-appearance.parameter.folder-class",
        "cubism.ui-control-appearance.parameter.single-create",
        "cubism.ui-control-appearance.parameter.single-selection",
        "cubism.ui-control-appearance.parameter.double-create",
        "cubism.ui-control-appearance.parameter.double-selection",
        "cubism.ui-control-appearance.parameter.folder-create-primary",
        "cubism.ui-control-appearance.parameter.folder-create-secondary",
        "cubism.ui-control-appearance.parameter.folder-selection",
        "cubism.ui-control-appearance.parameter.source",
        "cubism.ui-control-appearance.parameter.secondary-source",
        "cubism.ui-control-appearance.parameter.label",
        "cubism.ui-control-appearance.parameter.secondary-label",
        "cubism.ui-control-appearance.parameter.folder-source",
        "cubism.ui-control-appearance.parameter.folder-label",
        "cubism.ui-control-appearance.parameter.source-class",
        "cubism.ui-control-appearance.parameter.folder-source-class",
        "cubism.ui-control-appearance.parameter.source-id",
        "cubism.ui-control-appearance.parameter.folder-source-id",
        "cubism.ui-control-appearance.parameter.label-class",
        "cubism.ui-control-appearance.parameter.label-swing",
        "cubism.ui-control-appearance.part.renderer",
        "cubism.ui-control-appearance.part.node-class",
        "cubism.ui-control-appearance.part.node-source",
        "cubism.ui-control-appearance.part.source-class",
        "cubism.ui-control-appearance.part.source-id",
        "cubism.ui-control-appearance.part.source-children",
        "cubism.ui-control-appearance.id.value"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        if (ReviewedHostArtifacts.CUBISM_5_2_03.equals(artifact)) {
            return manifest(
                "cubism-5.2.03.ui-control-appearance.static",
                "bc272ba4263012d2776c40871f6ff5260fc8f92f02d76b764925439b13c7d57a",
                ReviewedHostArtifacts.CUBISM_5_2_03_VERSION,
                "cubism-5.2",
                ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                ReviewedHostArtifacts.CUBISM_5_2_03.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02.equals(artifact)) {
            return manifest(
                "cubism-5.3.02.ui-control-appearance.static",
                "cfba3d12584eb9ca7d601a95ca9e5a227112573b8abe7043b5961b2a0c87c82f",
                ReviewedHostArtifacts.CUBISM_5_3_02_VERSION,
                "cubism-5.3.02",
                ReviewedHostArtifacts.CUBISM_5_3_02.size(),
                ReviewedHostArtifacts.CUBISM_5_3_02.sha256()
            );
        }
        throw new IllegalArgumentException("host artifact is not a reviewed Cubism control-appearance artifact");
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

    private ControlAppearanceVerificationManifest() { }
}
