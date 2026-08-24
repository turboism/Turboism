package dev.turboism.plugin.core;

import dev.turboism.sdk.runtime.RuntimeLogReader;
import dev.turboism.sdk.runtime.RuntimeSettingsService;

import java.util.Objects;
import java.util.function.Supplier;

/** One-shot Runtime-owned service handoff consumed only by the built-in core instance. */
public record CorePluginServices(
    RuntimeSettingsService settings,
    CubismJvmSettingsService cubismJvmSettings,
    dev.turboism.sdk.ui.settings.SettingsContributionSource settingsContributions,
    CorePluginManagement plugins,
    FloatingPanelActions floatingPanelActions,
    RuntimeLogReader logs
) {
    public CorePluginServices(
        final RuntimeSettingsService settings,
        final CorePluginManagement plugins
    ) {
        this(
            settings,
            CubismJvmSettingsService.unavailable(),
            dev.turboism.sdk.ui.settings.SettingsContributionSource.empty(),
            plugins,
            FloatingPanelActions.unavailable(),
            RuntimeLogReader.unavailable()
        );
    }

    public interface FloatingPanelActions {
        void togglePanelFloating(dev.turboism.sdk.ui.context.PanelTabSelection selection);

        static FloatingPanelActions unavailable() {
            return selection -> {
                throw new IllegalStateException("panel-tab floating action is unavailable");
            };
        }
    }
    private static final ThreadLocal<CorePluginServices> PENDING = new ThreadLocal<>();

    public CorePluginServices {
        settings = Objects.requireNonNull(settings, "settings");
        cubismJvmSettings = Objects.requireNonNull(cubismJvmSettings, "cubismJvmSettings");
        settingsContributions = Objects.requireNonNull(
            settingsContributions,
            "settingsContributions"
        );
        plugins = Objects.requireNonNull(plugins, "plugins");
        floatingPanelActions = Objects.requireNonNull(floatingPanelActions, "floatingPanelActions");
        logs = Objects.requireNonNull(logs, "logs");
    }


    /**
     * Runs a constructor with the runtime's service handoff visible to it, then withdraws the
     * handoff.
     *
     * <p>The services are parked in a thread-local for exactly the duration of the supplier call,
     * which is how the built-in core instance receives them without a public constructor parameter.
     * The handoff is cleared on every path, including when the constructor throws, so a failed
     * construction cannot leave services dangling for the next one. Nesting is refused: only one
     * handoff may be active on a thread at a time.
     *
     * @param <T> the constructed type
     * @param services the runtime-owned services to expose; must not be {@code null}
     * @param constructor the construction to run; must not be {@code null} and must not return
     *     {@code null}
     * @return whatever the constructor produced
     * @throws IllegalStateException if a handoff is already active on this thread
     * @throws NullPointerException if either argument, or the constructor's result, is {@code null}
     */
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
