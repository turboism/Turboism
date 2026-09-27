package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge;
import dev.turboism.mapping.verification.HostArtifactDigest;

/** Contributor for the verified incremental model update optimization. */
final class IncrementalUpdateHookContributor extends NativeOptimizationHookContributor {

    IncrementalUpdateHookContributor() {
        super("TURBOISM_INCREMENTAL_UPDATE");
    }

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!IncrementalUpdateBridge.flagEnabled()) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedIncrementalUpdateInstaller.admitted(
            HostArtifactDigest.from(host.artifact()),
            NativeOptimizationPolicy.load(environment.options().home()),
            true,
            Runtime.version().feature()
        )) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final VerifiedIncrementalUpdateInstaller installer = new VerifiedIncrementalUpdateInstaller(
            environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return installer;
    }
}
