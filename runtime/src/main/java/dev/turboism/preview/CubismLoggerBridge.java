package dev.turboism.preview;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;

/** Routes Turboism records through Cubism's existing Log4j2 context and appenders. */
final class CubismLoggerBridge implements PreviewLog.Sink {

    private static final String LOGGER_NAME = "dev.turboism";

    private final Object logger;
    private final Method[] plainMethods;
    private final Method[] failureMethods;
    private final Runnable cleanup;

    CubismLoggerBridge(final Object logger) {
        this(logger, resolveUnchecked(Objects.requireNonNull(logger, "logger").getClass()), () -> {});
    }

    private CubismLoggerBridge(final Object logger, final Methods methods, final Runnable cleanup) {
        this.logger = Objects.requireNonNull(logger, "logger");
        plainMethods = methods.plain();
        failureMethods = methods.failure();
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    static CubismLoggerBridge connect(final ClassLoader hostClassLoader) throws ReflectiveOperationException {
        final ClassLoader loader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        final Class<?> loggerApi = Class.forName("org.apache.logging.log4j.Logger", true, loader);
        final Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager", true, loader);
        final Object logger = logManager.getMethod("getLogger", String.class).invoke(null, LOGGER_NAME);
        final Methods methods = resolve(loggerApi);

        final Class<?> levelType = Class.forName("org.apache.logging.log4j.Level", true, loader);
        final Class<?> configurator = Class.forName(
            "org.apache.logging.log4j.core.config.Configurator",
            true,
            loader
        );
        final Method setLevel = configurator.getMethod("setLevel", String.class, levelType);
        final Object previousLevel = loggerApi.getMethod("getLevel").invoke(logger);
        final Runnable cleanup = () -> invokeLevel(setLevel, previousLevel);

        setLevel.invoke(null, LOGGER_NAME, levelType.getField("ALL").get(null));
        return new CubismLoggerBridge(logger, methods, cleanup);
    }

    @Override
    public void write(final PreviewLog.Level level, final String line, final Throwable failure) {
        final Method method = failure == null ? plainMethods[level.ordinal()] : failureMethods[level.ordinal()];
        try {
            if (failure == null) {
                method.invoke(logger, line);
            } else {
                method.invoke(logger, line, failure);
            }
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cubism logger method is inaccessible", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Cubism logger rejected a Turboism record", exception.getCause());
        }
    }

    @Override
    public void close() {
        cleanup.run();
    }

    private static Methods resolveUnchecked(final Class<?> loggerType) {
        try {
            return resolve(loggerType);
        } catch (NoSuchMethodException exception) {
            throw new IllegalArgumentException("logger does not expose every supported level", exception);
        }
    }

    private static Methods resolve(final Class<?> loggerType) throws NoSuchMethodException {
        final PreviewLog.Level[] levels = PreviewLog.Level.values();
        final Method[] plain = new Method[levels.length];
        final Method[] failure = new Method[levels.length];
        for (PreviewLog.Level level : levels) {
            final String name = level.name().toLowerCase(Locale.ROOT);
            plain[level.ordinal()] = loggerType.getMethod(name, String.class);
            failure[level.ordinal()] = loggerType.getMethod(name, String.class, Throwable.class);
        }
        return new Methods(plain, failure);
    }

    private static void invokeLevel(final Method setLevel, final Object level) {
        try {
            setLevel.invoke(null, LOGGER_NAME, level);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            System.err.println("Turboism could not restore the Cubism logger level: " + exception.getMessage());
        }
    }

    private record Methods(Method[] plain, Method[] failure) {}
}
