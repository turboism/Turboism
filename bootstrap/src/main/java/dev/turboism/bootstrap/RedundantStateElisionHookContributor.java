package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.stateelision.RedundantStateElisionTransformer;

/**
 * Contributor for the test-only redundant-GL-state elision experiment. The
 * sole switch is {@code -Dturboism.validation.redundantStateElision=true}; the
 * hook policy is not consulted because this is a per-leg diagnostic, not a
 * product optimization. The {@code elision=ACTIVE sites=N} log marker, not
 * {@code installation=COMPLETE} alone, is the driver's evidence that the
 * pinned JOGL {@code GL4bcImpl} was actually rewritten; the close marker
 * reports per-method {@code elided/passed/invalidations} for reconciliation
 * with the workload-side {@code leg.N.stateElision.*} report keys.
 */
final class RedundantStateElisionHookContributor extends NativeOptimizationHookContributor {

    RedundantStateElisionHookContributor() {
        super("TURBOISM_STATE_ELISION");
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!Boolean.getBoolean(RedundantStateElisionTransformer.ENABLE_PROPERTY)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        final VerifiedRedundantStateElisionInstaller installer = new VerifiedRedundantStateElisionInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(
                environment,
                id() + " elision=ACTIVE sites=" + installer.sites() + " target="
                        + RedundantStateElisionTransformer.OWNER_DESCRIPTION);
        return installer;
    }
}
