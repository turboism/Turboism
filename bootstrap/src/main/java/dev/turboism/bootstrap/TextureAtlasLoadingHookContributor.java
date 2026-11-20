package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.TextureAtlasTriangleSetTransformer;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLoadingTransformer;
import dev.turboism.config.NativeOptimizationDiagnostics;
import dev.turboism.config.optimization.OptimizationCatalog;

/** Installs progress feedback before Cubism defines its texture atlas model. */
final class TextureAtlasLoadingHookContributor implements ReportedHookContributor {
    private static final String ID = "TURBOISM_TEXTURE_ATLAS_LOADING";
    private static final String POLICY_ID = "cubism.texture-atlas.loading";
    private static final String INSTALLED = "installed";
    private static final String NOT_INSTALLED = "not-installed";
    private static final String PREFERENCE_DISABLED = "preference-disabled";
    private static final String POLICY_DISABLED = "policy-disabled";
    private static final String CLOSED = "closed";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String policyId() {
        return POLICY_ID;
    }

    @Override
    public Phase phase() {
        return Phase.PREMAIN;
    }

    @Override
    public boolean admitted(final HookEnvironment environment) {
        final boolean allowed = environment.startupPolicy() != null
                && environment.startupPolicy().hookEnabled(POLICY_ID);
        if (!allowed) NativeOptimizationDiagnostics.installed(ID, NOT_INSTALLED, POLICY_DISABLED);
        return allowed;
    }

    @Override
    public boolean closesOnProcessExit() {
        return true;
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) {
        final var instrumentation = environment.instrumentation();
        final var policy = environment.startupPolicy();
        final boolean hasGlobalHash = policy.hookEnabled(MeshTriangulationHashHookContributor.HOOK_POLICY_ID)
                && environment
                        .plan()
                        .choice(OptimizationCatalog.MESH_TRIANGULATION_HASH)
                        .enabled();
        final boolean indexScopedEdges = policy.hookEnabled(TriangulationEdgeIndexHookContributor.HOOK_POLICY_ID)
                && !environment
                        .plan()
                        .choice(OptimizationCatalog.TRIANGULATION_EDGE_INDEX)
                        .enabled();
        final boolean requested = environment.plan().admitted(OptimizationCatalog.ATLAS_LOADING);
        final var transformer = new TextureAtlasLoadingTransformer(requested);
        instrumentation.addTransformer(transformer);
        if (!requested || (hasGlobalHash && !indexScopedEdges)) {
            NativeOptimizationDiagnostics.installed(
                    ID, requested ? INSTALLED : NOT_INSTALLED, requested ? INSTALLED : PREFERENCE_DISABLED);
            return () -> {
                instrumentation.removeTransformer(transformer);
                NativeOptimizationDiagnostics.installed(ID, NOT_INSTALLED, CLOSED);
            };
        }
        final var triangleSet = new TextureAtlasTriangleSetTransformer(indexScopedEdges, hasGlobalHash);
        try {
            instrumentation.addTransformer(triangleSet);
        } catch (RuntimeException failure) {
            instrumentation.removeTransformer(transformer);
            throw failure;
        }
        NativeOptimizationDiagnostics.installed(ID, INSTALLED, INSTALLED);
        return () -> {
            try {
                instrumentation.removeTransformer(triangleSet);
                instrumentation.removeTransformer(transformer);
            } finally {
                NativeOptimizationDiagnostics.installed(ID, NOT_INSTALLED, CLOSED);
            }
        };
    }
}
