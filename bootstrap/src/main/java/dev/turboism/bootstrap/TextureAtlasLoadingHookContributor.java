package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.TextureAtlasTriangleSetTransformer;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLoadingTransformer;
import dev.turboism.config.optimization.OptimizationCatalog;

/** Installs progress feedback before Cubism defines its texture atlas model. */
final class TextureAtlasLoadingHookContributor implements HookContributor {
    private static final String ID = "TURBOISM_TEXTURE_ATLAS_LOADING";
    private static final String POLICY_ID = "cubism.texture-atlas.loading";

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
        return environment.startupPolicy() != null
                && environment.startupPolicy().hookEnabled(POLICY_ID);
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
        final var transformer = new TextureAtlasLoadingTransformer(indexScopedEdges && !hasGlobalHash);
        instrumentation.addTransformer(transformer);
        if (hasGlobalHash) return () -> instrumentation.removeTransformer(transformer);
        final var triangleSet = new TextureAtlasTriangleSetTransformer(indexScopedEdges);
        try {
            instrumentation.addTransformer(triangleSet);
        } catch (RuntimeException failure) {
            instrumentation.removeTransformer(transformer);
            throw failure;
        }
        return () -> {
            instrumentation.removeTransformer(triangleSet);
            instrumentation.removeTransformer(transformer);
        };
    }
}
