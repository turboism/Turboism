package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Cubism5303StaticSliceManifestTest {

    private static final HostArtifactDigest REVIEWED = ReviewedHostArtifacts.CUBISM_5_3_03;
    private static final HostArtifactDigest FOREIGN = new HostArtifactDigest(1L, "0".repeat(64));

    @Test
    void exactArtifactSelectsEveryPinnedStaticSlice() {
        assertManifest(
            MainToolbarVerificationManifest.forArtifact(REVIEWED),
            "cubism-5.3.03.ui-main-toolbar.static",
            "3c3beb4f6574558b735c56d2c08dc07c9b7052c7406cb2fe77d7acd66a6c7d07",
            MainToolbarVerificationManifest.ADAPTER_SLICE_ID,
            MainToolbarVerificationManifest.CAPABILITY_IDS,
            MainToolbarVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            EmbeddedPanelVerificationManifest.forArtifact(REVIEWED),
            "cubism-5.3.03.ui-embedded-panel.static",
            "089a76ea22fd2dcc688e18bdc2157997416095ba61ab1e290769d92390891065",
            EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID,
            EmbeddedPanelVerificationManifest.CAPABILITY_IDS,
            EmbeddedPanelVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            TopMenuVerificationManifest.forArtifact(REVIEWED),
            "cubism-5.3.03.ui-top-menu.static",
            "14738c81260f4ac6f5c56c391ced3e923bca0176af7b7a9dfda0c64f4b26973b",
            TopMenuVerificationManifest.ADAPTER_SLICE_ID,
            TopMenuVerificationManifest.CAPABILITY_IDS,
            TopMenuVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            BoundingBoxOverlayButtonVerificationManifest.forArtifact(REVIEWED),
            "cubism-5.3.03.ui-bounding-box-overlay.static",
            "add4f142ad6d84a04b7e1b6bbfa4e82107982352fddd4e49a6a85cc2fdfb0ae5",
            BoundingBoxOverlayButtonVerificationManifest.ADAPTER_SLICE_ID,
            BoundingBoxOverlayButtonVerificationManifest.CAPABILITY_IDS,
            BoundingBoxOverlayButtonVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            StatusBarVerificationManifest.forArtifact(REVIEWED),
            "cubism-5.3.03.ui-status-bar.static",
            "7ac88d2e842e85636a2bb3aabc137fa2fd2312a76f442c9de24d6ba48ac54ec7",
            StatusBarVerificationManifest.ADAPTER_SLICE_ID,
            StatusBarVerificationManifest.CAPABILITY_IDS,
            StatusBarVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            ControlAppearanceVerificationManifest.forArtifact(REVIEWED),
            "cubism-5.3.03.ui-control-appearance.static",
            "9d6e88817a5596adb5d2057c4269ec01d2c1d7b0c49170aa7003ee289e4c11c0",
            ControlAppearanceVerificationManifest.ADAPTER_SLICE_ID,
            ControlAppearanceVerificationManifest.CAPABILITY_IDS,
            ControlAppearanceVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            WorkspaceControlVerificationManifest.forArtifact(REVIEWED),
            "m.workspace-5.3.03.control.static",
            "19a28870070b7f0e5c49060fef15dea087ebd37c7d20963d6aabd55fc5a464da",
            "adapter.workspace.control.v5_3",
            Set.of(WorkspaceControlVerificationManifest.CAPABILITY_ID),
            WorkspaceControlVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            ProjectWorkspaceVerificationManifest.forArtifact(REVIEWED),
            "m15.cubism-5.3.03.project-workspace.static",
            "a238d1ef701f59130d792b2b6ada3961ab9541f6cf5236bbed25d5f9d558eab2",
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            AutoBackupVerificationManifest.forArtifact(REVIEWED),
            AutoBackupVerificationManifest.VERIFICATION_ID_5303,
            AutoBackupVerificationManifest.RECORD_SHA256_5303,
            AutoBackupVerificationManifest.ADAPTER_SLICE_ID,
            AutoBackupVerificationManifest.CAPABILITY_IDS,
            AutoBackupVerificationManifest.REQUIRED_ALIASES
        );
    }

    @Test
    void everyStaticSliceRejectsAnUnreviewedArtifact() {
        assertThrows(IllegalArgumentException.class,
            () -> MainToolbarVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class,
            () -> EmbeddedPanelVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class,
            () -> TopMenuVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class,
            () -> BoundingBoxOverlayButtonVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class,
            () -> StatusBarVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class,
            () -> ControlAppearanceVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class,
            () -> WorkspaceControlVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class,
            () -> ProjectWorkspaceVerificationManifest.forArtifact(FOREIGN));
        assertThrows(IllegalArgumentException.class,
            () -> AutoBackupVerificationManifest.forArtifact(FOREIGN));
    }

    @Test
    void boundingBoxRecordPathIsExactAndUnknownVersionsFailClosed() {
        assertEquals(
            Path.of("records/cubism-5.3.03-ui-bounding-box-overlay.json"),
            BoundingBoxOverlayButtonVerificationManifest.verifiedRecordForArtifact(
                REVIEWED,
                Path.of("records")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> BoundingBoxOverlayButtonVerificationManifest.recordSha256ForVersion("5.3.04")
        );
    }

    private static void assertManifest(
        final PinnedVerifiedResolverWorkflow.Manifest manifest,
        final String verificationId,
        final String recordSha256,
        final String adapterSliceId,
        final Set<String> capabilityIds,
        final Set<String> requiredAliases
    ) {
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
