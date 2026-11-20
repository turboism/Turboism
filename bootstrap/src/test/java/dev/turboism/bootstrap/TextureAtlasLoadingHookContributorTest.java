package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.mesh.TextureAtlasTriangleSetTransformer;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLoadingTransformer;
import dev.turboism.config.NativeOptimizationDiagnostics;
import dev.turboism.config.RuntimeConfigRepository;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regressions for atlas-loading scoped triangulation and spatial-index contributor admission. */
final class TextureAtlasLoadingHookContributorTest {

    @TempDir
    Path home;

    @AfterEach
    void resetDiagnostics() {
        NativeOptimizationDiagnostics.resetForTesting();
    }

    @Test
    void globalMeshHashDoesNotDropScopedAtlasTriangleSetWhenEdgeIndexIsDisabled() throws Exception {
        new RuntimeConfigRepository(home, ignored -> {}).update(root -> {
            root.put(OptimizationCatalog.ATLAS_LOADING.key(), true);
            root.put(OptimizationCatalog.MESH_TRIANGULATION_HASH.key(), true);
            root.put(OptimizationCatalog.TRIANGULATION_EDGE_INDEX.key(), false);
            return root;
        });
        final List<ClassFileTransformer> added = new ArrayList<>();
        final List<ClassFileTransformer> removed = new ArrayList<>();
        final Instrumentation instrumentation = recordingInstrumentation(added, removed);
        final HookEnvironment environment = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(new RuntimeStartupConfig(false, false, false, false, false, false, false, Set.of()))
                .instrumentation(instrumentation)
                .build();

        final TextureAtlasLoadingHookContributor contributor = new TextureAtlasLoadingHookContributor();
        assertTrue(contributor.admitted(environment));
        try (AutoCloseable handle = contributor.install(environment)) {
            assertEquals(2, added.size());
            assertTrue(added.stream().anyMatch(TextureAtlasLoadingTransformer.class::isInstance));
            assertTrue(added.stream().anyMatch(TextureAtlasTriangleSetTransformer.class::isInstance));
        }
        assertEquals(2, removed.size());
    }

    @Test
    void disabledAtlasLoadingDoesNotInstallScopedTriangleSet() throws Exception {
        new RuntimeConfigRepository(home, ignored -> {}).update(root -> {
            root.put(OptimizationCatalog.ATLAS_LOADING.key(), false);
            return root;
        });
        final List<ClassFileTransformer> added = new ArrayList<>();
        final List<ClassFileTransformer> removed = new ArrayList<>();
        final Instrumentation instrumentation = recordingInstrumentation(added, removed);
        final HookEnvironment environment = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(new RuntimeStartupConfig(false, false, false, false, false, false, false, Set.of()))
                .instrumentation(instrumentation)
                .build();

        final TextureAtlasLoadingHookContributor contributor = new TextureAtlasLoadingHookContributor();
        assertTrue(contributor.admitted(environment));
        try (AutoCloseable handle = contributor.install(environment)) {
            assertEquals(1, added.size());
            assertTrue(added.get(0) instanceof TextureAtlasLoadingTransformer);
            assertEquals(
                    "not-installed",
                    NativeOptimizationDiagnostics.snapshot()
                            .get(OptimizationCatalog.ATLAS_LOADING)
                            .state());
        }
        assertEquals(1, removed.size());
    }

    @Test
    void spatialIndexContributorFollowsPreferenceAndPolicyAndClosesCleanly() throws Exception {
        final TriangulationSpatialIndexHookContributor contributor = new TriangulationSpatialIndexHookContributor();
        final HookEnvironment defaultEnv = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(new RuntimeStartupConfig(false, false, false, false, false, false, false, Set.of()))
                .build();
        assertTrue(contributor.admitted(defaultEnv));
        try (AutoCloseable handle = contributor.install(defaultEnv)) {
            assertEquals(
                    "awaiting-transformer",
                    NativeOptimizationDiagnostics.snapshot()
                            .get(OptimizationCatalog.TRIANGULATION_SPATIAL_INDEX)
                            .reason());
        }
        assertEquals(
                "closed",
                NativeOptimizationDiagnostics.snapshot()
                        .get(OptimizationCatalog.TRIANGULATION_SPATIAL_INDEX)
                        .reason());

        final HookEnvironment policyDisabled = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(new RuntimeStartupConfig(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Set.of(TriangulationSpatialIndexHookContributor.HOOK_POLICY_ID)))
                .build();
        assertFalse(contributor.admitted(policyDisabled));
        assertEquals(
                "policy-disabled",
                NativeOptimizationDiagnostics.snapshot()
                        .get(OptimizationCatalog.TRIANGULATION_SPATIAL_INDEX)
                        .reason());

        new RuntimeConfigRepository(home, ignored -> {}).update(root -> {
            root.put(OptimizationCatalog.TRIANGULATION_SPATIAL_INDEX.key(), false);
            return root;
        });
        final HookEnvironment optOut = HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(new RuntimeStartupConfig(false, false, false, false, false, false, false, Set.of()))
                .build();
        assertFalse(contributor.admitted(optOut));
        assertEquals(
                "preference-disabled",
                NativeOptimizationDiagnostics.snapshot()
                        .get(OptimizationCatalog.TRIANGULATION_SPATIAL_INDEX)
                        .reason());
    }

    private static Instrumentation recordingInstrumentation(
            final List<ClassFileTransformer> added, final List<ClassFileTransformer> removed) {
        return (Instrumentation) Proxy.newProxyInstance(
                TextureAtlasLoadingHookContributorTest.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "addTransformer" -> {
                        added.add((ClassFileTransformer) args[0]);
                        yield null;
                    }
                    case "removeTransformer" -> {
                        removed.add((ClassFileTransformer) args[0]);
                        yield Boolean.TRUE;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
