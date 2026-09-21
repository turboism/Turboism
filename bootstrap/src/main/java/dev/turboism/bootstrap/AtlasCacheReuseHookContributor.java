package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.textureatlas.cache.VerifiedAtlasCacheReuseInstaller;
import dev.turboism.config.AtlasCacheReusePreference;
import dev.turboism.runtime.log.RuntimeDiagnostics;

/**
 * Declarative contributor for the verified atlas cache-reuse optimization. It
 * installs in {@link Phase#PREMAIN} before the host's textureatlas classes
 * load and closes on the process-exit path.
 */
final class AtlasCacheReuseHookContributor implements HookContributor {

    static final String HOOK_POLICY_ID = "cubism.textureatlas.cache-reuse";

    @Override public String id() {
        return "TURBOISM_ATLAS_CACHE_REUSE";
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
            && AtlasCacheReusePreference.read(turboismHome);
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return enabled(environment.startupPolicy(), environment.options().home());
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        try {
            final VerifiedAtlasCacheReuseInstaller.Installation installation =
                VerifiedAtlasCacheReuseInstaller.install(
                    environment.instrumentation(),
                    // Premain runs before the diagnostics sink exists; the console is the
                    // only place the admission verdict survives on a real host.
                    code -> System.err.println("[turboism] atlas-cache-reuse " + code)
                );
            RuntimeDiagnostics.debug(
                "bootstrap",
                "Atlas cache-reuse status=" + installation.status()
                    + ", transformOutcome=" + installation.transformOutcome()
            );
            return installation;
        } catch (final Throwable failure) {
            RuntimeDiagnostics.error(
                "atlas-cache-reuse",
                "Atlas cache-reuse optimization disabled safely",
                failure
            );
            return () -> { };
        }
    }
}
