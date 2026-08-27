package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CubismEditorIdentityRoutingTest {

    @Test
    void exact5303ArtifactAgreesWithEditorProfileAndStaticRecords() {
        final HostArtifactDigest artifact = ReviewedHostArtifacts.CUBISM_5_3_03;

        assertEquals("5.3.03", EditorModelVerificationManifest.resourceProfileForArtifact(artifact));
        assertManifest(
            EditorModelVerificationManifest.forArtifact(artifact),
            "cubism-5.3.03.editor-model.static",
            "d9fc0b9412a3014e623ff3f962efd0b1f350f713e1d5275a7f0ce661c33444bb",
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            EditorModelVerificationManifest.CAPABILITY_IDS,
            EditorModelVerificationManifest.cubism5303StaticAliases()
        );
        assertManifest(
            ProjectWorkspaceVerificationManifest.forArtifact(artifact),
            "m15.cubism-5.3.03.project-workspace.static",
            "d7f45e0c7d70925b4c77db18022b06ee4f089bc7b1cbe585ef311efa754f168e",
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES
        );
    }

    @Test
    void identificationOpensFullRuntimeWithoutCreatingASeparateCoreProfile() {
        org.junit.jupiter.api.Assertions.assertTrue(
            ReviewedHostArtifacts.admitsFullRuntime("5.3.03")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> dev.turboism.adapter.cubism.core.CoreVersionExpectation.reviewedProfile("5.3.03")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new VerifiedCorePublicApiResolverFactory().create(
                "5.3.03",
                Path.of("missing-record"),
                Path.of("missing-artifact"),
                ClassLoader.getPlatformClassLoader()
            )
        );
    }

    private static void assertManifest(
        final PinnedVerifiedResolverWorkflow.Manifest manifest,
        final String verificationId,
        final String recordSha256,
        final String adapterSliceId,
        final Set<String> capabilityIds,
        final Set<String> aliases
    ) {
        assertEquals(verificationId, manifest.verificationId());
        assertEquals(recordSha256, manifest.recordSha256());
        assertEquals("5.3.03", manifest.cubismVersion());
        assertEquals("cubism-5.3.03", manifest.profileId());
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_03.size(), manifest.artifactSize());
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_03.sha256(), manifest.artifactSha256());
        assertEquals(adapterSliceId, manifest.adapterSliceId());
        assertEquals(capabilityIds, manifest.capabilityIds());
        assertEquals(aliases, manifest.requiredAliases());
    }

}
