package dev.turboism.plugin.core;

import dev.turboism.sdk.runtime.RuntimeSettingsService;

import java.util.Objects;
import java.util.function.Supplier;

/** One-shot Runtime-owned service handoff consumed only by the built-in core instance. */
public record CorePluginServices(
    RuntimeSettingsService settings,
    CorePluginManagement plugins
) {
    private static final ThreadLocal<CorePluginServices> PENDING = new ThreadLocal<>();

    public CorePluginServices {
        settings = Objects.requireNonNull(settings, "settings");
        plugins = Objects.requireNonNull(plugins, "plugins");
    }

    public static <T> T instantiate(
        final CorePluginServices services,
        final Supplier<T> constructor
    ) {
        if (PENDING.get() != null) throw new IllegalStateException("core service handoff already active");
        PENDING.set(Objects.requireNonNull(services, "services"));
        try {
            return Objects.requireNonNull(constructor, "constructor").get();
        } finally {
            PENDING.remove();
        }
    }

    static CorePluginServices consume() {
        final CorePluginServices services = PENDING.get();
        if (services == null) throw new IllegalStateException("built-in core requires Runtime composition");
        PENDING.remove();
        return services;
    }
}
