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
            "06a8e47b238503f224b60f6c3e29e404c2c7ca3041f1e3418f855badeb542262",
            MainToolbarVerificationManifest.ADAPTER_SLICE_ID,
            MainToolbarVerificationManifest.CAPABILITY_IDS,
            MainToolbarVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            EmbeddedPanelVerificationManifest.forArtifact(REVIEWED),
            "cubism-5.3.03.ui-embedded-panel.static",
            "f520ec5496dfd80b78c0dad54d6f9ab0db56142720156b4c551cd38c02cfdb23",
            EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID,
            EmbeddedPanelVerificationManifest.CAPABILITY_IDS,
            EmbeddedPanelVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            TopMenuVerificationManifest.forArtifact(REVIEWED),
            "cubism-5.3.03.ui-top-menu.static",
            "4c54e06b5d22e6af5936044645edea0c55b529cb38b1c6da59fd53a18fc3da0e",
            TopMenuVerificationManifest.ADAPTER_SLICE_ID,
            TopMenuVerificationManifest.CAPABILITY_IDS,
            TopMenuVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            BoundingBoxOverlayButtonVerificationManifest.forArtifact(REVIEWED),
            "cubism-5.3.03.ui-bounding-box-overlay.static",
            "5250893619f75791aec026d8f93fb62e4b4ed61760f9f193604d63e5f85ea2f0",
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
            "7a29da7e518727c6f3ccc43309ff1922fb0a4ed08fbe90879396cc7e86a0f6de",
            ControlAppearanceVerificationManifest.ADAPTER_SLICE_ID,
            ControlAppearanceVerificationManifest.CAPABILITY_IDS,
            ControlAppearanceVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            WorkspaceControlVerificationManifest.forArtifact(REVIEWED),
            "m.workspace-5.3.03.control.static",
            "2cd67a76c2377e1ee2a60829091a824a0ea9487f562251360534fba1225a2690",
            "adapter.workspace.control.v5_3",
            Set.of(WorkspaceControlVerificationManifest.CAPABILITY_ID),
            WorkspaceControlVerificationManifest.REQUIRED_ALIASES
        );
        assertManifest(
            ProjectWorkspaceVerificationManifest.forArtifact(REVIEWED),
            "m15.cubism-5.3.03.project-workspace.static",
            "d7f45e0c7d70925b4c77db18022b06ee4f089bc7b1cbe585ef311efa754f168e",
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
