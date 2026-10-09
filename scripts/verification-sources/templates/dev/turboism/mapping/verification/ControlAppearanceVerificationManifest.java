package dev.turboism.mapping.verification;

import java.util.Set;

/** Static trust roots for exact-version Cubism control-appearance selectors. */
public final class ControlAppearanceVerificationManifest {
    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-ui-control-appearance.json:adapterSliceId}";
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
        if (ReviewedHostArtifacts.CUBISM_5_0_00.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.00-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.0.00-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_00_VERSION, "${record:cubism-5.0.00-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_00.size(),
                ReviewedHostArtifacts.CUBISM_5_0_00.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_01.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.01-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.0.01-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_01_VERSION, "${record:cubism-5.0.01-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_01.size(),
                ReviewedHostArtifacts.CUBISM_5_0_01.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_02.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.02-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.0.02-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_02_VERSION, "${record:cubism-5.0.02-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_02.size(),
                ReviewedHostArtifacts.CUBISM_5_0_02.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_03.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.03-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.0.03-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_03_VERSION, "${record:cubism-5.0.03-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_03.size(),
                ReviewedHostArtifacts.CUBISM_5_0_03.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_04.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.04-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.0.04-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_04_VERSION, "${record:cubism-5.0.04-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_04.size(),
                ReviewedHostArtifacts.CUBISM_5_0_04.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_05.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.05-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.0.05-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_05_VERSION, "${record:cubism-5.0.05-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_05.size(),
                ReviewedHostArtifacts.CUBISM_5_0_05.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_0_06.equals(artifact)) {
            return manifest(
                "${record:cubism-5.0.06-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.0.06-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_0_06_VERSION, "${record:cubism-5.0.06-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_0_06.size(),
                ReviewedHostArtifacts.CUBISM_5_0_06.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_00.equals(artifact)) {
            return manifest(
                "${record:cubism-5.1.00-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.1.00-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_1_00_VERSION, "${record:cubism-5.1.00-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_1_00.size(),
                ReviewedHostArtifacts.CUBISM_5_1_00.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_01.equals(artifact)) {
            return manifest(
                "${record:cubism-5.1.01-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.1.01-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_1_01_VERSION, "${record:cubism-5.1.01-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_1_01.size(),
                ReviewedHostArtifacts.CUBISM_5_1_01.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_02.equals(artifact)) {
            return manifest(
                "${record:cubism-5.1.02-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.1.02-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_1_02_VERSION, "${record:cubism-5.1.02-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_1_02.size(),
                ReviewedHostArtifacts.CUBISM_5_1_02.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_03.equals(artifact)) {
            return manifest(
                "${record:cubism-5.1.03-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.1.03-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_1_03_VERSION, "${record:cubism-5.1.03-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_1_03.size(),
                ReviewedHostArtifacts.CUBISM_5_1_03.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_00.equals(artifact)) {
            return manifest(
                "${record:cubism-5.2.00-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.2.00-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_2_00_VERSION, "${record:cubism-5.2.00-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_2_00.size(),
                ReviewedHostArtifacts.CUBISM_5_2_00.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_01.equals(artifact)) {
            return manifest(
                "${record:cubism-5.2.01-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.2.01-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_2_01_VERSION, "${record:cubism-5.2.01-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_2_01.size(),
                ReviewedHostArtifacts.CUBISM_5_2_01.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_02.equals(artifact)) {
            return manifest(
                "${record:cubism-5.2.02-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.2.02-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_2_02_VERSION, "${record:cubism-5.2.02-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_2_02.size(),
                ReviewedHostArtifacts.CUBISM_5_2_02.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_03.equals(artifact)) {
            return manifest(
                "${record:cubism-5.2.03-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.2.03-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_2_03_VERSION, "${record:cubism-5.2.03-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                ReviewedHostArtifacts.CUBISM_5_2_03.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_00.equals(artifact)) {
            return manifest(
                "${record:cubism-5.3.00-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.3.00-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_3_00_VERSION, "${record:cubism-5.3.00-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_3_00.size(),
                ReviewedHostArtifacts.CUBISM_5_3_00.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_01.equals(artifact)) {
            return manifest(
                "${record:cubism-5.3.01-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.3.01-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_3_01_VERSION, "${record:cubism-5.3.01-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_3_01.size(),
                ReviewedHostArtifacts.CUBISM_5_3_01.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02.equals(artifact)) {
            return manifest(
                "${record:cubism-5.3.02-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.3.02-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_3_02_VERSION, "${record:cubism-5.3.02-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_3_02.size(),
                ReviewedHostArtifacts.CUBISM_5_3_02.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_03.equals(artifact)) {
            return manifest(
                "${record:cubism-5.3.03-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.3.03-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_3_03_VERSION, "${record:cubism-5.3.03-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_3_03.size(),
                ReviewedHostArtifacts.CUBISM_5_3_03.sha256()
            );
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_04.equals(artifact)) {
            return manifest(
                "${record:cubism-5.3.04-ui-control-appearance.json:verificationId}",
                "${record:cubism-5.3.04-ui-control-appearance.json:sha256}",
                ReviewedHostArtifacts.CUBISM_5_3_04_VERSION, "${record:cubism-5.3.04-ui-control-appearance.json:profileId}",
                ReviewedHostArtifacts.CUBISM_5_3_04.size(),
                ReviewedHostArtifacts.CUBISM_5_3_04.sha256()
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
