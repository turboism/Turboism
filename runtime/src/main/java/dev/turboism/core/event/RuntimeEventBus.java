package dev.turboism.core.event;

import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Compatibility facade for the legacy manual {@link EventBus} API.
 *
 * <p>New runtime composition shares one {@link RuntimeEventBroker} among plugin
 * facades. This constructor retains isolated behavior for focused legacy tests
 * and internal compatibility callers.</p>
 */
public final class RuntimeEventBus implements EventBus {

    private final PluginEventBus delegate;

    public RuntimeEventBus(
        final RuntimeScheduler scheduler,
        final String pluginId,
        final PermissionChecker permissionChecker
    ) {
        this(
            new RuntimeEventBroker(Objects.requireNonNull(scheduler, "scheduler")),
            pluginId,
            permissionChecker
        );
    }

    public RuntimeEventBus(
        final RuntimeEventBroker broker,
        final String pluginId,
        final PermissionChecker permissionChecker
    ) {
        this.delegate = new PluginEventBus(broker, pluginId, permissionChecker);
    }

    @Override
    public <T extends TurboismEvent> Registration subscribe(
        final Class<T> type,
        final Consumer<T> listener
    ) {
        return delegate.subscribe(type, listener);
    }

    @Override
    public <T extends TurboismEvent> void publish(final T event) {
        delegate.publish(event);
    }
}
