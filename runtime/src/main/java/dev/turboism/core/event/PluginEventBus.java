package dev.turboism.core.event;

import dev.turboism.core.runtime.ContextClassLoaderScope;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.function.Consumer;

/** Permission-gated plugin facade over a session-scoped event broker. */
public final class PluginEventBus implements EventBus {

    private final RuntimeEventBroker broker;
    private final PluginEventOwnerKey owner;
    private final PermissionChecker permissionChecker;
    private final ClassLoader pluginClassLoader;
    private final boolean legacyExactRouting;

    public PluginEventBus(
        final RuntimeEventBroker broker,
        final String pluginId,
        final PermissionChecker permissionChecker
    ) {
        this(
            broker,
            Objects.requireNonNull(broker, "broker").legacyOwner(pluginId),
            permissionChecker,
            null,
            true
        );
    }

    public PluginEventBus(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final PermissionChecker permissionChecker
    ) {
        this(broker, owner, permissionChecker, null, false);
    }

    public PluginEventBus(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final PermissionChecker permissionChecker,
        final ClassLoader pluginClassLoader
    ) {
        this(broker, owner, permissionChecker, pluginClassLoader, false);
    }

    private PluginEventBus(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final PermissionChecker permissionChecker,
        final ClassLoader pluginClassLoader,
        final boolean legacyExactRouting
    ) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.pluginClassLoader = pluginClassLoader;
        this.legacyExactRouting = legacyExactRouting;
    }

    @Override
    public <T extends TurboismEvent> Registration subscribe(
        final Class<T> type,
        final Consumer<T> listener
    ) {
        permissionChecker.check(PermissionIds.TURBOISM_EVENT_SUBSCRIBE, "event.subscribe");
        EventSubscriptionPermissionCatalog.check(type, permissionChecker);
        final Consumer<T> callback = Objects.requireNonNull(listener, "listener");
        if (pluginClassLoader == null) {
            return broker.subscribe(owner, type, callback);
        }
        return broker.subscribe(owner, type, event -> {
            try (ContextClassLoaderScope ignored = ContextClassLoaderScope.bind(
                pluginClassLoader
            )) {
                callback.accept(event);
            }
        });
    }

    @Override
    public <T extends TurboismEvent> void publish(final T event) {
        permissionChecker.check(PermissionIds.TURBOISM_EVENT_PUBLISH, "event.publish");
        if (legacyExactRouting) {
            broker.publishExact(owner, event);
        } else {
            broker.publish(owner, event);
        }
    }
}
