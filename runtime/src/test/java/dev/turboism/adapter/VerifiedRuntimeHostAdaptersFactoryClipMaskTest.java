package dev.turboism.adapter;

import dev.turboism.adapter.host.HostVerificationEvidence;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedRuntimeHostAdaptersFactoryClipMaskTest {

    @Test
    void clipMaskRejectsSliceFromADifferentClassloaderOrArtifact() {
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        ClassLoader hostClassLoader = getClass().getClassLoader();

        assertThrows(IllegalArgumentException.class, () -> HostVerificationEvidence
            .withEditorModel(
                slice("records/project.json", artifact, hostClassLoader),
                slice("records/editor.json", artifact, hostClassLoader)
            )
            .addingClipMask(slice("records/clip.json", artifact, new ClassLoader() { })));

        assertThrows(IllegalArgumentException.class, () -> HostVerificationEvidence
            .withEditorModel(
                slice("records/project.json", artifact, hostClassLoader),
                slice("records/editor.json", artifact, hostClassLoader)
            )
            .addingClipMask(slice("records/clip.json", Path.of("host/other.jar"), hostClassLoader)));
    }

    @Test
    void clipMaskSurvivesOtherAddingBuildersAndIsNotAnotherSlice() {
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        ClassLoader hostClassLoader = getClass().getClassLoader();
        HostVerificationEvidence.Slice clipMask = slice("records/clip.json", artifact, hostClassLoader);

        HostVerificationEvidence evidence = HostVerificationEvidence
            .withEditorModel(
                slice("records/project.json", artifact, hostClassLoader),
                slice("records/editor.json", artifact, hostClassLoader)
            )
            .addingClipMask(clipMask)
            .addingStatusBar(slice("records/status.json", artifact, hostClassLoader))
            .addingMainToolbar(slice("records/toolbar.json", artifact, hostClassLoader));

        assertEquals(clipMask, evidence.clipMask().orElseThrow());
        assertTrue(evidence.editorModel().isPresent());
        assertTrue(evidence.statusBar().isPresent());
        assertFalse(evidence.clipMask().orElseThrow().equals(evidence.statusBar().orElseThrow()),
            "the clip slice must not be confused with the status-bar slice");
    }

    @Test
    void clipMaskMissingKeepsTheUsualEvidenceShape() {
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        ClassLoader hostClassLoader = getClass().getClassLoader();
        HostVerificationEvidence evidence = HostVerificationEvidence
            .withEditorModel(
                slice("records/project.json", artifact, hostClassLoader),
                slice("records/editor.json", artifact, hostClassLoader)
            )
            .addingBoundingBoxOverlayButton(slice("records/overlay.json", artifact, hostClassLoader));

        assertTrue(evidence.clipMask().isEmpty(),
            "5.2 or clip-less evidence must stay clipMask-absent (safe mode)");
        assertTrue(evidence.editorModel().isPresent());
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
