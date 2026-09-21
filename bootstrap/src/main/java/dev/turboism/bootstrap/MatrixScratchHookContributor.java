package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.geometry.MatrixScratchTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.runtime.log.RuntimeDiagnostics;

/** Contributor for the verified matrix scratch-buffer optimization. */
final class MatrixScratchHookContributor extends NativeOptimizationHookContributor {

    MatrixScratchHookContributor() {
        super("TURBOISM_MATRIX_SCRATCH");
    }

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        final var host = environment.host().orElseThrow();
        if (!VerifiedMatrixScratchInstaller.admitted(
            HostArtifactDigest.from(host.artifact()),
            NativeOptimizationPolicy.load(environment.options().home()),
            Boolean.getBoolean(MatrixScratchTransformer.ENABLE_PROPERTY),
            Runtime.version().feature()
        )) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final VerifiedMatrixScratchInstaller installer = new VerifiedMatrixScratchInstaller(
            environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return () -> {
            try {
                installer.close();
                RuntimeDiagnostics.info(
                    "bootstrap",
                    "TURBOISM_MATRIX_SCRATCH restoration="
                        + (installer.restored() ? "COMPLETE" : "UNVERIFIED"));
            } catch (final Throwable failure) {
                RuntimeDiagnostics.warn(
                    "bootstrap",
                    "TURBOISM_MATRIX_SCRATCH restoration=FAILED " + failure);
            }
        };
    }
}
