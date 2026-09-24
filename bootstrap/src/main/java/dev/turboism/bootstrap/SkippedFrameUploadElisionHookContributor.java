package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionBridge;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;

/**
 * Contributor for the skipped-frame upload elision path, in two modes.
 * Validation mode: {@code -Dturboism.validation.skippedFrameUploadElision=true},
 * a per-leg diagnostic whose gate and compare mode are driven by the workload;
 * the hook policy is not consulted. Production mode:
 * {@code -Dturboism.optimization.uploadElision=true} (default off), content
 * compare armed unconditionally, gated by the persisted preference, the
 * startup hook policy ({@code cubism.render.upload-elision}) and the
 * model-update-skip dependency. When both flags are set the validation mode
 * wins — the harness owns the gate — and the mode is logged. The
 * {@code elision=ACTIVE} log marker, not {@code installation=COMPLETE} alone,
 * is the driver's evidence that the two reviewed mesh wrappers were actually
 * rewritten; the close marker reports the counters for reconciliation with
 * the workload-side {@code leg.N.uploadElision.*} report keys.
 */
final class SkippedFrameUploadElisionHookContributor extends NativeOptimizationHookContributor {

    SkippedFrameUploadElisionHookContributor() {
        super("TURBOISM_UPLOAD_ELISION");
    }

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        final boolean validation = Boolean.getBoolean(
            SkippedFrameUploadElisionTransformer.ENABLE_PROPERTY);
        final boolean production =
            SkippedFrameUploadElisionBridge.enabledByPreference();
        if (!validation && !production) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        if (production && !validation && !ModelUpdateSkipBridge.flagEnabled()) {
            // Without skipped frames the consult can never elide; installing
            // would buy pure per-call overhead.
            log(environment, id()
                + " installation=NOT_ADMITTED reason=modelUpdateSkip-disabled");
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        if (production && !validation
            && !VerifiedSkippedFrameUploadElisionInstaller.admitted(
                HostArtifactDigest.from(host.artifact()),
                NativeOptimizationPolicy.load(environment.options().home()),
                true,
                Runtime.version().feature())) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final VerifiedSkippedFrameUploadElisionInstaller installer =
            new VerifiedSkippedFrameUploadElisionInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader(),
                production && !validation);
        installer.install();
        log(environment, id() + " elision=ACTIVE sites=" + installer.sites()
            + " mode=" + (validation ? "validation" : "production")
            + " targets=com/live2d/graphics3d/mesh/a/{b,c}.b(Lcom/jogamp/opengl/GL2ES2;I)V");
        return installer;
    }
}
