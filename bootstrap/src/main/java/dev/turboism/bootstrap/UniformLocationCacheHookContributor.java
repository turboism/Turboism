package dev.turboism.bootstrap;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;

/** Contributor for the verified uniform-location cache optimization. */
final class UniformLocationCacheHookContributor extends NativeOptimizationHookContributor {

    UniformLocationCacheHookContributor() {
        super("TURBOISM_UNIFORM_LOCATION", VerifiedUniformLocationInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!NativeOptimizationPolicy.uniformLocationCacheEnabled(environment)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedUniformLocationInstaller.admitted(
                host.artifact(),
                NativeOptimizationPolicy.load(environment.options().home()),
                true,
                Runtime.version().feature())) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedUniformLocationInstaller installer = new VerifiedUniformLocationInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        // The shader-uniform hook owns the same query seam and runs first (premain,
        // retransformation-incapable). COMPOSED_SHADER_UNIFORM records that this
        // cache took the seam over from its reviewed composed body.
        log(environment, id() + " seam=" + (installer.seamComposed() ? "COMPOSED_SHADER_UNIFORM" : "OFFICIAL"));
        return () -> {
            try {
                installer.close();
                RuntimeDiagnostics.info(
                        "bootstrap",
                        "TURBOISM_UNIFORM_LOCATION restoration=" + (installer.restored() ? "COMPLETE" : "UNVERIFIED"));
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                RuntimeDiagnostics.warn("bootstrap", "TURBOISM_UNIFORM_LOCATION restoration=FAILED " + failure);
            }
        };
    }
}
