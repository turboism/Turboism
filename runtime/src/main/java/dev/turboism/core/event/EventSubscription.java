package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.EventPriority;
import java.util.function.Consumer;

/**
 * One registered callback route owned by an exact plugin generation.
 *
 * <p>The sequence number is assigned once at registration and never reused, so ordering stays
 * deterministic when a generation resubscribes. {@link #active()} is the only mutable state: a
 * closed {@link dev.turboism.sdk.plugin.Registration} deactivates the route instead of removing it
 * from an in-flight publication snapshot, which keeps an already-started publish consistent.
 */
final class EventSubscription<T extends EventBus.TurboismEvent> {

    private final long sequence;
    private final PluginEventOwnerKey owner;
    private final Class<T> type;
    private final EventPriority priority;
    private final int entrypointOrdinal;
    private final int methodOrdinal;
    private final boolean deliverWhileEnabling;
    private final boolean permissionFiltered;
    private final Consumer<T> listener;
    private volatile boolean active = true;

    EventSubscription(
            final long sequence,
            final PluginEventOwnerKey owner,
            final Class<T> type,
            final EventPriority priority,
            final int entrypointOrdinal,
            final int methodOrdinal,
            final boolean deliverWhileEnabling,
            final boolean permissionFiltered,
            final Consumer<T> listener) {
        this.sequence = sequence;
        this.owner = owner;
        this.type = type;
        this.priority = priority;
        this.entrypointOrdinal = entrypointOrdinal;
        this.methodOrdinal = methodOrdinal;
        this.deliverWhileEnabling = deliverWhileEnabling;
        this.permissionFiltered = permissionFiltered;
        this.listener = listener;
    }

    long sequence() {
        return sequence;
    }

    PluginEventOwnerKey owner() {
        return owner;
    }

    Class<T> type() {
        return type;
    }

    EventPriority priority() {
        return priority;
    }

    int entrypointOrdinal() {
        return entrypointOrdinal;
    }

    int methodOrdinal() {
        return methodOrdinal;
    }

    boolean active() {
        return active;
    }

    boolean permissionFiltered() {
        return permissionFiltered;
    }

    /**
     * Reports whether this route admits deliveries in the given owner lifecycle state. Staged
     * subscriptions stay queued during initialization and enabling; the flag decides whether those
     * queued deliveries run before the owner reaches {@code ACTIVE}.
     */
    boolean accepts(final RuntimeEventBroker.OwnerLifecycle lifecycle) {
        return lifecycle == RuntimeEventBroker.OwnerLifecycle.ACTIVE
                || (deliverWhileEnabling
                        && (lifecycle == RuntimeEventBroker.OwnerLifecycle.ENABLING
                                || lifecycle == RuntimeEventBroker.OwnerLifecycle.INITIALIZING));
    }

    void deactivate() {
        active = false;
    }

    void deliverDispatched(final EventBus.TurboismEvent event) {
        if (type.isInstance(event)) {
            listener.accept(type.cast(event));
        }
    }
}
