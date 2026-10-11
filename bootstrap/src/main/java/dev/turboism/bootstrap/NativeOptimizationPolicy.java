package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTransformer;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionBridge;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionBridge;
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

    private static Path home(final HookEnvironment environment) {
        return environment != null && environment.options() != null
                ? environment.options().home()
                : null;
    }

    static boolean modelUpdateSkipEnabled(final HookEnvironment environment) {
        if (System.getProperty(ModelUpdateSkipBridge.ENABLE_PROPERTY) != null) {
            return ModelUpdateSkipBridge.flagEnabled();
        }
        final boolean enabled =
                RuntimeStartupConfig.readLauncherOptimization(home(environment), "modelUpdateSkip", true);
        ModelUpdateSkipBridge.setConfiguredDefault(enabled);
        return enabled;
    }

    static boolean incrementalUpdateEnabled(final HookEnvironment environment) {
        if (System.getProperty(IncrementalUpdateBridge.ENABLE_PROPERTY) != null) {
            return IncrementalUpdateBridge.flagEnabled();
        }
        final boolean enabled =
                RuntimeStartupConfig.readLauncherOptimization(home(environment), "incrementalUpdate", false);
        IncrementalUpdateBridge.setConfiguredDefault(enabled);
        return enabled;
    }

    static boolean uniformLocationCacheEnabled(final HookEnvironment environment) {
        if (System.getProperty(UniformLocationHookBridge.ENABLE_PROPERTY) != null) {
            return UniformLocationHookBridge.enabledByPreference();
        }
        final boolean enabled =
                RuntimeStartupConfig.readLauncherOptimization(home(environment), "uniformLocationCache", true);
        UniformLocationHookBridge.setConfiguredDefault(enabled);
        return enabled;
    }

    static boolean uploadElisionEnabled(final HookEnvironment environment) {
        if (System.getProperty(SkippedFrameUploadElisionBridge.ENABLE_PROPERTY) != null) {
            return SkippedFrameUploadElisionBridge.enabledByPreference();
        }
        return RuntimeStartupConfig.readLauncherOptimization(home(environment), "uploadElision", true);
    }

    static boolean inputPathElisionEnabled(final HookEnvironment environment) {
        if (System.getProperty(InputPathElisionBridge.ENABLE_PROPERTY) != null) {
            return InputPathElisionBridge.enabledByPreference();
        }
        return RuntimeStartupConfig.readLauncherOptimization(
                home(environment), "inputPathElision", InputPathElisionBridge.enabledByPreference());
    }

    static boolean mesaGlThreadEnabled(final HookEnvironment environment) {
        if (System.getProperty(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY) != null) {
            return DeferredGlErrorCheckTransformer.enabledByPreference();
        }
        return RuntimeStartupConfig.readLauncherOptimization(
                home(environment), "mesaGlThread", DeferredGlErrorCheckTransformer.enabledByPreference());
    }
}
