package dev.turboism.runtime.log;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Process-wide route for framework diagnostics that must not leak into Cubism's native log. */
public final class RuntimeDiagnostics {

    public enum Level {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    @FunctionalInterface
    public interface Sink {
        void write(Level level, String component, String message, Throwable failure);
    }

    private static final Sink NONE = (level, component, message, failure) -> { };
    private static final AtomicReference<Sink> SINK = new AtomicReference<>(NONE);

    private RuntimeDiagnostics() {
    }

    /** Replaces the process-wide diagnostics sink; rejects a null sink. */
    public static void install(final Sink sink) {
        SINK.set(Objects.requireNonNull(sink, "sink"));
    }

    /** Resets the process-wide diagnostics sink to the no-op default. */
    public static void clear() {
        SINK.set(NONE);
    }

    /** Routes a TRACE diagnostic for the given component; skips on blank text. */
    public static void trace(final String component, final String message) {
        write(Level.TRACE, component, message, null);
    }

    /** Routes a DEBUG diagnostic for the given component; skips on blank text. */
    public static void debug(final String component, final String message) {
        write(Level.DEBUG, component, message, null);
    }

    /** Routes an INFO diagnostic for the given component; skips on blank text. */
    public static void info(final String component, final String message) {
        write(Level.INFO, component, message, null);
    }

    /** Routes a WARN diagnostic for the given component; skips on blank text. */
    public static void warn(final String component, final String message) {
        write(Level.WARN, component, message, null);
    }

    /** Routes an ERROR diagnostic with the optional failure for the given component. */
    public static void error(
        final String component,
        final String message,
        final Throwable failure
    ) {
        write(Level.ERROR, component, message, failure);
    }

    private static void write(
        final Level level,
        final String component,
        final String message,
        final Throwable failure
    ) {
        try {
            SINK.get().write(
                Objects.requireNonNull(level, "level"),
                requireText(component, "component"),
                requireText(message, "message"),
                failure
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never escape into Cubism or destabilize the host.
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
