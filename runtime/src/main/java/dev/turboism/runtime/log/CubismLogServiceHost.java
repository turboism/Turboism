package dev.turboism.runtime.log;

import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.RuntimeTimerHandle;
import dev.turboism.core.runtime.RuntimeTimerSubmission;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.runtime.CubismLogBatchEvent;
import dev.turboism.sdk.runtime.CubismLogService;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Host adapter exposing Cubism's own log stream to plugins.
 *
 * <p>The adapter reaches Cubism's Log4j2 context reflectively: it locates the
 * appenders Cubism's log palette renders, wraps the log-palette appender with a
 * pre-render filter ({@link #setFilter(LogFilter)}), and publishes every log
 * entry to subscribers. Real-host readiness is not claimed here; the appender
 * topology is confirmed by an initial probe log before wrapping.</p>
 */
public final class CubismLogServiceHost implements CubismLogService, AutoCloseable {

    static final int DEFAULT_EVENT_QUEUE_CAPACITY = 256;
    static final int DEFAULT_EVENT_BATCH_SIZE = 32;
    static final int MAX_EVENT_MESSAGE_LENGTH = 1024;
    static final Duration DEFAULT_EVENT_FLUSH_DELAY = Duration.ofMillis(100);

    private static final String REDACTED_PATH = "<redacted-path>";
    private static final String REDACTED_URI = "<redacted-uri>";
    private static final String REDACTED_SECRET = "<redacted-secret>";
    private static final Pattern URI = Pattern.compile(
        "(?i)\\b(?:https?|file)://[^\\s\\\"']+"
    );
    private static final Pattern UNC_PATH = Pattern.compile(
        "\\\\\\\\[^\\s\\\"'<>]+(?:\\\\[^\\s\\\"'<>]+)+"
    );
    private static final Pattern WINDOWS_PATH = Pattern.compile(
        "(?i)(?<![A-Za-z0-9])(?:[A-Z]:[\\\\/])[^\\s\\\"'<>]+"
    );
    private static final Pattern HOME_PATH = Pattern.compile(
        "(?<![A-Za-z0-9_])~(?:[/\\\\])[^\\s\\\"'<>]+"
    );
    private static final Pattern UNIX_PATH = Pattern.compile(
        "(?<![A-Za-z0-9._~:/-])/(?:[^/\\s\\\"'<>]+/)*[^\\s\\\"'<>]+"
    );
    private static final Pattern AUTHORIZATION = Pattern.compile(
        "(?i)\\b(?:authorization\\s*[:=]\\s*|bearer\\s+)"
            + "[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
        "(?i)\\b(?:token|secret|password)\\s*[:=]\\s*[A-Za-z0-9._~+/=-]+"
    );

    private final List<Consumer<LogEntry>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<LogFilter> filter = new AtomicReference<>(LogFilter.all());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object eventLock = new Object();
    private final ArrayDeque<CubismLogBatchEvent.Entry> eventQueue = new ArrayDeque<>();
    private final int eventQueueCapacity;
    private final int eventBatchSize;
    private final Duration eventFlushDelay;
    private RuntimeEventBroker eventBroker;
    private RuntimeScheduler eventScheduler;
    private RuntimeTimerHandle eventFlushTimer;
    private long droppedEventEntries;
    private volatile Object wrappedAppender;
    private volatile Object originalAppender;
    private volatile ClassLoader hostClassLoader;

    public CubismLogServiceHost() {
        this(
            DEFAULT_EVENT_QUEUE_CAPACITY,
            DEFAULT_EVENT_BATCH_SIZE,
            DEFAULT_EVENT_FLUSH_DELAY
        );
    }

    CubismLogServiceHost(
        final int eventQueueCapacity,
        final int eventBatchSize,
        final Duration eventFlushDelay
    ) {
        if (eventQueueCapacity < 1) {
            throw new IllegalArgumentException("eventQueueCapacity must be positive");
        }
        if (eventBatchSize < 1 || eventBatchSize > eventQueueCapacity) {
            throw new IllegalArgumentException(
                "eventBatchSize must be positive and no greater than eventQueueCapacity"
            );
        }
        this.eventQueueCapacity = eventQueueCapacity;
        this.eventBatchSize = eventBatchSize;
        this.eventFlushDelay = Objects.requireNonNull(eventFlushDelay, "eventFlushDelay");
        if (eventFlushDelay.isNegative() || eventFlushDelay.isZero()) {
            throw new IllegalArgumentException("eventFlushDelay must be positive");
        }
    }

    /**
     * Attaches the session event broker used for privacy-safe batched observations.
     * Reattachment discards queued entries owned by the previous composition.
     */
    public void attachEventBroker(
        final RuntimeEventBroker broker,
        final RuntimeScheduler scheduler
    ) {
        synchronized (eventLock) {
            requireOpen();
            cancelEventFlushLocked();
            eventQueue.clear();
            droppedEventEntries = 0L;
            eventBroker = Objects.requireNonNull(broker, "broker");
            eventScheduler = Objects.requireNonNull(scheduler, "scheduler");
        }
    }

    /** Connects to Cubism's Log4j2 context. Logs the appender topology for diagnosis. */
    public synchronized void connect(final ClassLoader hostClassLoader) {
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        if (closed.get()) {
            return;
        }
        try {
            final Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager", true, hostClassLoader);
            final Object context = logManager.getMethod("getContext", boolean.class)
                .invoke(null, false);
            final Class<?> loggerContextClass = Class.forName(
                "org.apache.logging.log4j.core.LoggerContext", true, hostClassLoader);
            final Object configuration = loggerContextClass.getMethod("getConfiguration").invoke(context);
            final Class<?> configurationClass = configuration.getClass();
            final Object rootLoggerConfig = configurationClass.getMethod("getRootLogger").invoke(configuration);
            final Object appenders = rootLoggerConfig.getClass()
                .getMethod("getAppenders").invoke(rootLoggerConfig);
            final String topology = describeAppenders(appenders);
            System.out.println("Turboism CubismLogServiceHost: appender topology: " + topology);
            final Object logPaneAppender = appenders instanceof java.util.Map<?, ?> map
                ? map.get("CTextPane")
                : null;
            if (logPaneAppender == wrappedAppender) {
                return;
            }
            wrappedAppender = null;
            originalAppender = null;
            if (logPaneAppender != null) {
                wrapLogPaneAppender(context, rootLoggerConfig, logPaneAppender, hostClassLoader);
            } else {
                System.out.println("Turboism CubismLogServiceHost: CTextPane appender not found; "
                    + "pre-render log filtering unavailable");
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            System.out.println("Turboism CubismLogServiceHost: log4j probe failed: "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    /**
     * Replaces Cubism's log-palette appender (CTextPaneAppender) with a proxy that
     * drops non-matching events before they reach the palette (pre-render filter)
     * and publishes every event to subscribers with the real log4j level.
     */
    private void wrapLogPaneAppender(
        final Object loggerContext,
        final Object rootLoggerConfig,
        final Object original,
        final ClassLoader hostClassLoader
    ) throws ReflectiveOperationException {
        final Class<?> appenderInterface = Class.forName(
            "org.apache.logging.log4j.core.Appender", true, hostClassLoader);
        final Object delegate = original;
        final Object proxy = Proxy.newProxyInstance(
            hostClassLoader,
            new Class<?>[] {appenderInterface},
            (p, method, args) -> {
                final String name = method.getName();
                switch (name) {
                    case "append" -> {
                        final Object event = args[0];
                        if (event != null) {
                            final LogEntry entry = toEntry(event);
                            publish(entry.level(), entry.message(), entry.timestampNanos());
                            if (filter.get().matches(entry)) {
                                try {
                                    method.invoke(delegate, args);
                                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                                    System.out.println("Turboism CubismLogServiceHost: log pane append failed: "
                                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                                }
                            }
                        }
                        return null;
                    }
                    case "getName" -> {
                        return method.invoke(delegate, args);
                    }
                    case "getLayout", "getFilter", "getIgnoreExceptions", "isStarted",
                         "isStopped", "getState", "getHandler", "start", "stop",
                         "setIgnoreExceptions" -> {
                        return method.invoke(delegate, args);
                    }
                    default -> {
                        return method.invoke(delegate, args);
                    }
                }
            });
        rootLoggerConfig.getClass().getMethod("removeAppender", String.class)
            .invoke(rootLoggerConfig, "CTextPane");
        final java.lang.reflect.Method addAppender = findAddAppender(rootLoggerConfig.getClass(), appenderInterface);
        addAppender.invoke(rootLoggerConfig, proxy, null, null);
        // Commit the configuration change; without this log4j2 keeps routing to the removed appender.
        final Class<?> loggerContextClass = Class.forName(
            "org.apache.logging.log4j.core.LoggerContext", true, hostClassLoader);
        loggerContextClass.getMethod("updateLoggers").invoke(loggerContext);
        wrappedAppender = proxy;
        originalAppender = original;
        System.out.println("Turboism CubismLogServiceHost: CTextPane appender wrapped for pre-render filtering");
    }

    /** Converts a log4j2 LogEvent into a typed log entry (real level, no text guessing). */
    private LogEntry toEntry(final Object event) {
        try {
            final Object level = event.getClass().getMethod("getLevel").invoke(event);
            final String levelName = String.valueOf(level.getClass().getMethod("name").invoke(level));
            final Object messageObject = event.getClass().getMethod("getMessage").invoke(event);
            final String message = messageObject == null ? "" : String.valueOf(messageObject);
            final long timestamp = (Long) event.getClass().getMethod("getTimeMillis").invoke(event);
            return new LogEntry(toSdkLevel(levelName), message, timestamp * 1_000_000L);
        } catch (ReflectiveOperationException | LinkageError failure) {
            return new LogEntry(LogLevel.INFO, String.valueOf(event), System.nanoTime());
        }
    }

    private static LogLevel toSdkLevel(final String log4jLevel) {
        return switch (log4jLevel) {
            case "TRACE" -> LogLevel.TRACE;
            case "DEBUG" -> LogLevel.DEBUG;
            case "WARN" -> LogLevel.WARN;
            case "ERROR" -> LogLevel.ERROR;
            case "FATAL" -> LogLevel.FATAL;
            default -> LogLevel.INFO;
        };
    }

    private static String describeAppenders(final Object appenders) {
        if (!(appenders instanceof java.util.Map<?, ?> map)) {
            return String.valueOf(appenders);
        }
        final StringBuilder builder = new StringBuilder();
        for (var entry : map.entrySet()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(
                entry.getValue() == null ? "null" : entry.getValue().getClass().getName());
        }
        return builder.toString();
    }

    @Override
    public Registration subscribe(final Consumer<LogEntry> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void setFilter(final LogFilter filter) {
        this.filter.set(Objects.requireNonNull(filter, "filter"));
    }

    @Override
    public LogFilter filter() {
        return filter.get();
    }

    /** Publishes one entry to subscribers (unfiltered stream). */
    public void publish(final LogLevel level, final String message, final long timestampNanos) {
        if (closed.get()) {
            return;
        }
        final LogEntry entry = new LogEntry(level, message, timestampNanos);
        for (Consumer<LogEntry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (RuntimeException ignored) {
                // A misbehaving subscriber must not break the log stream.
            }
        }
        enqueueEventObservation(entry);
    }

    private void enqueueEventObservation(final LogEntry entry) {
        synchronized (eventLock) {
            if (closed.get() || eventBroker == null || eventScheduler == null) {
                return;
            }
            if (eventQueue.size() >= eventQueueCapacity) {
                droppedEventEntries++;
                scheduleEventFlushLocked();
                return;
            }
            eventQueue.addLast(new CubismLogBatchEvent.Entry(
                entry.level(),
                redactAndBound(entry.message()),
                Math.max(0L, entry.timestampNanos())
            ));
            scheduleEventFlushLocked();
        }
    }

    private void scheduleEventFlushLocked() {
        if (eventFlushTimer != null || eventScheduler == null) {
            return;
        }
        final RuntimeTimerSubmission submission = eventScheduler.schedule(
            eventFlushDelay,
            this::flushEventBatch
        );
        if (submission.accepted()) {
            eventFlushTimer = submission.handle();
        } else {
            droppedEventEntries += eventQueue.size();
            eventQueue.clear();
        }
    }

    private void flushEventBatch() {
        final CubismLogBatchEvent batch;
        synchronized (eventLock) {
            eventFlushTimer = null;
            if (closed.get() || eventBroker == null) {
                eventQueue.clear();
                droppedEventEntries = 0L;
                return;
            }
            batch = drainEventBatchLocked();
            if (!eventQueue.isEmpty() || droppedEventEntries > 0L) {
                scheduleEventFlushLocked();
            }
        }
        publishEventBatch(batch);
    }

    private CubismLogBatchEvent drainEventBatchLocked() {
        if (eventQueue.isEmpty() && droppedEventEntries == 0L) {
            return null;
        }
        final int count = Math.min(eventBatchSize, eventQueue.size());
        final List<CubismLogBatchEvent.Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(eventQueue.removeFirst());
        }
        final long dropped = droppedEventEntries;
        droppedEventEntries = 0L;
        cancelEventFlushLocked();
        return new CubismLogBatchEvent(entries, dropped);
    }

    private void publishEventBatch(final CubismLogBatchEvent batch) {
        if (batch == null || closed.get()) {
            return;
        }
        final RuntimeEventBroker broker;
        synchronized (eventLock) {
            broker = eventBroker;
        }
        if (broker != null) {
            try {
                broker.publishRuntime(batch);
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Observation delivery cannot break Cubism's host log stream.
            }
        }
    }

    private void cancelEventFlushLocked() {
        final RuntimeTimerHandle timer = eventFlushTimer;
        eventFlushTimer = null;
        if (timer != null) {
            timer.cancel();
        }
    }

    private static String redactAndBound(final String message) {
        String sanitized = Objects.requireNonNull(message, "message");
        sanitized = URI.matcher(sanitized).replaceAll(REDACTED_URI);
        sanitized = UNC_PATH.matcher(sanitized).replaceAll(REDACTED_PATH);
        sanitized = WINDOWS_PATH.matcher(sanitized).replaceAll(REDACTED_PATH);
        sanitized = HOME_PATH.matcher(sanitized).replaceAll(REDACTED_PATH);
        sanitized = UNIX_PATH.matcher(sanitized).replaceAll(REDACTED_PATH);
        sanitized = AUTHORIZATION.matcher(sanitized).replaceAll(REDACTED_SECRET);
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized).replaceAll(REDACTED_SECRET);
        return sanitized.length() <= MAX_EVENT_MESSAGE_LENGTH
            ? sanitized
            : sanitized.substring(0, MAX_EVENT_MESSAGE_LENGTH);
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Cubism log service is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        listeners.clear();
        synchronized (eventLock) {
            cancelEventFlushLocked();
            eventQueue.clear();
            droppedEventEntries = 0L;
            eventBroker = null;
            eventScheduler = null;
        }
        if (wrappedAppender != null && originalAppender != null && hostClassLoader != null) {
            try {
                final Class<?> logManager = Class.forName(
                    "org.apache.logging.log4j.LogManager", true, hostClassLoader);
                final Object context = logManager.getMethod("getContext", boolean.class)
                    .invoke(null, false);
                final Class<?> loggerContextClass = Class.forName(
                    "org.apache.logging.log4j.core.LoggerContext", true, hostClassLoader);
                final Object configuration = loggerContextClass.getMethod("getConfiguration").invoke(context);
                final Object rootLoggerConfig = configuration.getClass()
                    .getMethod("getRootLogger").invoke(configuration);
                final Object appenders = rootLoggerConfig.getClass().getMethod("getAppenders").invoke(rootLoggerConfig);
                if (appenders instanceof java.util.Map<?, ?> map && map.get("CTextPane") == wrappedAppender) {
                    rootLoggerConfig.getClass().getMethod("removeAppender", String.class)
                        .invoke(rootLoggerConfig, "CTextPane");
                    findAddAppender(rootLoggerConfig.getClass(), appenderInterface(hostClassLoader))
                        .invoke(rootLoggerConfig, originalAppender, null, null);
                    loggerContextClass.getMethod("updateLoggers").invoke(context);
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Restore is best-effort on close.
            }
        }
        wrappedAppender = null;
        originalAppender = null;
        hostClassLoader = null;
    }

    private static Class<?> appenderInterface(final ClassLoader loader) throws ClassNotFoundException {
        return Class.forName("org.apache.logging.log4j.core.Appender", true, loader);
    }

    private static java.lang.reflect.Method findAddAppender(
        final Class<?> loggerConfigType,
        final Class<?> appenderInterface
    ) throws NoSuchMethodException {
        for (java.lang.reflect.Method method : loggerConfigType.getMethods()) {
            if (!"addAppender".equals(method.getName())) {
                continue;
            }
            final Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 3 && parameterTypes[0].isAssignableFrom(appenderInterface)) {
                return method;
            }
        }
        throw new NoSuchMethodException("LoggerConfig.addAppender(Appender, Level, Filter)");
    }

}
