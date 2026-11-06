package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the weave every migrated hook declares, one row per hook: the matched-site count the routine
 * must prove, the number of classes its close proof re-captures, and the admission row the hook froze
 * before the migration.
 *
 * <p>The counts are copied from the hand-rolled protocols these hooks used to carry
 * ({@code matches() != n}, {@code guarded() != n}, {@code touchedClasses().size() != n}), so a refactor
 * that silently widens or narrows a proof fails here. The constants are load-bearing: each installer
 * passes {@code EXPECTED_MATCHES} to its {@link WeaveTarget} and derives its touched owners from the
 * transformer or reviewed target, so the row is the declaration, not a copy of it.</p>
 */
class WeaveSpecTest {

    @Test
    void everyMigratedHookKeepsItsExpectedMatchCount() {
        // Batch A — one target, one transformer, scalar restoration proof.
        assertEquals(1, VerifiedModelUpdateSkipInstaller.EXPECTED_MATCHES, VerifiedModelUpdateSkipInstaller.HOOK_ID);
        assertEquals(
                1,
                VerifiedWarpPositionProjectionInstaller.EXPECTED_MATCHES,
                VerifiedWarpPositionProjectionInstaller.HOOK_ID);
        assertEquals(
                1, VerifiedImageArchiveReuseInstaller.EXPECTED_MATCHES, VerifiedImageArchiveReuseInstaller.HOOK_ID);
        assertEquals(1, VerifiedAsyncReadbackInstaller.EXPECTED_MATCHES, VerifiedAsyncReadbackInstaller.HOOK_ID);
        assertEquals(
                1,
                VerifiedFloatArrayParseCacheInstaller.EXPECTED_MATCHES,
                VerifiedFloatArrayParseCacheInstaller.HOOK_ID);
        // Batch B — multi-target and multi-transformer hooks.
        assertEquals(1, VerifiedSortingInfoCacheInstaller.EXPECTED_MATCHES, VerifiedSortingInfoCacheInstaller.HOOK_ID);
        assertEquals(
                2,
                VerifiedDeferredGlErrorCheckInstaller.EXPECTED_MATCHES,
                VerifiedDeferredGlErrorCheckInstaller.HOOK_ID);
        assertEquals(2, VerifiedCanvasCompositeElisionInstaller.EXPECTED_MATCHES, "TURBOISM_CANVAS_COMPOSITE");
        assertEquals(
                1,
                VerifiedSkippedFrameUploadElisionInstaller.EXPECTED_MATCHES,
                VerifiedSkippedFrameUploadElisionInstaller.HOOK_ID);
        assertEquals(
                1,
                VerifiedSkippedFrameMeshSetupElisionInstaller.EXPECTED_MATCHES,
                VerifiedSkippedFrameMeshSetupElisionInstaller.HOOK_ID);
        assertEquals(1, VerifiedInputPathElisionInstaller.EXPECTED_MATCHES, VerifiedInputPathElisionInstaller.HOOK_ID);
        assertEquals(
                1,
                VerifiedLineRendererUploadSplitInstaller.EXPECTED_MATCHES,
                VerifiedLineRendererUploadSplitInstaller.HOOK_ID);
        assertEquals(
                7, VerifiedIncrementalUpdateInstaller.EXPECTED_MATCHES, VerifiedIncrementalUpdateInstaller.HOOK_ID);
        assertEquals(
                1,
                VerifiedTextureUploadPreparationInstaller.EXPECTED_MATCHES,
                VerifiedTextureUploadPreparationInstaller.HOOK_ID);
        // The three D1 hooks gain a match count and a restoration proof in this slice.
        assertEquals(
                3,
                VerifiedClipMaskDeduplicationInstaller.EXPECTED_MATCHES,
                VerifiedClipMaskDeduplicationInstaller.HOOK_ID);
        assertEquals(
                2,
                VerifiedExtendedInterpolationSetupInstaller.EXPECTED_MATCHES,
                VerifiedExtendedInterpolationSetupInstaller.HOOK_ID);
        assertEquals(
                6,
                VerifiedInspectorPropertyBypassInstaller.EXPECTED_MATCHES,
                VerifiedInspectorPropertyBypassInstaller.HOOK_ID);
        // The two hooks that invented their own target list keep one rewritten site per transformer.
        assertEquals(1, VerifiedUniformLocationInstaller.EXPECTED_MATCHES, VerifiedUniformLocationInstaller.HOOK_ID);
        assertEquals(1, VerifiedMatrixScratchInstaller.EXPECTED_MATCHES, VerifiedMatrixScratchInstaller.HOOK_ID);
    }

    @Test
    void everyMigratedHookKeepsItsTouchedOwners() {
        assertEquals(1, VerifiedModelUpdateSkipInstaller.EXPECTED_OWNERS, VerifiedModelUpdateSkipInstaller.HOOK_ID);
        assertEquals(
                1,
                VerifiedWarpPositionProjectionInstaller.EXPECTED_OWNERS,
                VerifiedWarpPositionProjectionInstaller.HOOK_ID);
        assertEquals(1, VerifiedImageArchiveReuseInstaller.EXPECTED_OWNERS, VerifiedImageArchiveReuseInstaller.HOOK_ID);
        assertEquals(1, VerifiedAsyncReadbackInstaller.EXPECTED_OWNERS, VerifiedAsyncReadbackInstaller.HOOK_ID);
        assertEquals(
                1,
                VerifiedFloatArrayParseCacheInstaller.EXPECTED_OWNERS,
                VerifiedFloatArrayParseCacheInstaller.HOOK_ID);
        // Two rewritten classes each: the sorting pair, the deferred pair and the composite pair.
        assertEquals(2, VerifiedSortingInfoCacheInstaller.EXPECTED_OWNERS, VerifiedSortingInfoCacheInstaller.HOOK_ID);
        assertEquals(
                2,
                VerifiedDeferredGlErrorCheckInstaller.EXPECTED_OWNERS,
                VerifiedDeferredGlErrorCheckInstaller.HOOK_ID);
        assertEquals(2, VerifiedCanvasCompositeElisionInstaller.EXPECTED_OWNERS, "TURBOISM_CANVAS_COMPOSITE");
        assertEquals(
                2,
                VerifiedSkippedFrameUploadElisionInstaller.EXPECTED_OWNERS,
                VerifiedSkippedFrameUploadElisionInstaller.HOOK_ID);
        assertEquals(
                1,
                VerifiedSkippedFrameMeshSetupElisionInstaller.EXPECTED_OWNERS,
                VerifiedSkippedFrameMeshSetupElisionInstaller.HOOK_ID);
        assertEquals(1, VerifiedInputPathElisionInstaller.EXPECTED_OWNERS, VerifiedInputPathElisionInstaller.HOOK_ID);
        assertEquals(
                1,
                VerifiedLineRendererUploadSplitInstaller.EXPECTED_OWNERS,
                VerifiedLineRendererUploadSplitInstaller.HOOK_ID);
        assertEquals(5, VerifiedIncrementalUpdateInstaller.EXPECTED_OWNERS, VerifiedIncrementalUpdateInstaller.HOOK_ID);
        assertEquals(
                1,
                VerifiedTextureUploadPreparationInstaller.EXPECTED_OWNERS,
                VerifiedTextureUploadPreparationInstaller.HOOK_ID);
        // One rewritten render-system class, one setup owner and six inspector setters.
        assertEquals(
                1,
                VerifiedClipMaskDeduplicationInstaller.EXPECTED_OWNERS,
                VerifiedClipMaskDeduplicationInstaller.HOOK_ID);
        assertEquals(
                1,
                VerifiedExtendedInterpolationSetupInstaller.EXPECTED_OWNERS,
                VerifiedExtendedInterpolationSetupInstaller.HOOK_ID);
        assertEquals(
                6,
                VerifiedInspectorPropertyBypassInstaller.EXPECTED_OWNERS,
                VerifiedInspectorPropertyBypassInstaller.HOOK_ID);
        // The shader query plus the four lifecycle roles, and the one rewritten traversal.
        assertEquals(5, VerifiedUniformLocationInstaller.EXPECTED_OWNERS, VerifiedUniformLocationInstaller.HOOK_ID);
        assertEquals(1, VerifiedMatrixScratchInstaller.EXPECTED_OWNERS, VerifiedMatrixScratchInstaller.HOOK_ID);
    }

    @Test
    void everyMigratedHookKeepsItsAdmissionRow() {
        // Policy id, kill-switch semantics and JVM predicate: the three facts the migration must not move.
        assertRow(VerifiedModelUpdateSkipInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);
        assertRow(VerifiedWarpPositionProjectionInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.EXACTLY_17);
        assertRow(VerifiedImageArchiveReuseInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.ANY);
        // The pipelined readback never consulted the startup policy: its only switch is the preference.
        assertRow(VerifiedAsyncReadbackInstaller.ADMISSION, PolicyCheck.SKIP, JvmCheck.ANY);
        assertRow(VerifiedFloatArrayParseCacheInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.ANY);
        assertRow(VerifiedSortingInfoCacheInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.ANY);
        assertRow(VerifiedDeferredGlErrorCheckInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);
        // The test-only experiments are switched by their own system property, never by the policy.
        assertRow(VerifiedCanvasCompositeElisionInstaller.ADMISSION, PolicyCheck.SKIP, JvmCheck.AT_LEAST_17);
        assertRow(VerifiedSkippedFrameUploadElisionInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);
        assertRow(VerifiedSkippedFrameMeshSetupElisionInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);
        assertRow(VerifiedInputPathElisionInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);
        assertRow(VerifiedLineRendererUploadSplitInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.ANY);
        assertRow(VerifiedIncrementalUpdateInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);
        assertRow(VerifiedTextureUploadPreparationInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.EXACTLY_17);
        // The two hooks whose only switch is their own preference never consult the startup policy.
        assertRow(VerifiedClipMaskDeduplicationInstaller.ADMISSION, PolicyCheck.SKIP, JvmCheck.ANY);
        assertRow(VerifiedExtendedInterpolationSetupInstaller.ADMISSION, PolicyCheck.SKIP, JvmCheck.ANY);
        assertRow(VerifiedInspectorPropertyBypassInstaller.ADMISSION, PolicyCheck.SKIP, JvmCheck.AT_LEAST_17);
        assertRow(VerifiedUniformLocationInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);
        assertRow(VerifiedMatrixScratchInstaller.ADMISSION, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);
    }

    private static void assertRow(final Admission admission, final PolicyCheck policyCheck, final JvmCheck jvmCheck) {
        assertEquals(policyCheck, admission.policyCheck(), admission.hookId());
        assertEquals(jvmCheck, admission.jvmCheck(), admission.hookId());
    }
}
