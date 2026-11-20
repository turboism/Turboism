package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.TriangulationSpatialIndexTransformer;
import dev.turboism.config.NativeOptimizationDiagnostics;
import dev.turboism.config.optimization.OptimizationCatalog;

/** Installs spatial-index triangulation acceleration before Cubism loads its mesh classes. */
final class TriangulationSpatialIndexHookContributor implements ReportedHookContributor {

    static final String HOOK_POLICY_ID = "cubism.mesh.triangulation-spatial-index";
    private static final String ID = "TURBOISM_TRIANGULATION_SPATIAL_INDEX";
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
        return HOOK_POLICY_ID;
    }

    @Override
    public Phase phase() {
        return Phase.PREMAIN;
    }

    @Override
    public boolean closesOnProcessExit() {
        return true;
    }

    @Override
    public boolean admitted(final HookEnvironment environment) {
        final boolean requested = environment
                .plan()
                .choice(OptimizationCatalog.TRIANGULATION_SPATIAL_INDEX)
                .enabled();
        final boolean admitted = environment.startupPolicy() != null
                && environment.startupPolicy().hookEnabled(HOOK_POLICY_ID)
                && requested;
        if (!admitted) {
            NativeOptimizationDiagnostics.installed(
                    ID, NOT_INSTALLED, requested ? POLICY_DISABLED : PREFERENCE_DISABLED);
        }
        return admitted;
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) {
        final var instrumentation = environment.instrumentation();
        final var transformer = new TriangulationSpatialIndexTransformer();
        instrumentation.addTransformer(transformer);
        NativeOptimizationDiagnostics.installed(ID, INSTALLED, INSTALLED);
        return () -> {
            try {
                instrumentation.removeTransformer(transformer);
            } finally {
                NativeOptimizationDiagnostics.installed(ID, NOT_INSTALLED, CLOSED);
            }
        };
    }
}
