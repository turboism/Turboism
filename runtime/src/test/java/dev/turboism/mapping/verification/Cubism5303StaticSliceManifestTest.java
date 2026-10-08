package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Cubism5303StaticSliceManifestTest {

    private static final HostArtifactDigest REVIEWED = ReviewedHostArtifacts.CUBISM_5_3_03;
    private static final HostArtifactDigest FOREIGN = new HostArtifactDigest(1L, "0".repeat(64));

    @Test
    void exactArtifactSelectsEveryPinnedStaticSlice() {
        assertManifest(
                MainToolbarVerificationManifest.forArtifact(REVIEWED),
                "cubism-5.3.03.ui-main-toolbar.static",
                "cee7eca72cd4abf180d0e01905381907c8ae35056d68dc4b2f7911d2e2d4fee4",
                MainToolbarVerificationManifest.ADAPTER_SLICE_ID,
                MainToolbarVerificationManifest.CAPABILITY_IDS,
                MainToolbarVerificationManifest.REQUIRED_ALIASES);
        assertManifest(
                EmbeddedPanelVerificationManifest.forArtifact(REVIEWED),
                "cubism-5.3.03.ui-embedded-panel.static",
                "efd21e78301e09cb5cdf4d4d80fdbaa78c6f676f8bb813ef28af41a2656dca55",
                EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID,
                EmbeddedPanelVerificationManifest.CAPABILITY_IDS,
                EmbeddedPanelVerificationManifest.REQUIRED_ALIASES);
        assertManifest(
                TopMenuVerificationManifest.forArtifact(REVIEWED),
                "cubism-5.3.03.ui-top-menu.static",
                "8468ba23f43f3176cd20b92851348c719b1b8811b90c5af055fd26bf10f0bfea",
                TopMenuVerificationManifest.ADAPTER_SLICE_ID,
                TopMenuVerificationManifest.CAPABILITY_IDS,
                TopMenuVerificationManifest.REQUIRED_ALIASES);
        assertManifest(
                BoundingBoxOverlayButtonVerificationManifest.forArtifact(REVIEWED),
                "cubism-5.3.03.ui-bounding-box-overlay.static",
                "fd67451595cbf68ca3084504c730daee3b110577e67852680db64b3a6f81e000",
                BoundingBoxOverlayButtonVerificationManifest.ADAPTER_SLICE_ID,
                BoundingBoxOverlayButtonVerificationManifest.CAPABILITY_IDS,
                BoundingBoxOverlayButtonVerificationManifest.REQUIRED_ALIASES);
        assertManifest(
                StatusBarVerificationManifest.forArtifact(REVIEWED),
                "cubism-5.3.03.ui-status-bar.static",
                "f74152ef76ac4f88daf22ae3670aa8296757f18818f98d8763deea913cd3a1f2",
                StatusBarVerificationManifest.ADAPTER_SLICE_ID,
                StatusBarVerificationManifest.CAPABILITY_IDS,
                StatusBarVerificationManifest.REQUIRED_ALIASES);
        assertManifest(
                ControlAppearanceVerificationManifest.forArtifact(REVIEWED),
                "cubism-5.3.03.ui-control-appearance.static",
                "6c771b8564af0569ed1c47064f13db20a0da5b8ef3dd1d90d06905311c58f710",
                ControlAppearanceVerificationManifest.ADAPTER_SLICE_ID,
                ControlAppearanceVerificationManifest.CAPABILITY_IDS,
                ControlAppearanceVerificationManifest.REQUIRED_ALIASES);
        assertManifest(
                WorkspaceControlVerificationManifest.forArtifact(REVIEWED),
                "m.workspace-5.3.03.control.static",
                "1a1be264fe64c6dc3e2f1ec10cb854a475540ebf1270b93e6d50e01ae6b801fc",
                "adapter.workspace.control.v5_3",
                Set.of(WorkspaceControlVerificationManifest.CAPABILITY_ID),
                WorkspaceControlVerificationManifest.REQUIRED_ALIASES);
        assertManifest(
                ProjectWorkspaceVerificationManifest.forArtifact(REVIEWED),
                "m15.cubism-5.3.03.project-workspace.static",
                "f52edde0c7d1a59d5bed7dd693f5a74e6946d0b14bbe9e13fedf2c49e6fa5613",
                ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
                ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
                ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES);
        assertManifest(
                AutoBackupVerificationManifest.forArtifact(REVIEWED),
                AutoBackupVerificationManifest.VERIFICATION_ID_5303,
                AutoBackupVerificationManifest.RECORD_SHA256_5303,
                AutoBackupVerificationManifest.ADAPTER_SLICE_ID,
                AutoBackupVerificationManifest.CAPABILITY_IDS,
                AutoBackupVerificationManifest.REQUIRED_ALIASES);
    }

    @Test
    void everyStaticSliceRejectsAnUnreviewedArtifact() {
        assertThrows(IllegalArgumentException.class, () -> MainToolbarVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class, () -> EmbeddedPanelVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class, () -> TopMenuVerificationManifest.forArtifact(FOREIGN));
        assertThrows(
                IllegalArgumentException.class,
                () -> BoundingBoxOverlayButtonVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class, () -> StatusBarVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class, () -> ControlAppearanceVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceControlVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class, () -> ProjectWorkspaceVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class, () -> AutoBackupVerificationManifest.forArtifact(FOREIGN));
    }

    @Test
    void boundingBoxRecordPathIsExactAndUnknownVersionsFailClosed() {
        assertEquals(
                Path.of("records/cubism-5.3.03-ui-bounding-box-overlay.json"),
                BoundingBoxOverlayButtonVerificationManifest.verifiedRecordForArtifact(REVIEWED, Path.of("records")));
        assertThrows(
                IllegalArgumentException.class,
                () -> BoundingBoxOverlayButtonVerificationManifest.recordSha256ForVersion("5.3.04"));
    }

    private static void assertManifest(
            final PinnedVerifiedResolverWorkflow.Manifest manifest,
            final String verificationId,
            final String recordSha256,
            final String adapterSliceId,
            final Set<String> capabilityIds,
            final Set<String> requiredAliases) {
        assertEquals(verificationId, manifest.verificationId());
        assertEquals(recordSha256, manifest.recordSha256());
        assertEquals("5.3.03", manifest.cubismVersion());
        assertEquals("cubism-5.3.03", manifest.profileId());
        assertEquals(REVIEWED.size(), manifest.artifactSize());
        assertEquals(REVIEWED.sha256(), manifest.artifactSha256());
        assertEquals(adapterSliceId, manifest.adapterSliceId());
        assertEquals(capabilityIds, manifest.capabilityIds());
        assertEquals(requiredAliases, manifest.requiredAliases());
    }
}
