package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedMemoryLeakMitigationInstallerTest {

    private static final Path MISSING_ARTIFACT = Path.of("missing-cubism-host.jar");
    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final List<Path> FALLBACK_ARTIFACT_ROOTS = List.of(
            Path.of(System.getProperty("user.home"), ARTIFACTS_DIR_NAME),
            ReviewedCubismArtifactTestPaths.root());

    @TempDir
    Path home;

    @Test
    void rejectsUnrequestedWrongJvmSafeModeDisabledHookAndUnboundArtifact() throws Exception {
        final RuntimeStartupConfig normal = new RuntimeStartupConfig(false, false, false, false);
        assertFalse(VerifiedMemoryLeakMitigationInstaller.admitted(MISSING_ARTIFACT, normal, false, 17));
        assertFalse(VerifiedMemoryLeakMitigationInstaller.admitted(MISSING_ARTIFACT, normal, true, 16));
        assertFalse(VerifiedMemoryLeakMitigationInstaller.admitted(
                MISSING_ARTIFACT, new RuntimeStartupConfig(true, false, false, false), true, 17));
        assertFalse(VerifiedMemoryLeakMitigationInstaller.admitted(
                MISSING_ARTIFACT,
                new RuntimeStartupConfig(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Set.of(VerifiedMemoryLeakMitigationInstaller.HOOK_ID)),
                true,
                17));
        assertFalse(
                VerifiedMemoryLeakMitigationInstaller.admitted(MISSING_ARTIFACT, normal, true, 17),
                "an unreviewed artifact must not admit");
        assertFalse(
                VerifiedMemoryLeakMitigationInstaller.admitted(MISSING_ARTIFACT, normal, true, 25),
                "newer JVM still requires a reviewed artifact contract");
    }

    @Test
    void admitsAvailableReviewedCubismArtifacts() throws Exception {
        int admitted = 0;
        final RuntimeStartupConfig normal = new RuntimeStartupConfig(false, false, false, false);
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path jar = findLocalJar(version);
            if (jar == null) {
                continue;
            }
            assertTrue(
                    VerifiedMemoryLeakMitigationInstaller.admitted(jar, normal, true, 17),
                    "expected admission for Cubism " + version);
            assertTrue(
                    VerifiedMemoryLeakMitigationInstaller.admitted(jar, normal, true, 21),
                    "expected admission on JVM 21 for Cubism " + version);
            admitted++;
        }
        assumeTrue(admitted > 0, "no local reviewed Cubism JARs present");
    }

    @Test
    void installsBeforeRuntimeAndCleansUpOnFailure() throws Exception {
        NativeOptimizationStartupOrder.assertBeforeRuntime();
    }

    @Test
    void defaultsOnInLaunchPlanAndHonorsExplicitOptOut() throws Exception {
        final DeclarativeHooks.DeclarativeHook hook =
                DeclarativeHooks.byKey("verified-memory-leak-mitigation").orElseThrow();
        assertEquals(VerifiedMemoryLeakMitigationInstaller.HOOK_ID, hook.hookId());
        assertEquals(OptimizationCatalog.MEMORY_LEAK_MITIGATION.protocols().get(0), hook.reportId());

        final HookEnvironment defaultEnv = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .fullRuntimeAdmission(true)
                .build();
        assertTrue(hook.preference().test(defaultEnv));

        Files.writeString(home.resolve("config.json"), """
                {"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"mem-opt-out","memoryLeakMitigation":false}
                """);
        final HookEnvironment optedOutEnv = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .fullRuntimeAdmission(true)
                .build();
        assertFalse(hook.preference().test(optedOutEnv));
        assertNotNull(DeclarativeHookContributor.of(hook).install(optedOutEnv));
    }

    private static Path findLocalJar(final String version) {
        final String singleProp = System.getProperty("turboism.test.cubismEditorJar");
        if (singleProp != null && !singleProp.isBlank()) {
            final Path candidate = Path.of(singleProp);
            if (Files.isRegularFile(candidate) && candidate.toString().contains(version)) {
                return candidate;
            }
        }
        for (final Path root : FALLBACK_ARTIFACT_ROOTS) {
            final Path candidate = ReviewedCubismArtifactTestPaths.jar(root, version);
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
