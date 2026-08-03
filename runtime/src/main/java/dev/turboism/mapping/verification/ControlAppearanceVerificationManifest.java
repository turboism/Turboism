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
        if (artifact.size() == 40_805_584L
            && artifact.sha256().equals("bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd")) {
            return manifest(
                "cubism-5.2.03.ui-control-appearance.static",
                "a1a9900374bc9a8637f2de0973206f1ac92bd59a93df6933ae5fd919b0134f6f",
                "5.2.03",
                "cubism-5.2",
                40_805_584L,
                "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"
            );
        }
        if (artifact.size() == 41_922_739L
            && artifact.sha256().equals("988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21")) {
            return manifest(
                "cubism-5.3.02.ui-control-appearance.static",
                "d5ae349fd18dbebbd52b2bf1e84291ecd325d7a2ec6d0be6bae0609285aebbac",
                "5.3.02",
                "cubism-5.3.02",
                41_922_739L,
                "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
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
