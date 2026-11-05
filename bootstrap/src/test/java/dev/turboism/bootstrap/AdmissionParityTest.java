package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the 17 admission truth tables the verified installers froze now that they are expressed as
 * {@link Admission} rows: which hooks consult the kill switch at all, which JVM predicate each retains,
 * and that every row still refuses an unreviewed artifact in every policy arrangement. No row may widen
 * silently, so each expectation below is written out by hand instead of being read back from the row it
 * checks.
 */
class AdmissionParityTest {

    private static final HostArtifactDigest UNREVIEWED_DIGEST = new HostArtifactDigest(1024, "0".repeat(64));

    @TempDir
    Path directory;

    private Path unreviewed;

    /** What one former {@code admitted(...)} body promised. */
    private record Frozen(boolean killSwitch, String jvm) {}

    /** One installer's frozen row plus the call that exercises its own former admission body. */
    private record Row(String hook, Admission admission, Frozen frozen, Gate gate) {}

    /** The installer's own {@code admitted(...)}, with an unreviewed artifact, across the matrix. */
    @FunctionalInterface
    private interface Gate {
        boolean admitted(RuntimeStartupConfig policy, boolean requested, int jvm);
    }

    private static final List<RuntimeStartupConfig> POLICIES = List.of(
            open(),
            safeMode(),
            killSwitched("cubism.render.matrix-scratch"),
            killSwitched("cubism.model-update.incremental"));

    private static final int[] JVMS = {16, 17, 21};

    @Test
    void everyRowKeepsTheKillSwitchAndJvmPredicateItFroze() {
        for (final Row row : rows()) {
            assertEquals(
                    row.frozen().killSwitch(),
                    row.admission().policyCheck() == PolicyCheck.REQUIRED,
                    row.hook() + ": only the hooks that never consulted the policy may skip it");
            assertEquals(row.frozen().jvm(), row.admission().jvmCheck().name(), row.hook() + ": JVM predicate");
        }
    }

    @Test
    void theSharedGateReproducesEveryFormerTruthTable() {
        for (final Row row : rows()) {
            for (final RuntimeStartupConfig policy : POLICIES) {
                for (final boolean requested : new boolean[] {false, true}) {
                    for (final int jvm : JVMS) {
                        assertEquals(
                                expected(row, policy, requested, jvm),
                                VerifiedInstall.admits(row.admission(), policy, requested, jvm),
                                row.hook() + " policy=" + describe(policy) + " requested=" + requested + " jvm=" + jvm);
                    }
                }
            }
        }
    }

    @Test
    void everyInstallerStillRefusesAnUnreviewedArtifact() {
        for (final Row row : rows()) {
            for (final RuntimeStartupConfig policy : POLICIES) {
                for (final boolean requested : new boolean[] {false, true}) {
                    for (final int jvm : JVMS) {
                        assertFalse(
                                row.gate().admitted(policy, requested, jvm),
                                row.hook() + ": an unreviewed artifact must never be admitted, policy="
                                        + describe(policy) + " requested=" + requested + " jvm=" + jvm);
                    }
                }
            }
        }
    }

    @Test
    void aMissingPolicyRefusesEveryHookThatConsultsIt() {
        for (final Row row : rows()) {
            assertEquals(
                    !row.frozen().killSwitch(),
                    VerifiedInstall.admits(row.admission(), null, true, 17),
                    row.hook() + ": a launch without a policy snapshot can only admit a hook that ignores it");
        }
    }

    @Test
    void theRequirementGateIsPartOfTheRow() {
        final Row row = rows().stream()
                .filter(candidate -> !candidate.frozen().killSwitch())
                .findFirst()
                .orElseThrow();
        assertTrue(VerifiedInstall.admits(row.admission(), safeMode(), true, 17), "unpoliced hooks ignore safe mode");
        assertFalse(VerifiedInstall.admits(row.admission(), open(), false, 17), "an unrequested hook is refused");
        assertFalse(
                VerifiedInstall.admits(row.admission(), safeMode(), true, 16),
                "the JVM predicate still applies to an unpoliced hook");
    }

    @Test
    void theTestOnlyGlGetErrorExperimentNeverConsultsTheKillSwitch() {
        assertEquals(PolicyCheck.SKIP, VerifiedGlGetErrorElisionInstaller.ADMISSION.policyCheck());
        assertTrue(VerifiedInstall.admits(
                VerifiedGlGetErrorElisionInstaller.ADMISSION,
                safeMode(),
                true,
                Runtime.version().feature()));
    }

    private static boolean expected(
            final Row row, final RuntimeStartupConfig policy, final boolean requested, final int jvm) {
        if (!requested) return false;
        final boolean jvmAdmits =
                switch (row.frozen().jvm()) {
                    case "ANY" -> true;
                    case "AT_LEAST_17" -> jvm >= 17;
                    case "EXACTLY_17" -> jvm == 17;
                    default ->
                        throw new AssertionError(
                                "unknown frozen JVM predicate: " + row.frozen().jvm());
                };
        if (!jvmAdmits) return false;
        if (!row.frozen().killSwitch()) return true;
        return !policy.safeMode()
                && !policy.disabledHookIds().contains(row.admission().hookId());
    }

    /**
     * @return an artifact that really exists but is not a reviewed Cubism host, so the digest-keyed rows
     *     compute a digest instead of failing on a missing file
     */
    private Path artifact() {
        if (unreviewed != null) return unreviewed;
        final Path artifact = directory.resolve("unreviewed-host.jar");
        try (OutputStream out = Files.newOutputStream(artifact);
                JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new java.util.jar.JarEntry("unreviewed.txt"));
            jar.write(1);
            jar.closeEntry();
        } catch (final java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
        unreviewed = artifact;
        return artifact;
    }

    private List<Row> rows() {
        return List.of(
                new Row(
                        "reinit-pair-elision",
                        VerifiedReinitPairElisionInstaller.ADMISSION,
                        new Frozen(true, "ANY"),
                        (policy, requested, jvm) ->
                                VerifiedReinitPairElisionInstaller.admitted(artifact(), policy, requested)),
                new Row(
                        "mesa-gl-thread",
                        VerifiedDeferredGlErrorCheckInstaller.ADMISSION,
                        new Frozen(true, "AT_LEAST_17"),
                        (policy, requested, jvm) -> VerifiedDeferredGlErrorCheckInstaller.admitted(
                                UNREVIEWED_DIGEST, policy, requested, jvm)),
                new Row(
                        "float-array-parse-cache",
                        VerifiedFloatArrayParseCacheInstaller.ADMISSION,
                        new Frozen(true, "ANY"),
                        (policy, requested, jvm) ->
                                VerifiedFloatArrayParseCacheInstaller.admitted(artifact(), policy, requested)),
                new Row(
                        "image-archive-reuse",
                        VerifiedImageArchiveReuseInstaller.ADMISSION,
                        new Frozen(true, "ANY"),
                        (policy, requested, jvm) ->
                                VerifiedImageArchiveReuseInstaller.admitted(artifact(), policy, requested)),
                new Row(
                        "incremental-update",
                        VerifiedIncrementalUpdateInstaller.ADMISSION,
                        new Frozen(true, "AT_LEAST_17"),
                        (policy, requested, jvm) ->
                                VerifiedIncrementalUpdateInstaller.admitted(artifact(), policy, requested, jvm)),
                new Row(
                        "input-path-elision",
                        VerifiedInputPathElisionInstaller.ADMISSION,
                        new Frozen(true, "AT_LEAST_17"),
                        (policy, requested, jvm) -> VerifiedInputPathElisionInstaller.admitted(
                                UNREVIEWED_DIGEST, policy, requested, Runtime.version())),
                new Row(
                        "inspector-property-bypass",
                        VerifiedInspectorPropertyBypassInstaller.ADMISSION,
                        new Frozen(false, "AT_LEAST_17"),
                        (policy, requested, jvm) ->
                                VerifiedInspectorPropertyBypassInstaller.admitted(artifact(), requested, jvm)),
                new Row(
                        "line-renderer-upload-split",
                        VerifiedLineRendererUploadSplitInstaller.ADMISSION,
                        new Frozen(true, "ANY"),
                        (policy, requested, jvm) -> {
                            try {
                                return VerifiedLineRendererUploadSplitInstaller.admitted(artifact(), policy, requested);
                            } catch (final java.io.IOException impossible) {
                                throw new AssertionError(impossible);
                            }
                        }),
                new Row(
                        "matrix-scratch",
                        VerifiedMatrixScratchInstaller.ADMISSION,
                        new Frozen(true, "AT_LEAST_17"),
                        (policy, requested, jvm) ->
                                VerifiedMatrixScratchInstaller.admitted(artifact(), policy, requested, jvm)),
                new Row(
                        "model-instance-coalesce",
                        VerifiedModelInstanceCoalesceInstaller.ADMISSION,
                        new Frozen(false, "AT_LEAST_17"),
                        (policy, requested, jvm) ->
                                VerifiedModelInstanceCoalesceInstaller.admitted(artifact(), requested, jvm)),
                new Row(
                        "model-update-skip",
                        VerifiedModelUpdateSkipInstaller.ADMISSION,
                        new Frozen(true, "AT_LEAST_17"),
                        (policy, requested, jvm) ->
                                VerifiedModelUpdateSkipInstaller.admitted(artifact(), policy, requested, jvm)),
                new Row(
                        "mesh-setup-elision",
                        VerifiedSkippedFrameMeshSetupElisionInstaller.ADMISSION,
                        new Frozen(true, "AT_LEAST_17"),
                        (policy, requested, jvm) -> VerifiedSkippedFrameMeshSetupElisionInstaller.admitted(
                                UNREVIEWED_DIGEST, policy, requested, jvm)),
                new Row(
                        "upload-elision",
                        VerifiedSkippedFrameUploadElisionInstaller.ADMISSION,
                        new Frozen(true, "AT_LEAST_17"),
                        (policy, requested, jvm) -> VerifiedSkippedFrameUploadElisionInstaller.admitted(
                                UNREVIEWED_DIGEST, policy, requested, jvm)),
                new Row(
                        "sorting-info-cache",
                        VerifiedSortingInfoCacheInstaller.ADMISSION,
                        new Frozen(true, "ANY"),
                        (policy, requested, jvm) -> {
                            try {
                                return VerifiedSortingInfoCacheInstaller.admitted(artifact(), policy, requested);
                            } catch (final java.io.IOException impossible) {
                                throw new AssertionError(impossible);
                            }
                        }),
                new Row(
                        "texture-upload-preparation",
                        VerifiedTextureUploadPreparationInstaller.ADMISSION,
                        new Frozen(true, "EXACTLY_17"),
                        (policy, requested, jvm) ->
                                VerifiedTextureUploadPreparationInstaller.admitted(artifact(), policy, requested, jvm)),
                new Row(
                        "uniform-location-cache",
                        VerifiedUniformLocationInstaller.ADMISSION,
                        new Frozen(true, "AT_LEAST_17"),
                        (policy, requested, jvm) ->
                                VerifiedUniformLocationInstaller.admitted(artifact(), policy, requested, jvm)),
                new Row(
                        "warp-position-projection",
                        VerifiedWarpPositionProjectionInstaller.ADMISSION,
                        new Frozen(true, "EXACTLY_17"),
                        (policy, requested, jvm) ->
                                VerifiedWarpPositionProjectionInstaller.admitted(artifact(), policy, requested, jvm)));
    }

    private static RuntimeStartupConfig open() {
        return new RuntimeStartupConfig(false, false, false, false);
    }

    private static RuntimeStartupConfig safeMode() {
        return new RuntimeStartupConfig(true, false, false, false);
    }

    private static RuntimeStartupConfig killSwitched(final String hookId) {
        return new RuntimeStartupConfig(false, false, false, false, false, false, false, Set.of(hookId));
    }

    private static String describe(final RuntimeStartupConfig policy) {
        return "safeMode=" + policy.safeMode() + " disabled=" + policy.disabledHookIds();
    }
}
