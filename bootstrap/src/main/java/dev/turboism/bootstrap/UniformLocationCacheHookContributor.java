package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.runtime.log.RuntimeDiagnostics;

/** Contributor for the verified uniform-location cache optimization. */
final class UniformLocationCacheHookContributor extends NativeOptimizationHookContributor {

    UniformLocationCacheHookContributor() {
        super("TURBOISM_UNIFORM_LOCATION");
    }

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!UniformLocationHookBridge.enabledByPreference()) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedUniformLocationInstaller.admitted(
            HostArtifactDigest.from(host.artifact()),
            NativeOptimizationPolicy.load(environment.options().home()),
            true,
            Runtime.version().feature()
        )) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final VerifiedUniformLocationInstaller installer = new VerifiedUniformLocationInstaller(
            environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return () -> {
            try {
                installer.close();
                RuntimeDiagnostics.info(
                    "bootstrap",
                    "TURBOISM_UNIFORM_LOCATION restoration="
                        + (installer.restored() ? "COMPLETE" : "UNVERIFIED"));
            } catch (final Throwable failure) {
                RuntimeDiagnostics.warn(
                    "bootstrap",
                    "TURBOISM_UNIFORM_LOCATION restoration=FAILED " + failure);
            }
        };
    }
}
