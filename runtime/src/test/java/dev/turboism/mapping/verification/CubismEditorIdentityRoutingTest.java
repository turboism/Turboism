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
            "7e03714a07e27400c86e5a39e45bfdc5d00612030a1819ad547ab239bdea5665",
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            EditorModelVerificationManifest.CAPABILITY_IDS,
            EditorModelVerificationManifest.cubism5303StaticAliases()
        );
        assertManifest(
            ProjectWorkspaceVerificationManifest.forArtifact(artifact),
            "m15.cubism-5.3.03.project-workspace.static",
            "a238d1ef701f59130d792b2b6ada3961ab9541f6cf5236bbed25d5f9d558eab2",
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES
        );
    }

    @Test
    void identificationDoesNotOpenFullRuntimeOrCreateACoreProfile() {
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
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
