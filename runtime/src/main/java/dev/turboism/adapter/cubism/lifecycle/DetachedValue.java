package dev.turboism.adapter.cubism.lifecycle;

import java.util.function.Supplier;

/** Captures optional event metadata without retaining a getter or a host exception. */
record DetachedValue<T>(String property, T value, Availability availability) {
    enum Availability { AVAILABLE, UNSUPPORTED, UNAVAILABLE }

    static <T> DetachedValue<T> capture(final String property, final Supplier<T> read) {
        try {
            return new DetachedValue<>(property, read.get(), Availability.AVAILABLE);
        } catch (UnsupportedOperationException unsupported) {
            return new DetachedValue<>(property, null, Availability.UNSUPPORTED);
        } catch (IllegalStateException unavailable) {
            return new DetachedValue<>(property, null, Availability.UNAVAILABLE);
        }
    }

    T get() {
        if (availability == Availability.UNSUPPORTED) {
            throw new UnsupportedOperationException("Event snapshot property is unsupported: " + property);
        }
        if (availability == Availability.UNAVAILABLE) {
            throw new IllegalStateException("Event snapshot property is unavailable: " + property);
        }
        return value;
    }
}
