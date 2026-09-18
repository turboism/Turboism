package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consolidates the per-feature runtime-admission contracts that previously lived
 * in one single-test class per feature. Every assertion is preserved verbatim
 * from the classes it replaces.
 */
final class TurboismAgentAdmissionTest {

    @Test
    void fpsAdmitsExact5303OnlyAfterFullRuntimeAdmission() {
        assertTrue(TurboismAgent.fpsRuntimeAdmitted("5.3.03", true));
        assertFalse(TurboismAgent.fpsRuntimeAdmitted("5.3.03", false));
        assertFalse(TurboismAgent.fpsRuntimeAdmitted("5.3.04", true));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    @Test
    void textureAtlasAdmitsExact5303OnlyAfterFullRuntimeAdmission() {
        assertTrue(TurboismAgent.textureAtlasRuntimeAdmitted("5.3.03", true));
        assertFalse(TurboismAgent.textureAtlasRuntimeAdmitted("5.3.03", false));
        assertFalse(TurboismAgent.textureAtlasRuntimeAdmitted("5.3.04", true));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    @Test
    void statusBarAdmitsExact5303OnlyAfterFullRuntimeAdmission() {
        assertTrue(TurboismAgent.statusBarRuntimeAdmitted("5.3.03", true));
        assertFalse(TurboismAgent.statusBarRuntimeAdmitted("5.3.03", false));
        assertFalse(TurboismAgent.statusBarRuntimeAdmitted("5.3.04", true));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    @Test
    void projectLifecycleAdmitsExact5303OnlyAfterFullRuntimeAdmission() {
        assertTrue(TurboismAgent.projectLifecycleRuntimeAdmitted("5.3.03", true));
        assertFalse(TurboismAgent.projectLifecycleRuntimeAdmitted("5.3.03", false));
        assertFalse(TurboismAgent.projectLifecycleRuntimeAdmitted("5.3.04", true));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    @Test
    void parameterLifecycleAdmitsExact5303OnlyAfterFullRuntimeAdmission() {
        assertTrue(TurboismAgent.parameterLifecycleRuntimeAdmitted("5.3.03", true));
        assertFalse(TurboismAgent.parameterLifecycleRuntimeAdmitted("5.3.03", false));
        assertFalse(TurboismAgent.parameterLifecycleRuntimeAdmitted("5.3.04", true));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    @Test
    void fileChooserHistoryAdmitsExact5303OnlyAfterFullRuntimeAdmission() {
        assertTrue(TurboismAgent.fileChooserHistoryRuntimeAdmitted("5.3.03", true));
        assertFalse(TurboismAgent.fileChooserHistoryRuntimeAdmitted("5.3.03", false));
        assertFalse(TurboismAgent.fileChooserHistoryRuntimeAdmitted("5.3.04", true));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    @Test
    void autoBackupAdmitsExact5303OnlyAfterFullRuntimeAdmission() {
        assertTrue(TurboismAgent.autoBackupRuntimeAdmitted("5.3.03", true));
        assertFalse(TurboismAgent.autoBackupRuntimeAdmitted("5.3.03", false));
        assertFalse(TurboismAgent.autoBackupRuntimeAdmitted("5.3.04", true));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }
}
