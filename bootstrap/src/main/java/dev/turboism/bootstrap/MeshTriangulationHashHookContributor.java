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

    @Override public String id() {
        return "TURBOISM_MESH_TRIANGULATION_HASH";
    }

    @Override public Phase phase() {
        return Phase.PREMAIN;
    }

    @Override public boolean closesOnProcessExit() {
        return true;
    }

    static boolean enabled(
        final dev.turboism.config.RuntimeStartupConfig policy,
        final java.nio.file.Path turboismHome
    ) {
        return policy != null
            && policy.hookEnabled(HOOK_POLICY_ID)
            && MeshTriangulationPreference.read(turboismHome);
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return enabled(environment.startupPolicy(), environment.options().home());
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        try {
            final VerifiedMeshTriangulationHashInstaller.Installation installation =
                VerifiedMeshTriangulationHashInstaller.install(
                    environment.instrumentation(),
                    code -> RuntimeDiagnostics.debug("mesh-triangulation-hash", code)
                );
            RuntimeDiagnostics.debug(
                "bootstrap",
                "Mesh triangulation hash status=" + installation.status()
            );
            return installation;
        } catch (final Throwable failure) {
            RuntimeDiagnostics.error(
                "mesh-triangulation-hash",
                "Triangulation hash fix disabled safely",
                failure
            );
            return () -> { };
        }
    }
}
