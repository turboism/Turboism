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

    @Override
    public String id() {
        return "TURBOISM_ATLAS_TILE_BBOX";
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
        return policy != null && policy.hookEnabled(HOOK_POLICY_ID) && AtlasTileBboxPreference.read(turboismHome);
    }

    @Override
    public boolean admitted(final HookEnvironment environment) {
        final boolean requested = environment
                .optimizationConfig()
                .choice(dev.turboism.config.NativeOptimizationConfig.Option.ATLAS_TILE_BBOX)
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
            final VerifiedAtlasTileBboxInstaller.Installation installation = VerifiedAtlasTileBboxInstaller.install(
                    environment.instrumentation(), code -> RuntimeDiagnostics.debug("atlas-tile-bbox", code));
            RuntimeDiagnostics.debug(
                    "bootstrap",
                    "Atlas tile-bbox status=" + installation.status() + ", transformOutcome="
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
            RuntimeDiagnostics.error("atlas-tile-bbox", "Atlas tile-bbox optimization disabled safely", failure);
            return () -> {};
        }
    }
}
