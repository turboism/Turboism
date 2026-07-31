package dev.turboism.ui.context;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/** Fail-closed bridge from exact Cubism object-menu hooks to runtime policy. */
public final class NativeObjectContextMenuBridge {

    private static final String PROPERTY_PREFIX = "turboism.object-context-menu.";
    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();

    private NativeObjectContextMenuBridge() {
    }

    public static Registration install(final Handler handler) {
        final Handler requested = Objects.requireNonNull(handler, "handler");
        if (!HANDLER.compareAndSet(null, requested)) {
            throw new IllegalStateException("object context-menu bridge is already installed");
        }
        final Map<Location, BiFunction<Object, Object, Object>> callbacks = new EnumMap<>(Location.class);
        for (Location location : Location.values()) {
            callbacks.put(location, (menu, source) -> augment(menu, location.name(), source));
        }
        final Properties properties = System.getProperties();
        try {
            synchronized (properties) {
                for (Location location : Location.values()) {
                    if (properties.containsKey(propertyKey(location))) {
                        throw new IllegalStateException("object context-menu callback property is already installed");
                    }
                }
                callbacks.forEach((location, callback) -> properties.put(propertyKey(location), callback));
            }
        } catch (RuntimeException | Error failure) {
            HANDLER.compareAndSet(requested, null);
            throw failure;
        }
        return () -> {
            synchronized (properties) {
                callbacks.forEach((location, callback) -> properties.remove(propertyKey(location), callback));
            }
            HANDLER.compareAndSet(requested, null);
        };
    }

    static String propertyKey(final Location location) {
        return PROPERTY_PREFIX + Objects.requireNonNull(location, "location").name();
    }

    public static Object augment(
        final Object menu,
        final String locationName,
        final Object source
    ) {
        final Handler handler = HANDLER.get();
        if (handler == null || menu == null || locationName == null || source == null) {
            return menu;
        }
        try {
            final Location location = Location.valueOf(locationName);
            final Object result = handler.augment(menu, location, source);
            return result == null ? menu : result;
        } catch (Throwable failure) {
            System.err.println(
                "Turboism object context-menu augmentation failed safely: "
                    + failure.getClass().getName()
            );
            return menu;
        }
    }

    /** Combines exact native selection locals without exposing host collection types. */
    public static Object mergeSources(final Object first, final Object second) {
        final java.util.ArrayList<Object> merged = new java.util.ArrayList<>();
        addSources(merged, first);
        addSources(merged, second);
        return merged;
    }

    private static void addSources(final java.util.List<Object> target, final Object source) {
        if (source instanceof java.util.Collection<?> values) target.addAll(values);
        else if (source != null) target.add(source);
    }



    @FunctionalInterface
    public interface Handler {
        Object augment(Object menu, Location location, Object source);
    }
}
