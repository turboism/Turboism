package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.geometry.MatrixScratchTransformer;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;

/** Contributor for the verified matrix scratch-buffer optimization. */
final class MatrixScratchHookContributor extends NativeOptimizationHookContributor {

    MatrixScratchHookContributor() {
        super("TURBOISM_MATRIX_SCRATCH", VerifiedMatrixScratchInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!NativeOptimizationPolicy.matrixScratchEnabled(environment)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedMatrixScratchInstaller.admitted(
                host.artifact(),
                NativeOptimizationPolicy.load(environment.options().home()),
                true,
                Runtime.version().feature())) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedMatrixScratchInstaller installer =
                new VerifiedMatrixScratchInstaller(environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        final boolean ownedEnableProperty = System.getProperty(MatrixScratchTransformer.ENABLE_PROPERTY) == null;
        if (ownedEnableProperty) {
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, "true");
        }
        return () -> {
            if (ownedEnableProperty) {
                System.clearProperty(MatrixScratchTransformer.ENABLE_PROPERTY);
            }
            try {
                installer.close();
                RuntimeDiagnostics.info(
                        "bootstrap",
                        "TURBOISM_MATRIX_SCRATCH restoration=" + (installer.restored() ? "COMPLETE" : "UNVERIFIED"));
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                RuntimeDiagnostics.warn("bootstrap", "TURBOISM_MATRIX_SCRATCH restoration=FAILED " + failure);
            }
        };
    }
}
