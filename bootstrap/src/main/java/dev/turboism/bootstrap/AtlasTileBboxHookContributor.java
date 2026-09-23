package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.textureatlas.image.VerifiedAtlasTileBboxInstaller;
import dev.turboism.config.AtlasTileBboxPreference;
import dev.turboism.runtime.log.RuntimeDiagnostics;

/**
 * Declarative contributor for the verified atlas tile-bbox optimization. Like
 * the other atlas premain fixes it must observe the host's textureatlas
 * classes before they load, so it installs in {@link Phase#PREMAIN} and closes
 * on the process-exit path.
 */
final class AtlasTileBboxHookContributor implements HookContributor {

    static final String HOOK_POLICY_ID = "cubism.textureatlas.tile-bbox";

    @Override public String id() {
        return "TURBOISM_ATLAS_TILE_BBOX";
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
            && AtlasTileBboxPreference.read(turboismHome);
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return enabled(environment.startupPolicy(), environment.options().home());
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        try {
            final VerifiedAtlasTileBboxInstaller.Installation installation =
                VerifiedAtlasTileBboxInstaller.install(
                    environment.instrumentation(),
                    // Premain runs before the diagnostics sink exists; the console is the
                    // only place the admission verdict survives on a real host.
                    code -> System.err.println("[turboism] atlas-tile-bbox " + code)
                );
            RuntimeDiagnostics.debug(
                "bootstrap",
                "Atlas tile-bbox status=" + installation.status()
                    + ", transformOutcome=" + installation.transformOutcome()
            );
            return installation;
        } catch (final Throwable failure) {
            RuntimeDiagnostics.error(
                "atlas-tile-bbox",
                "Atlas tile-bbox optimization disabled safely",
                failure
            );
            return () -> { };
        }
    }
}
