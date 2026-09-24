package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionTransformer;

/**
 * Contributor for the test-only input-path elision experiment. The sole switch
 * is {@code -Dturboism.validation.inputPathElision=true}; the hook policy is
 * not consulted because this is a per-leg diagnostic, not a product
 * optimization. The {@code elision=ACTIVE} log marker, not
 * {@code installation=COMPLETE} alone, is the driver's evidence that the
 * reviewed {@code CWidget} methods were actually rewritten; the close marker
 * reports the per-site counters for reconciliation with the workload-side
 * {@code leg.N.inputPath.*} report keys.
 */
final class InputPathElisionHookContributor extends NativeOptimizationHookContributor {

    InputPathElisionHookContributor() {
        super("TURBOISM_INPUT_PATH");
    }

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!Boolean.getBoolean(InputPathElisionTransformer.ENABLE_PROPERTY)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        final VerifiedInputPathElisionInstaller installer =
            new VerifiedInputPathElisionInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(environment, id() + " elision=ACTIVE sites=" + installer.sites()
            + " targets=com/live2d/ui/CWidget.requestFocus()V"
            + "+setCursor(Lcom/live2d/type/CCursor;)V");
        return installer;
    }
}
