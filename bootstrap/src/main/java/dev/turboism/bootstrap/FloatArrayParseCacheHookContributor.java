package dev.turboism.bootstrap;

/** Contributor for the verified float-array parse cache optimization. */
final class FloatArrayParseCacheHookContributor extends NativeOptimizationHookContributor {

    FloatArrayParseCacheHookContributor() {
        super("TURBOISM_FLOAT_ARRAY_PARSE_CACHE", VerifiedFloatArrayParseCacheInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!NativeOptimizationPolicy.floatArrayParseCacheEnabled(environment)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedFloatArrayParseCacheInstaller.admitted(
                host.artifact(),
                NativeOptimizationPolicy.load(environment.options().home()),
                true)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedFloatArrayParseCacheInstaller installer = new VerifiedFloatArrayParseCacheInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return installer;
    }
}
