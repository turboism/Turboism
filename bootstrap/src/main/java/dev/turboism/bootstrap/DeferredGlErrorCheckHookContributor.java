package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTransformer;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.mapping.verification.HostArtifactDigest;

/**
 * Contributor for the production deferred GL error-check path, installed only
 * under the combined {@code launcher.mesaGlThread} option (default off,
 * Linux/Proton-only in effect). The hook replaces the shader helper's
 * per-call synchronous {@code glGetError} with a frame-scoped checkpoint and
 * performs one real query at the render3d frame boundary; the frame-end
 * result feeds both the host-equivalent error report and the uniform-location
 * cache's conservative confirm/invalidate. The {@code deferred=ACTIVE} log
 * marker, not {@code installation=COMPLETE} alone, is the driver's evidence
 * that both reviewed rewrites were actually admitted.
 */
final class DeferredGlErrorCheckHookContributor extends NativeOptimizationHookContributor {

    DeferredGlErrorCheckHookContributor() {
        super("TURBOISM_DEFERRED_GL_ERROR_CHECK");
    }

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!Boolean.getBoolean(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        // The deferred checkpoints ride the uniform lifecycle seam: without its
        // published slots every checkpoint falls back to a real query while
        // glthread stays on — exactly the measured glthread-alone regression.
        if (!System.getProperties().containsKey(
                UniformLocationHookBridge.DEFER_QUERY_PROPERTY)) {
            log(environment, id() + " installation=NOT_ADMITTED reason=uniform-seam-absent");
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedDeferredGlErrorCheckInstaller.admitted(
                HostArtifactDigest.from(host.artifact()),
                NativeOptimizationPolicy.load(environment.options().home()),
                true,
                Runtime.version().feature())) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final VerifiedDeferredGlErrorCheckInstaller installer =
            new VerifiedDeferredGlErrorCheckInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(environment, id() + " deferred=ACTIVE targets=" + installer.targetDescription());
        return installer;
    }
}
