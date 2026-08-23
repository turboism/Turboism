package dev.turboism.core.event;

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
            true
        );
    }

    public PluginEventBus(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final PermissionChecker permissionChecker
    ) {
        this(broker, owner, permissionChecker, false);
    }

    private PluginEventBus(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final PermissionChecker permissionChecker,
        final boolean legacyExactRouting
    ) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.legacyExactRouting = legacyExactRouting;
    }

    @Override
    public <T extends TurboismEvent> Registration subscribe(
        final Class<T> type,
        final Consumer<T> listener
    ) {
        permissionChecker.check(PermissionIds.TURBOISM_EVENT_SUBSCRIBE, "event.subscribe");
        EventSubscriptionPermissionCatalog.check(type, permissionChecker);
        return broker.subscribe(owner, type, listener);
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
