package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.serialization.FloatArrayParseBridge;
import dev.turboism.mapping.verification.HostArtifactDigest;

/** Contributor for the verified float-array parse cache optimization. */
final class FloatArrayParseCacheHookContributor extends NativeOptimizationHookContributor {

    FloatArrayParseCacheHookContributor() {
        super("TURBOISM_FLOAT_ARRAY_PARSE_CACHE");
    }

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!Boolean.getBoolean(FloatArrayParseBridge.ENABLE_PROPERTY)) {
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedFloatArrayParseCacheInstaller.admitted(
            HostArtifactDigest.from(host.artifact()),
            NativeOptimizationPolicy.load(environment.options().home()),
            true
        )) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final VerifiedFloatArrayParseCacheInstaller installer =
            new VerifiedFloatArrayParseCacheInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return installer;
    }
}
