package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Invalid configuration must not accidentally enable an opt-in native optimization. */
final class NativeOptimizationPolicy {
    private NativeOptimizationPolicy() {}

    static RuntimeStartupConfig load(Path home) {
        AtomicBoolean rejected = new AtomicBoolean();
        RuntimeStartupConfig config = RuntimeStartupConfig.load(home, ignored -> rejected.set(true));
        // The general startup loader's fallback disables splash/update switches,
        // not arbitrary hook IDs. Fail closed here without changing its global API.
        return rejected.get() ? new RuntimeStartupConfig(true, false, false, false) : config;
    }

    private static boolean requested(
            final HookEnvironment environment, final dev.turboism.config.NativeOptimizationConfig.Option option) {
        final var config = environment == null
                ? dev.turboism.config.NativeOptimizationConfig.load(null, ignored -> {})
                : environment.optimizationConfig();
        final var choice = config.choice(option);
        dev.turboism.config.NativeOptimizationDiagnostics.requested(option, choice);
        if (environment != null) {
            NativeOptimizationHookContributor.log(
                    environment,
                    "NATIVE_OPTIMIZATION preference=" + option.key()
                            + " requested=" + choice.enabled() + " source=" + choice.source()
                            + " config="
                            + (environment.options() == null
                                    ? "unavailable"
                                    : environment.options().home().resolve("config.json")));
        }
        return choice.enabled();
    }

    static boolean modelUpdateSkipEnabled(final HookEnvironment environment) {
        final boolean enabled =
                requested(environment, dev.turboism.config.NativeOptimizationConfig.Option.MODEL_UPDATE_SKIP);
        ModelUpdateSkipBridge.setConfiguredDefault(enabled);
        return enabled;
    }

    static boolean incrementalUpdateEnabled(final HookEnvironment environment) {
        final boolean enabled =
                requested(environment, dev.turboism.config.NativeOptimizationConfig.Option.INCREMENTAL_UPDATE);
        IncrementalUpdateBridge.setConfiguredDefault(enabled);
        return enabled;
    }

    static boolean uniformLocationCacheEnabled(final HookEnvironment environment) {
        final boolean enabled =
                requested(environment, dev.turboism.config.NativeOptimizationConfig.Option.UNIFORM_LOCATION_CACHE);
        UniformLocationHookBridge.setConfiguredDefault(enabled);
        return enabled;
    }

    static boolean uploadElisionEnabled(final HookEnvironment environment) {
        return requested(environment, dev.turboism.config.NativeOptimizationConfig.Option.UPLOAD_ELISION);
    }

    static boolean inputPathElisionEnabled(final HookEnvironment environment) {
        return requested(environment, dev.turboism.config.NativeOptimizationConfig.Option.INPUT_PATH_ELISION);
    }

    static boolean mesaGlThreadEnabled(final HookEnvironment environment) {
        return requested(environment, dev.turboism.config.NativeOptimizationConfig.Option.MESA_GL_THREAD);
    }

    static boolean meshInterpolationEnabled(final HookEnvironment environment) {
        return requested(environment, dev.turboism.config.NativeOptimizationConfig.Option.MESH_INTERPOLATION);
    }
}
