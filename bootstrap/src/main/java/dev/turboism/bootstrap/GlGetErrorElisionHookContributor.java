package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTransformer;

/**
 * Contributor for the test-only glGetError elision experiment. The sole switch is
 * {@code -Dturboism.validation.glGetErrorElision=true}; the hook policy is not
 * consulted because this is a per-leg diagnostic, not a product optimization. The
 * {@code elision=ACTIVE} log marker, not {@code installation=COMPLETE} alone, is the
 * driver's evidence that a rewrite actually happened.
 */
final class GlGetErrorElisionHookContributor extends NativeOptimizationHookContributor {

    GlGetErrorElisionHookContributor() {
        super("TURBOISM_GL_ERROR_ELISION");
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!Boolean.getBoolean(GlGetErrorElisionTransformer.ENABLE_PROPERTY)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        final VerifiedGlGetErrorElisionInstaller installer = new VerifiedGlGetErrorElisionInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(
                environment,
                id() + " elision=ACTIVE sites=" + installer.elided() + " target=" + installer.targetDescription());
        return installer;
    }
}
