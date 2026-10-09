package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CubismEditorIdentityRoutingTest {

    @Test
    void exact5303ArtifactAgreesWithEditorProfileAndStaticRecords() {
        final HostArtifactDigest artifact = ReviewedHostArtifacts.CUBISM_5_3_03;

        assertEquals("5.3.03", EditorModelVerificationManifest.resourceProfileForArtifact(artifact));
        assertManifest(
                EditorModelVerificationManifest.forArtifact(artifact),
                "cubism-5.3.03.editor-model.static",
                "ba3d1c7c4d593d78ab2cb46bf3af96fc3f9c6f264df84b1035d466ad44da51aa",
                EditorModelVerificationManifest.ADAPTER_SLICE_ID,
                EditorModelVerificationManifest.cubism5303Capabilities(),
                EditorModelVerificationManifest.cubism5303StaticAliases());
        assertManifest(
                ProjectWorkspaceVerificationManifest.forArtifact(artifact),
                "m15.cubism-5.3.03.project-workspace.static",
                "f52edde0c7d1a59d5bed7dd693f5a74e6946d0b14bbe9e13fedf2c49e6fa5613",
                ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
                ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
                ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES);
    }

    @Test
    void identificationOpensFullRuntimeWithoutCreatingASeparateCoreProfile() {
        org.junit.jupiter.api.Assertions.assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
        // 5.3.03 ships the byte-identical 5.3.02 Core jar, so its Core expectation is
        // the shared reviewed tuple — not a new profile — and the digest-keyed
        // manifest still refuses a named "5.3.03" profile.
        org.junit.jupiter.api.Assertions.assertEquals(
                new dev.turboism.adapter.cubism.core.CoreRuntimeVersion(6, 0, 257),
                dev.turboism.adapter.cubism.core.CoreVersionExpectation.reviewedProfile("5.3.03")
                        .exactVersion());
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedCorePublicApiResolverFactory()
                        .create(
                                "5.3.03",
                                Path.of("missing-record"),
                                Path.of("missing-artifact"),
                                ClassLoader.getPlatformClassLoader()));
    }

    private static void assertManifest(
            final PinnedVerifiedResolverWorkflow.Manifest manifest,
            final String verificationId,
            final String recordSha256,
            final String adapterSliceId,
            final Set<String> capabilityIds,
            final Set<String> aliases) {
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
