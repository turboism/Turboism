package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.VerifiedTriangulationEdgeIndexInstaller;
import dev.turboism.config.TriangulationEdgeIndexPreference;
import dev.turboism.runtime.log.RuntimeDiagnostics;

/**
 * Declarative contributor for the verified triangulation edge-index weave. The
 * transformer must observe the host's {@code TriangleList} before it loads, so
 * it installs in {@link Phase#PREMAIN} and closes on the process-exit path.
 */
final class TriangulationEdgeIndexHookContributor implements HookContributor {

    static final String HOOK_POLICY_ID = "cubism.mesh.triangulation-edge-index";

    @Override
    public String id() {
        return "TURBOISM_TRIANGULATION_EDGE_INDEX";
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
        return policy != null
                && policy.hookEnabled(HOOK_POLICY_ID)
                && TriangulationEdgeIndexPreference.read(turboismHome);
    }

    @Override
    public boolean admitted(final HookEnvironment environment) {
        return enabled(environment.startupPolicy(), environment.options().home());
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) throws Exception {
        try {
            final VerifiedTriangulationEdgeIndexInstaller.Installation installation =
                    VerifiedTriangulationEdgeIndexInstaller.install(environment.instrumentation(), code -> {
                        if (code.startsWith("TRIANGULATION_MEMBERSHIP_PATCHED ")
                                || code.startsWith("TRIANGULATION_FRESH_EDGE_PATCHED ")
                                || code.startsWith("TRIANGULATION_LAZY_EDGE_")) {
                            RuntimeDiagnostics.info("triangulation-edge-index", code);
                        } else {
                            RuntimeDiagnostics.debug("triangulation-edge-index", code);
                        }
                    });
            RuntimeDiagnostics.debug("bootstrap", "Triangulation edge index status=" + installation.status());
            return installation;
        } catch (final Throwable failure) {
            RuntimeDiagnostics.error("triangulation-edge-index", "Triangulation edge index disabled safely", failure);
            return () -> {};
        }
    }
}
