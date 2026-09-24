package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTransformer;

/**
 * Contributor for the test-only skipped-frame upload elision experiment. The
 * sole switch is {@code -Dturboism.validation.skippedFrameUploadElision=true};
 * the hook policy is not consulted because this is a per-leg diagnostic, not a
 * product optimization. The {@code elision=ACTIVE} log marker, not
 * {@code installation=COMPLETE} alone, is the driver's evidence that the two
 * reviewed mesh wrappers were actually rewritten; the close marker reports the
 * elided/passed/clear counters for reconciliation with the workload-side
 * {@code leg.N.uploadElision.*} report keys.
 */
final class SkippedFrameUploadElisionHookContributor extends NativeOptimizationHookContributor {

    SkippedFrameUploadElisionHookContributor() {
        super("TURBOISM_UPLOAD_ELISION");
    }

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!Boolean.getBoolean(SkippedFrameUploadElisionTransformer.ENABLE_PROPERTY)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        final VerifiedSkippedFrameUploadElisionInstaller installer =
            new VerifiedSkippedFrameUploadElisionInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(environment, id() + " elision=ACTIVE sites=" + installer.sites()
            + " targets=com/live2d/graphics3d/mesh/a/{b,c}.b(Lcom/jogamp/opengl/GL2ES2;I)V");
        return installer;
    }
}
