package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PerformanceFpsVerificationManifestTest {

    @Test
    void exact5303FpsRequiresTheCompoundValidationIdentity() {
        assertTrue(PerformanceFpsVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_FPS_RENDER_SCENE_CANDIDATE",
            "5303",
            "render-scene",
            "fps-5303-r1"
        ));
        assertFalse(PerformanceFpsVerificationManifest.admits5303ValidationCandidate(
            null,
            "5303",
            "render-scene",
            "fps-5303-r1"
        ));
        assertFalse(PerformanceFpsVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_FPS_RENDER_SCENE_CANDIDATE",
            "5302",
            "render-scene",
            "fps-5303-r1"
        ));
        assertFalse(PerformanceFpsVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_FPS_RENDER_SCENE_CANDIDATE",
            "5303",
            "full-probe",
            "fps-5303-r1"
        ));
        assertFalse(PerformanceFpsVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_FPS_RENDER_SCENE_CANDIDATE",
            "5303",
            "render-scene",
            " "
        ));
    }
}
