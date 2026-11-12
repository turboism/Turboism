package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.EventPriority;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Owns the subscription routes of one broker session: registration, deterministic ordering, and
 * the cached dispatch plans derived from them.
 *
 * <p>Two locks protect this state and their order is load-bearing. Callers hold the owner monitor
 * first and enter here second, so a lifecycle transition can never interleave with a registration
 * for the same generation. {@code subscriptionLock} guards every mutation of {@code subscribers}
 * and {@code dispatchPlans}; publications copy a plan under that lock and then deliver outside it.
 *
 * <p>Plans are cached per concrete event type and invalidated on every route change. A cached plan
 * is an immutable snapshot, so a route closed during publication stays visible to the in-flight
 * pass and is skipped by its own {@code active} flag instead.
 */
final class EventSubscriptionRegistry {

    private static final Comparator<EventSubscription<? extends EventBus.TurboismEvent>> SUBSCRIPTION_ORDER =
            Comparator.comparingInt((EventSubscription<? extends EventBus.TurboismEvent> value) ->
                            value.priority().ordinal())
                    .thenComparing(value -> value.owner().pluginId())
                    .thenComparingLong(value -> value.owner().generation())
                    .thenComparingInt(EventSubscription::entrypointOrdinal)
                    .thenComparingInt(EventSubscription::methodOrdinal)
                    .thenComparingLong(EventSubscription::sequence);

    private final Object subscriptionLock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<
                    Class<? extends EventBus.TurboismEvent>,
                    CopyOnWriteArrayList<EventSubscription<? extends EventBus.TurboismEvent>>>
            subscribers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, List<EventSubscription<? extends EventBus.TurboismEvent>>> dispatchPlans =
            new ConcurrentHashMap<>();

    /** Adds one route and returns the handle its owner registration closes. */
    <T extends EventBus.TurboismEvent> EventSubscription<T> register(
            final PluginEventOwnerKey owner,
            final Class<T> type,
            final EventPriority priority,
            final int entrypointOrdinal,
            final int methodOrdinal,
            final boolean deliverWhileEnabling,
            final boolean permissionFiltered,
            final Consumer<T> listener) {
        final EventSubscription<T> subscription = new EventSubscription<>(
                sequence.incrementAndGet(),
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(priority, "priority"),
                entrypointOrdinal,
                methodOrdinal,
                deliverWhileEnabling,
                permissionFiltered,
                Objects.requireNonNull(listener, "listener"));
        synchronized (subscriptionLock) {
            final CopyOnWriteArrayList<EventSubscription<? extends EventBus.TurboismEvent>> route =
                    subscribers.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>());
            route.add(subscription);
            route.sort(SUBSCRIPTION_ORDER);
            dispatchPlans.clear();
        }
        return subscription;
    }

    /** Deactivates and removes one route, dropping its event type when the route was the last. */
    <T extends EventBus.TurboismEvent> void remove(final Class<T> type, final EventSubscription<T> subscription) {
        subscription.deactivate();
        synchronized (subscriptionLock) {
            final CopyOnWriteArrayList<EventSubscription<? extends EventBus.TurboismEvent>> eventSubscribers =
                    subscribers.get(type);
            if (eventSubscribers == null) {
                return;
            }
            eventSubscribers.remove(subscription);
            dispatchPlans.clear();
            if (eventSubscribers.isEmpty()) {
                subscribers.remove(type, eventSubscribers);
            }
        }
    }

    /** Deactivates and removes every route owned by one generation. */
    void removeOwner(final PluginEventOwnerKey owner) {
        synchronized (subscriptionLock) {
            subscribers.forEach((type, route) -> {
                route.removeIf(subscription -> {
                    if (!subscription.owner().equals(owner)) {
                        return false;
                    }
                    subscription.deactivate();
                    return true;
                });
                if (route.isEmpty()) {
                    subscribers.remove(type, route);
                }
            });
            dispatchPlans.clear();
        }
    }

    /** Returns the exact-type routes registered for one concrete event class. */
    List<EventSubscription<? extends EventBus.TurboismEvent>> exactRoute(final Class<?> concreteType) {
        synchronized (subscriptionLock) {
            final CopyOnWriteArrayList<EventSubscription<? extends EventBus.TurboismEvent>> current =
                    subscribers.get(concreteType);
            return current == null ? List.of() : List.copyOf(current);
        }
    }

    /**
     * Returns the ordered plan for one concrete event class: every route whose registered type is
     * assignable from it. The plan is cached because publication runs per event and the route set
     * changes only on registration or removal.
     */
    List<EventSubscription<? extends EventBus.TurboismEvent>> dispatchPlan(final Class<?> concreteType) {
        final List<EventSubscription<? extends EventBus.TurboismEvent>> cached = dispatchPlans.get(concreteType);
        if (cached != null) {
            return cached;
        }
        synchronized (subscriptionLock) {
            final List<EventSubscription<? extends EventBus.TurboismEvent>> current = dispatchPlans.get(concreteType);
            if (current != null) {
                return current;
            }
            final List<EventSubscription<? extends EventBus.TurboismEvent>> planned = subscribers.entrySet().stream()
                    .filter(entry -> entry.getKey().isAssignableFrom(concreteType))
                    .flatMap(entry -> entry.getValue().stream())
                    .sorted(SUBSCRIPTION_ORDER)
                    .toList();
            dispatchPlans.put(concreteType, planned);
            return planned;
        }
    }

    /** Returns every route owned by one generation, in registration order. */
    List<EventSubscription<? extends EventBus.TurboismEvent>> routesOf(final PluginEventOwnerKey owner) {
        return subscribers.values().stream()
                .flatMap(List::stream)
                .filter(subscription -> subscription.owner().equals(owner))
                .toList();
    }
}
