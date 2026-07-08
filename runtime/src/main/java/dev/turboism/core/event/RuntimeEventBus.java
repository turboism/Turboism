package dev.turboism.core.event;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class RuntimeEventBus implements EventBus {

    private static final String EVENT_TASK_TYPE = "event.subscribe";
    private static final String DEFAULT_CAPABILITY = "none";

    private final RuntimeScheduler scheduler;
    private final String pluginId;
    private final ConcurrentMap<Class<? extends TurboismEvent>, CopyOnWriteArrayList<Subscription<? extends TurboismEvent>>> subscribers =
        new ConcurrentHashMap<>();

    public RuntimeEventBus(RuntimeScheduler scheduler, PluginContext pluginContext) {
        this(scheduler, Objects.requireNonNull(pluginContext, "pluginContext").descriptor().id());
    }

    public RuntimeEventBus(RuntimeScheduler scheduler, String pluginId) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pluginId = requireText(pluginId, "pluginId");
    }

    @Override
    public <T extends TurboismEvent> Registration subscribe(Class<T> type, Consumer<T> listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listener, "listener");
        Subscription<T> subscription = new Subscription<>(type, listener);
        subscribers.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>()).add(subscription);
        return () -> remove(type, subscription);
    }

    @Override
    public <T extends TurboismEvent> void publish(T event) {
        Objects.requireNonNull(event, "event");
        CopyOnWriteArrayList<Subscription<? extends TurboismEvent>> eventSubscribers = subscribers.get(event.getClass());
        if (eventSubscribers == null) {
            return;
        }

        for (Subscription<? extends TurboismEvent> subscription : eventSubscribers) {
            dispatch(event, subscription);
        }
    }

    private <T extends TurboismEvent> void remove(Class<T> type, Subscription<T> subscription) {
        CopyOnWriteArrayList<Subscription<? extends TurboismEvent>> eventSubscribers = subscribers.get(type);
        if (eventSubscribers == null) {
            return;
        }
        eventSubscribers.remove(subscription);
        if (eventSubscribers.isEmpty()) {
            subscribers.remove(type, eventSubscribers);
        }
    }

    private <T extends TurboismEvent> void dispatch(T event, Subscription<? extends TurboismEvent> subscription) {
        if (!subscription.type().isInstance(event)) {
            return;
        }
        scheduler.dispatch(task(event), () -> deliver(event, subscription));
    }

    private PluginTask task(TurboismEvent event) {
        return new PluginTask(
            EVENT_TASK_TYPE,
            pluginId,
            event.getClass().getName(),
            DEFAULT_CAPABILITY
        );
    }

    private static <T extends TurboismEvent> void deliver(
        T event,
        Subscription<? extends TurboismEvent> subscription
    ) {
        subscription.deliver(event);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record Subscription<T extends TurboismEvent>(Class<T> type, Consumer<T> listener) {

        private void deliver(TurboismEvent event) {
            if (type.isInstance(event)) {
                listener.accept(type.cast(event));
            }
        }
    }
}
