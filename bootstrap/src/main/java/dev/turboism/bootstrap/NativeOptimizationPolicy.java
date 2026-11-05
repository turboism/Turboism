package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
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

    private static boolean requested(final HookEnvironment environment, final Optimization option) {
        final var config = environment == null
                ? dev.turboism.config.NativeOptimizationConfig.load(null, ignored -> {})
                : environment.optimizationConfig();
        final var choice = config.choice(option);
        dev.turboism.config.NativeOptimizationDiagnostics.requested(option, choice);
        if (environment != null) {
            NativeOptimizationHookContributor.debug(
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
        final boolean enabled = requested(environment, OptimizationCatalog.MODEL_UPDATE_SKIP);
        ModelUpdateSkipBridge.setConfiguredDefault(enabled);
        return enabled;
    }

    static boolean incrementalUpdateEnabled(final HookEnvironment environment) {
        final boolean enabled = requested(environment, OptimizationCatalog.INCREMENTAL_UPDATE);
        IncrementalUpdateBridge.setConfiguredDefault(enabled);
        if (enabled) {
            IncrementalUpdateBridge.setAffecterConfiguredDefault(
                    requested(environment, OptimizationCatalog.INCREMENTAL_UPDATE_AFFECTERS));
        }
        return enabled;
    }

    static boolean uniformLocationCacheEnabled(final HookEnvironment environment) {
        final boolean enabled = requested(environment, OptimizationCatalog.UNIFORM_LOCATION_CACHE);
        UniformLocationHookBridge.setConfiguredDefault(enabled);
        return enabled;
    }

    static boolean uploadElisionEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.UPLOAD_ELISION);
    }

    static boolean meshSetupElisionEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.MESH_SETUP_ELISION);
    }

    static boolean inputPathElisionEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.INPUT_PATH_ELISION);
    }

    static boolean mesaGlThreadEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.MESA_GL_THREAD);
    }

    static boolean asyncReadbackEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.ASYNC_READBACK);
    }

    static boolean meshInterpolationEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.MESH_INTERPOLATION);
    }

    static boolean shaderUniformEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.SHADER_UNIFORM);
    }

    static boolean matrixScratchEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.MATRIX_SCRATCH);
    }

    static boolean imageArchiveReuseEnabled(final HookEnvironment environment) {
        final boolean enabled = requested(environment, OptimizationCatalog.IMAGE_ARCHIVE_REUSE);
        dev.turboism.adapter.cubism.optimization.image.ImageArchiveReuseBridge.setConfiguredDefault(enabled);
        return enabled;
    }

    static boolean floatArrayParseCacheEnabled(final HookEnvironment environment) {
        final boolean enabled = requested(environment, OptimizationCatalog.FLOAT_ARRAY_PARSE_CACHE);
        dev.turboism.adapter.cubism.optimization.serialization.FloatArrayParseBridge.setConfiguredDefault(enabled);
        return enabled;
    }

    static boolean textureUploadPreparationEnabled(final HookEnvironment environment) {
        final boolean enabled = requested(environment, OptimizationCatalog.TEXTURE_UPLOAD_PREPARATION);
        dev.turboism.adapter.cubism.optimization.image.TextureUploadPreparationBridge.setConfiguredDefault(enabled);
        return enabled;
    }

    static boolean warpPositionProjectionEnabled(final HookEnvironment environment) {
        final boolean enabled = requested(environment, OptimizationCatalog.WARP_POSITION_PROJECTION);
        dev.turboism.adapter.cubism.optimization.geometry.WarpPositionProjectionBridge.setConfiguredDefault(enabled);
        return enabled;
    }

    static boolean reinitPairElisionEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.REINIT_PAIR_ELISION);
    }

    static boolean clipMaskDeduplicationEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.CLIP_MASK_DEDUPLICATION);
    }

    static boolean clipMaskScissorClearEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.CLIP_MASK_SCISSOR_CLEAR);
    }

    static boolean extendedInterpolationIncrementalSetupEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.EXTENDED_INTERPOLATION_INCREMENTAL_SETUP);
    }

    static boolean modelInstanceCoalesceEnabled(final HookEnvironment environment) {
        return requested(environment, OptimizationCatalog.MODEL_INSTANCE_COALESCE);
    }

    static boolean inspectorPropertyBypassEnabled(final HookEnvironment environment) {
        final boolean enabled = requested(environment, OptimizationCatalog.INSPECTOR_PROPERTY_BYPASS);
        dev.turboism.adapter.cubism.optimization.inspector.InspectorPropertyBypassBridge.setConfiguredDefault(enabled);
        return enabled;
    }
}
