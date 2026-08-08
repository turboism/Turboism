package dev.turboism.adapter;

import dev.turboism.adapter.host.HostVerificationEvidence;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedRuntimeHostAdaptersFactoryStatusBarTest {

    @Test
    void evidenceRejectsStatusSliceFromADifferentClassloaderOrArtifact() {
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        ClassLoader hostClassLoader = getClass().getClassLoader();

        assertThrows(IllegalArgumentException.class, () -> HostVerificationEvidence
            .withClipMask(
                slice("records/project.json", artifact, hostClassLoader),
                slice("records/clip.json", artifact, hostClassLoader)
            )
            .addingStatusBar(slice("records/status.json", artifact, new ClassLoader() { })));

        assertThrows(IllegalArgumentException.class, () -> HostVerificationEvidence
            .withClipMask(
                slice("records/project.json", artifact, hostClassLoader),
                slice("records/clip.json", artifact, hostClassLoader)
            )
            .addingStatusBar(slice("records/status.json", Path.of("host/other.jar"), hostClassLoader)));
    }

    @Test
    void statusBarSurvivesOtherAddingBuildersAndIsNotAToolbarOrOtherSlice() {
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        ClassLoader hostClassLoader = getClass().getClassLoader();
        HostVerificationEvidence.Slice statusBar = slice("records/status.json", artifact, hostClassLoader);

        HostVerificationEvidence evidence = HostVerificationEvidence
            .withClipMask(
                slice("records/project.json", artifact, hostClassLoader),
                slice("records/clip.json", artifact, hostClassLoader)
            )
            .addingStatusBar(statusBar)
            .addingMainToolbar(slice("records/toolbar.json", artifact, hostClassLoader))
            .addingTopMenu(slice("records/menu.json", artifact, hostClassLoader));

        assertEquals(statusBar, evidence.statusBar().orElseThrow());
        assertTrue(evidence.mainToolbar().isPresent());
        assertTrue(evidence.topMenu().isPresent());
        assertFalse(evidence.statusBar().orElseThrow().equals(evidence.mainToolbar().orElseThrow()),
            "the status slice must not be confused with the main-toolbar slice");
    }

    @Test
    void statusBarMissingKeepsTheUsualEvidenceShape() {
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        ClassLoader hostClassLoader = getClass().getClassLoader();
        HostVerificationEvidence evidence = HostVerificationEvidence
            .withClipMask(
                slice("records/project.json", artifact, hostClassLoader),
                slice("records/clip.json", artifact, hostClassLoader)
            )
            .addingBoundingBoxOverlayButton(slice("records/overlay.json", artifact, hostClassLoader));

        assertTrue(evidence.statusBar().isEmpty(),
            "5.2 or status-less evidence must stay statusBar-absent (safe mode)");
        assertTrue(evidence.boundingBoxOverlayButton().isPresent());
    }

    private static HostVerificationEvidence.Slice slice(
        final String record,
        final Path artifact,
        final ClassLoader classLoader
    ) {
        return new HostVerificationEvidence.Slice(Path.of(record), artifact, classLoader);
    }
}
