package dev.turboism.runtime.log;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Process-wide route for framework diagnostics that must not leak into Cubism's native log. */
public final class RuntimeDiagnostics {

    /** Severity of one framework diagnostic record. */
    public enum Level {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    /** Where framework diagnostic records are delivered. */
    @FunctionalInterface
    public interface Sink {
        /**
         * Receives one diagnostic record.
         *
         * <p>Called from arbitrary threads, including premain-phase replay; a sink must be
         *   cheap and must not throw — the caller swallows its failures.</p>
         *
         * @param level the record severity
         * @param component the subsystem the record belongs to
         * @param message the human-readable record text
         * @param failure the associated throwable, or {@code null}
         */
        void write(Level level, String component, String message, Throwable failure);
    }

    private static final int PENDING_LIMIT = 256;
    private static final Sink NONE = (level, component, message, failure) -> { };
    private static final Object STATE_LOCK = new Object();
    private static final AtomicReference<Sink> SINK = new AtomicReference<>(NONE);
    private static final java.util.List<Entry> PENDING = new java.util.ArrayList<>();

    private RuntimeDiagnostics() {
    }

    /**
     * Replaces the process-wide diagnostics sink; rejects a null sink. Diagnostics emitted before
     * the first sink is installed are buffered (bounded by {@code PENDING_LIMIT}) and replayed to
     * the new sink in order, so premain-phase records are not silently dropped.
     */
    public static void install(final Sink sink) {
        final java.util.List<Entry> pending;
        synchronized (STATE_LOCK) {
            SINK.set(Objects.requireNonNull(sink, "sink"));
            pending = new java.util.ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (Entry entry : pending) {
            write(entry.level(), entry.component(), entry.message(), entry.failure());
        }
    }

    /** Resets the process-wide diagnostics sink to the no-op default and drops the buffer. */
    public static void clear() {
        synchronized (STATE_LOCK) {
            SINK.set(NONE);
            PENDING.clear();
        }
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
            final Level checkedLevel = Objects.requireNonNull(level, "level");
            final String checkedComponent = requireText(component, "component");
            final String checkedMessage = requireText(message, "message");
            final Sink sink;
            synchronized (STATE_LOCK) {
                sink = SINK.get();
                if (sink == NONE) {
                    if (PENDING.size() < PENDING_LIMIT) {
                        PENDING.add(
                            new Entry(checkedLevel, checkedComponent, checkedMessage, failure)
                        );
                    }
                    return;
                }
            }
            sink.write(checkedLevel, checkedComponent, checkedMessage, failure);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never escape into Cubism or destabilize the host.
        }
    }

    private record Entry(Level level, String component, String message, Throwable failure) {
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
