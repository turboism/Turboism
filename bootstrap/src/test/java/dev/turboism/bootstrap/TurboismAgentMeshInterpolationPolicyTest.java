package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.config.NativeOptimizationConfig;
import dev.turboism.config.NativeOptimizationConfig.Option;
import dev.turboism.config.NativeOptimizationDiagnostics;
import dev.turboism.config.RuntimeStartupConfig;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TurboismAgentMeshInterpolationPolicyTest {
    @TempDir
    Path home;

    @AfterEach
    void cleanup() {
        System.clearProperty("turboism.meshInterpolation.enabled");
        System.clearProperty("turboism.optimization.meshInterpolation");
        System.clearProperty("turboism.modelSerialization.enabled");
        NativeOptimizationDiagnostics.resetForTesting();
    }

    static RuntimeStartupConfig policy(boolean safe, Set<String> disabled) {
        return new RuntimeStartupConfig(safe, false, false, false, false, false, false, disabled);
    }

    @Test
    void candidateRequiresExplicitRequest() {
        assertFalse(TurboismAgent.meshInterpolationEnabled(policy(false, Set.of()), false));
        assertTrue(TurboismAgent.meshInterpolationEnabled(policy(false, Set.of()), true));
    }

    @Test
    void verifiedProductInterpolationDefaultsOffWithExplicitOptIn() {
        assertFalse(TurboismAgent.meshInterpolationRequested(null));
        assertTrue(TurboismAgent.meshInterpolationRequested("true"));
        assertFalse(TurboismAgent.meshInterpolationRequested("false"));
        assertFalse(TurboismAgent.meshInterpolationRequested("invalid"));
    }

    @Test
    void modelSerializationHasIndependentPolicyAndExplicitDisable() {
        assertTrue(TurboismAgent.modelSerializationEnabled(policy(false, Set.of()), true));
        assertFalse(TurboismAgent.modelSerializationEnabled(policy(true, Set.of()), true));
        assertFalse(TurboismAgent.modelSerializationEnabled(policy(false, Set.of("cubism.model.serialization")), true));
        assertFalse(TurboismAgent.modelSerializationEnabled(policy(false, Set.of()), false));
        assertFalse(TurboismAgent.modelSerializationEnabled(null, true));
    }

    @Test
    void safeModeDisabledHookAndMissingPolicyDecline() {
        assertFalse(TurboismAgent.meshInterpolationEnabled(policy(true, Set.of()), true));
        assertFalse(TurboismAgent.meshInterpolationEnabled(policy(false, Set.of("cubism.mesh.interpolation")), true));
        assertFalse(TurboismAgent.meshInterpolationEnabled(null, true));
    }

    @Test
    void contributorAdmissionReflectsGlobalConfigAndJvmOverrides() throws Exception {
        final MeshInterpolationHookContributor contributor = new MeshInterpolationHookContributor();
        assertEquals("TURBOISM_MESH_INTERPOLATION", contributor.id());
        assertEquals("cubism.mesh.interpolation", contributor.policyId());
        assertTrue(contributor.closesOnProcessExit());

        // 1. Default config: not requested, admitted is false, diagnostic recorded
        HookEnvironment defaultEnv = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(policy(false, Set.of()))
                .build();
        assertFalse(contributor.admitted(defaultEnv));
        var defaultDiag = NativeOptimizationDiagnostics.snapshot().get(Option.MESH_INTERPOLATION);
        assertNotNull(defaultDiag);
        assertEquals("not-installed", defaultDiag.state());
        assertEquals("preference-disabled", defaultDiag.reason());

        // 2. Global config enables meshInterpolation
        Files.writeString(
                home.resolve("config.json"),
                "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,\"worktreeId\":\"test-worktree\",\"launcher\":{\"meshInterpolation\":true}}");
        HookEnvironment configEnv = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(policy(false, Set.of()))
                .build();
        assertTrue(contributor.admitted(configEnv));

        // 3. Legacy JVM override turboism.meshInterpolation.enabled
        Files.writeString(
                home.resolve("config.json"),
                "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,\"worktreeId\":\"test-worktree\",\"launcher\":{\"meshInterpolation\":false}}");
        System.setProperty("turboism.meshInterpolation.enabled", "true");
        HookEnvironment legacyOverrideEnv = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(policy(false, Set.of()))
                .build();
        assertTrue(contributor.admitted(legacyOverrideEnv));

        // 4. Modern override turboism.optimization.meshInterpolation takes precedence over legacy
        System.setProperty("turboism.optimization.meshInterpolation", "false");
        HookEnvironment modernOverrideEnv = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(policy(false, Set.of()))
                .build();
        assertFalse(contributor.admitted(modernOverrideEnv));

        // 5. Safe mode declines admission even when requested
        System.setProperty("turboism.optimization.meshInterpolation", "true");
        HookEnvironment safeEnv = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(policy(true, Set.of()))
                .build();
        assertFalse(contributor.admitted(safeEnv));

        // 6. Policy disabled hook declines admission even when requested
        HookEnvironment disabledEnv = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(policy(false, Set.of("cubism.mesh.interpolation")))
                .build();
        assertFalse(contributor.admitted(disabledEnv));

        // 7. Serialization-only admission does not mark interpolation as installed
        System.clearProperty("turboism.optimization.meshInterpolation");
        System.clearProperty("turboism.meshInterpolation.enabled");
        System.setProperty("turboism.modelSerialization.enabled", "true");
        Files.writeString(
                home.resolve("config.json"),
                "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,\"worktreeId\":\"test-worktree\",\"launcher\":{\"meshInterpolation\":false}}");
        HookEnvironment serializationOnlyEnv = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(policy(false, Set.of()))
                .build();
        assertTrue(contributor.admitted(serializationOnlyEnv), "admitted because serialization is enabled");
        var serializationDiag = NativeOptimizationDiagnostics.snapshot().get(Option.MESH_INTERPOLATION);
        assertNotNull(serializationDiag);
        assertEquals("not-installed", serializationDiag.state());
        assertEquals("preference-disabled", serializationDiag.reason());
    }

    private static Instrumentation mockInstrumentation(AtomicReference<ClassFileTransformer> capturedRef) {
        return (Instrumentation) Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> {
                    if ("getAllLoadedClasses".equals(method.getName())) {
                        return new Class<?>[0];
                    }
                    if ("addTransformer".equals(method.getName())) {
                        capturedRef.set((ClassFileTransformer) args[0]);
                        return null;
                    }
                    if ("removeTransformer".equals(method.getName())) {
                        capturedRef.set(null);
                        return true;
                    }
                    return null;
                });
    }

    @Test
    void realCallbackLifecycleWithMockInstrumentation() throws Exception {
        final MeshInterpolationHookContributor contributor = new MeshInterpolationHookContributor();
        Files.writeString(
                home.resolve("config.json"),
                "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,\"worktreeId\":\"test-worktree\",\"launcher\":{\"meshInterpolation\":true}}");
        NativeOptimizationDiagnostics.startup(
                NativeOptimizationConfig.load(home, ignored -> {}).settings());

        final AtomicReference<ClassFileTransformer> capturedRef = new AtomicReference<>();
        final HookEnvironment env = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(policy(false, Set.of()))
                .instrumentation(mockInstrumentation(capturedRef))
                .build();

        try (var handle = contributor.install(env)) {
            assertNotNull(handle, "contributor handle must be returned");
            ClassFileTransformer transformer = capturedRef.get();
            assertNotNull(transformer, "transformer must be registered in instrumentation");

            // 1. Immediately after registration before class loads: pending waiting-class-load
            var pendingStatus = NativeOptimizationDiagnostics.snapshot().get(Option.MESH_INTERPOLATION);
            assertNotNull(pendingStatus);
            assertEquals("pending", pendingStatus.state());
            assertEquals("waiting-class-load", pendingStatus.reason());

            // 2. Class loaded with invalid definition/bytes -> triggers real callback DECLINED_DEFINITION
            transformer.transform(
                    getClass().getClassLoader(),
                    "com/live2d/cubism/doc/model/interpolator/extendedInterpolation/CExtendedInterpolationExtension",
                    null,
                    null,
                    null);
            var declinedStatus = NativeOptimizationDiagnostics.snapshot().get(Option.MESH_INTERPOLATION);
            assertEquals("not-installed", declinedStatus.state());
            assertEquals("DECLINED_DEFINITION", declinedStatus.reason());
        }

        // 3. After handle close -> closed
        var closedStatus = NativeOptimizationDiagnostics.snapshot().get(Option.MESH_INTERPOLATION);
        assertEquals("not-installed", closedStatus.state());
        assertEquals("closed", closedStatus.reason());
    }

    @Test
    void serializationOnlyDoesNotPolluteInterpolationDiagnostics() throws Exception {
        final MeshInterpolationHookContributor contributor = new MeshInterpolationHookContributor();
        Files.writeString(
                home.resolve("config.json"),
                "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,\"worktreeId\":\"test-worktree\",\"launcher\":{\"meshInterpolation\":false}}");
        System.setProperty("turboism.modelSerialization.enabled", "true");
        NativeOptimizationDiagnostics.startup(
                NativeOptimizationConfig.load(home, ignored -> {}).settings());

        final AtomicReference<ClassFileTransformer> capturedRef = new AtomicReference<>();
        final HookEnvironment env = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(policy(false, Set.of()))
                .instrumentation(mockInstrumentation(capturedRef))
                .build();

        try (var handle = contributor.install(env)) {
            assertNotNull(handle, "serialization handle must install");
            ClassFileTransformer transformer = capturedRef.get();
            assertNotNull(transformer, "serialization transformer registered");

            // Diagnostic for mesh interpolation must remain preference-disabled, NOT pending or installed!
            var status = NativeOptimizationDiagnostics.snapshot().get(Option.MESH_INTERPOLATION);
            assertNotNull(status);
            assertEquals("not-installed", status.state());
            assertEquals("preference-disabled", status.reason());

            // Even if a serialization class or unknown class is transformed:
            transformer.transform(getClass().getClassLoader(), "any/RandomClass", null, null, null);
            var statusAfter = NativeOptimizationDiagnostics.snapshot().get(Option.MESH_INTERPOLATION);
            assertEquals("not-installed", statusAfter.state());
            assertEquals("preference-disabled", statusAfter.reason());
        }
    }

    @Test
    void multiPhaseEnvironmentSharesStartupSnapshotAndPreservesObservedStatus() throws Exception {
        final MeshInterpolationHookContributor contributor = new MeshInterpolationHookContributor();
        Files.writeString(
                home.resolve("config.json"),
                "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,\"worktreeId\":\"test-worktree\",\"launcher\":{\"meshInterpolation\":true}}");

        final AtomicReference<ClassFileTransformer> capturedRef = new AtomicReference<>();
        final HookEnvironment premainEnv = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(policy(false, Set.of()))
                .instrumentation(mockInstrumentation(capturedRef))
                .build();

        final dev.turboism.config.NativeOptimizationConfig launchConfig = premainEnv.optimizationConfig();

        // 1. In PREMAIN phase: contributor installs and class is transformed / patched
        try (var handle = contributor.install(premainEnv)) {
            assertNotNull(handle);
            NativeOptimizationDiagnostics.installed("TURBOISM_MESH_INTERPOLATION", "installed", "PATCHED_5304");
            var premainStatus = NativeOptimizationDiagnostics.snapshot().get(Option.MESH_INTERPOLATION);
            assertEquals("installed", premainStatus.state());
            assertEquals("PATCHED_5304", premainStatus.reason());

            // 2. Later phase (e.g. HOST_RESOLVED / RUNTIME_READY) receives the explicitly shared launchConfig
            final HookEnvironment hostResolvedEnv = HookEnvironment.builder()
                    .options(AgentOptions.parse("home=" + home, home))
                    .startupPolicy(policy(false, Set.of()))
                    .instrumentation(mockInstrumentation(capturedRef))
                    .optimizationConfig(launchConfig)
                    .build();

            // When new phase environment resolves its optimizationConfig, it reuses the explicitly shared launchConfig
            var nextConfig = hostResolvedEnv.optimizationConfig();
            assertSame(launchConfig, nextConfig);
            assertTrue(nextConfig.choice(Option.MESH_INTERPOLATION).enabled());

            // Because launchConfig was explicitly shared, premain observed status is preserved
            var preservedStatus = NativeOptimizationDiagnostics.snapshot().get(Option.MESH_INTERPOLATION);
            assertEquals(
                    "installed",
                    preservedStatus.state(),
                    "premain observed status must not be wiped out by next phase");
            assertEquals("PATCHED_5304", preservedStatus.reason());
        }
    }
}
