package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.VerifiedMeshTriangulationHashInstaller;
import dev.turboism.config.MeshTriangulationPreference;
import dev.turboism.runtime.log.RuntimeDiagnostics;

/**
 * Declarative contributor for the verified mesh triangulation hash fix. The
 * transformer must observe the host's mesh classes before they load, so it
 * installs in {@link Phase#PREMAIN} and closes on the process-exit path.
 */
final class MeshTriangulationHashHookContributor implements HookContributor {

    static final String HOOK_POLICY_ID = "cubism.mesh.triangulation-hash";

    @Override
    public String id() {
        return "TURBOISM_MESH_TRIANGULATION_HASH";
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

    static boolean enabled(
            final dev.turboism.config.RuntimeStartupConfig policy, final java.nio.file.Path turboismHome) {
        return policy != null && policy.hookEnabled(HOOK_POLICY_ID) && MeshTriangulationPreference.read(turboismHome);
    }

    @Override
    public boolean admitted(final HookEnvironment environment) {
        final boolean requested = environment
                .optimizationConfig()
                .choice(dev.turboism.config.NativeOptimizationConfig.Option.MESH_TRIANGULATION_HASH)
                .enabled();
        final boolean admitted = environment.startupPolicy() != null
                && environment.startupPolicy().hookEnabled(HOOK_POLICY_ID)
                && requested;
        if (!admitted)
            dev.turboism.config.NativeOptimizationDiagnostics.installed(
                    id(), "not-installed", requested ? "policy-disabled" : "preference-disabled");
        return admitted;
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) throws Exception {
        try {
            final VerifiedMeshTriangulationHashInstaller.Installation installation =
                    VerifiedMeshTriangulationHashInstaller.install(
                            environment.instrumentation(),
                            code -> RuntimeDiagnostics.debug("mesh-triangulation-hash", code));
            RuntimeDiagnostics.debug("bootstrap", "Mesh triangulation hash status=" + installation.status());
            dev.turboism.config.NativeOptimizationDiagnostics.installed(
                    id(),
                    "INSTALLED".equals(installation.status().name()) ? "installed" : "not-installed",
                    installation.status().name());
            return () -> {
                try {
                    installation.close();
                } finally {
                    dev.turboism.config.NativeOptimizationDiagnostics.installed(id(), "not-installed", "closed");
                }
            };
        } catch (final Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            dev.turboism.config.NativeOptimizationDiagnostics.installed(
                    id(), "failed", failure.getClass().getSimpleName());
            RuntimeDiagnostics.error("mesh-triangulation-hash", "Triangulation hash fix disabled safely", failure);
            return () -> {};
        }
    }
}
