package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Invalid configuration must not accidentally enable an opt-in native optimization. */
final class NativeOptimizationPolicy {
    private NativeOptimizationPolicy() { }

    static RuntimeStartupConfig load(Path home) {
        AtomicBoolean rejected = new AtomicBoolean();
        RuntimeStartupConfig config = RuntimeStartupConfig.load(home, ignored -> rejected.set(true));
        // The general startup loader's fallback disables splash/update switches,
        // not arbitrary hook IDs. Fail closed here without changing its global API.
        return rejected.get() ? new RuntimeStartupConfig(true, false, false, false) : config;
    }
}
