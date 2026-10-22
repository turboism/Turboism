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

    @Override
    public String id() {
        return "TURBOISM_ATLAS_CACHE_REUSE";
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
        return policy != null && policy.hookEnabled(HOOK_POLICY_ID) && AtlasCacheReusePreference.read(turboismHome);
    }

    @Override
    public boolean admitted(final HookEnvironment environment) {
        final boolean requested = environment
                .optimizationConfig()
                .choice(dev.turboism.config.NativeOptimizationConfig.Option.ATLAS_CACHE_REUSE)
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
            final VerifiedAtlasCacheReuseInstaller.Installation installation = VerifiedAtlasCacheReuseInstaller.install(
                    environment.instrumentation(), code -> RuntimeDiagnostics.debug("atlas-cache-reuse", code));
            RuntimeDiagnostics.debug(
                    "bootstrap",
                    "Atlas cache-reuse status=" + installation.status() + ", transformOutcome="
                            + installation.transformOutcome());
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
            RuntimeDiagnostics.error("atlas-cache-reuse", "Atlas cache-reuse optimization disabled safely", failure);
            return () -> {};
        }
    }
}
