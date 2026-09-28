package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionBridge;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;

/**
 * Contributor for the input-path elision transform, in two modes. Validation
 * mode: {@code -Dturboism.validation.inputPathElision=true}, a per-leg
 * diagnostic whose gate is driven by the workload; the hook policy is not
 * consulted. Production mode:
 * {@code -Dturboism.optimization.inputPathElision=true} (default off), armed
 * unconditionally, gated by the persisted preference and the startup hook
 * policy ({@code cubism.render.input-path-elision}). When both flags are set
 * the validation mode wins — the harness owns the gate — and the mode is
 * logged. The {@code elision=ACTIVE} log marker, not
 * {@code installation=COMPLETE} alone, is the driver's evidence that the
 * reviewed {@code CWidget} methods were actually rewritten; the close marker
 * reports the per-site counters for reconciliation with the workload-side
 * {@code leg.N.inputPath.*} report keys.
 */
final class InputPathElisionHookContributor extends NativeOptimizationHookContributor {

    InputPathElisionHookContributor() {
        super("TURBOISM_INPUT_PATH");
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        final boolean validation = Boolean.getBoolean(InputPathElisionTransformer.ENABLE_PROPERTY);
        final boolean production = InputPathElisionBridge.enabledByPreference();
        if (!validation && !production) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        if (production
                && !validation
                && !VerifiedInputPathElisionInstaller.admitted(
                        HostArtifactDigest.from(host.artifact()),
                        NativeOptimizationPolicy.load(environment.options().home()),
                        true,
                        Runtime.version())) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final VerifiedInputPathElisionInstaller installer = new VerifiedInputPathElisionInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install(production && !validation);
        log(
                environment,
                id() + " elision=ACTIVE sites=" + installer.sites()
                        + " mode=" + (validation ? "validation" : "production")
                        + " targets=com/live2d/ui/CWidget.requestFocus()V"
                        + "+setCursor(Lcom/live2d/type/CCursor;)V");
        return installer;
    }
}
