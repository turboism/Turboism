package dev.turboism.test.event;

import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.plugin.Registration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Recording test fake for {@link EventBus}.
 */
public class RecordingEventBus implements EventBus {

    private final List<Subscription<?>> subscriptions = new CopyOnWriteArrayList<>();
    private final List<TurboismEvent> publishedEvents = new CopyOnWriteArrayList<>();

    private record Subscription<T extends TurboismEvent>(Class<T> type, Consumer<T> listener) {}

    @Override
    public <T extends TurboismEvent> Registration subscribe(final Class<T> type, final Consumer<T> listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listener, "listener");
        final Subscription<T> subscription = new Subscription<>(type, listener);
        subscriptions.add(subscription);
        return () -> subscriptions.remove(subscription);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends TurboismEvent> void publish(final T event) {
        Objects.requireNonNull(event, "event");
        publishedEvents.add(event);
        for (final Subscription<?> subscription : subscriptions) {
            if (subscription.type().isInstance(event)) {
                ((Consumer<T>) subscription.listener()).accept(event);
            }
        }
    }

    public List<TurboismEvent> publishedEvents() {
        return List.copyOf(publishedEvents);
    }

    public List<TurboismEvent> published() {
        return publishedEvents();
    }
}
