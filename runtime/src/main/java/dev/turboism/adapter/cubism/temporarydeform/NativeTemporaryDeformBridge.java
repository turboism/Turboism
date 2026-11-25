package dev.turboism.adapter.cubism.temporarydeform;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Loader-neutral System-properties bridge connecting instrumented Cubism temporary deform hooks to
 * {@link TemporaryDeformCoordinator}.
 */
public final class NativeTemporaryDeformBridge {

    static final String ON_UNBIND_PROPERTY = "turboism.temporary-deform.on-unbind";
    static final String ON_SWITCH_NATIVE_PROPERTY = "turboism.temporary-deform.on-switch-native";
    static final String ON_TOOL_DETAIL_INIT_PROPERTY = "turboism.temporary-deform.on-tool-detail-init";
    static final String ON_TOOL_DETAIL_REFRESH_PROPERTY = "turboism.temporary-deform.on-tool-detail-refresh";

    private static final String LOG_COMPONENT = "temporary-deform";
    private static final AtomicReference<Handler> ACTIVE_HANDLER = new AtomicReference<>();
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();
    private static final AtomicLong ACTIVE_GENERATION = new AtomicLong();

    private NativeTemporaryDeformBridge() {}

    /** Installs JDK-only callback slots into system properties and returns a fenced registration. */
    public static Registration install(final Handler handler) {
        final Handler requested = Objects.requireNonNull(handler, "handler");
        final long generation =
                NEXT_GENERATION.updateAndGet(previous -> previous == Long.MAX_VALUE ? 1L : previous + 1L);
        if (!ACTIVE_HANDLER.compareAndSet(null, requested)) {
            throw new IllegalStateException("temporary deform bridge is already installed");
        }
        final BiConsumer<Object, Object> onUnbind = (manager, actionContext) -> {
            if (isGenerationActive(generation)) {
                contain(() -> requested.onHostUnbind(manager, actionContext));
            }
        };
        final BiConsumer<Object, Object> onSwitchNative = (manager, handlerType) -> {
            if (isGenerationActive(generation)) {
                contain(() -> requested.onHostSwitchToNative(manager, handlerType));
            }
        };
        final BiConsumer<Object, Object> onToolDetailInit = (panel, completePack) -> {
            if (isGenerationActive(generation)) {
                contain(() -> requested.onToolDetailInit(panel, completePack));
            }
        };
        final BiConsumer<Object, Object> onToolDetailRefresh = (panel, modelingView) -> {
            if (isGenerationActive(generation)) {
                contain(() -> requested.onToolDetailRefresh(panel, modelingView));
            }
        };
        final Properties properties = System.getProperties();
        try {
            synchronized (properties) {
                if (properties.containsKey(ON_UNBIND_PROPERTY)
                        || properties.containsKey(ON_SWITCH_NATIVE_PROPERTY)
                        || properties.containsKey(ON_TOOL_DETAIL_INIT_PROPERTY)
                        || properties.containsKey(ON_TOOL_DETAIL_REFRESH_PROPERTY)) {
                    throw new IllegalStateException("temporary deform bridge callback properties already exist");
                }
                properties.put(ON_UNBIND_PROPERTY, onUnbind);
                properties.put(ON_SWITCH_NATIVE_PROPERTY, onSwitchNative);
                properties.put(ON_TOOL_DETAIL_INIT_PROPERTY, onToolDetailInit);
                properties.put(ON_TOOL_DETAIL_REFRESH_PROPERTY, onToolDetailRefresh);
                ACTIVE_GENERATION.set(generation);
            }
        } catch (RuntimeException | Error installFailure) {
            // @containment-exempt: cleans up partially installed bridge properties before rethrowing
            ACTIVE_GENERATION.compareAndSet(generation, 0L);
            Throwable toThrow = installFailure;
            synchronized (properties) {
                toThrow = removeExpected(properties, ON_UNBIND_PROPERTY, onUnbind, toThrow);
                toThrow = removeExpected(properties, ON_SWITCH_NATIVE_PROPERTY, onSwitchNative, toThrow);
                toThrow = removeExpected(properties, ON_TOOL_DETAIL_INIT_PROPERTY, onToolDetailInit, toThrow);
                toThrow = removeExpected(properties, ON_TOOL_DETAIL_REFRESH_PROPERTY, onToolDetailRefresh, toThrow);
            }
            ACTIVE_HANDLER.compareAndSet(requested, null);
            throw FatalErrors.propagate(toThrow);
        }
        return () -> {
            ACTIVE_GENERATION.compareAndSet(generation, 0L);
            Throwable cleanupFailure = null;
            synchronized (properties) {
                cleanupFailure = removeExpected(properties, ON_UNBIND_PROPERTY, onUnbind, cleanupFailure);
                cleanupFailure = removeExpected(properties, ON_SWITCH_NATIVE_PROPERTY, onSwitchNative, cleanupFailure);
                cleanupFailure =
                        removeExpected(properties, ON_TOOL_DETAIL_INIT_PROPERTY, onToolDetailInit, cleanupFailure);
                cleanupFailure = removeExpected(
                        properties, ON_TOOL_DETAIL_REFRESH_PROPERTY, onToolDetailRefresh, cleanupFailure);
            }
            ACTIVE_HANDLER.compareAndSet(requested, null);
            rethrowCleanupFailure(cleanupFailure);
        };
    }

    private static boolean isGenerationActive(final long generation) {
        return generation != 0L && ACTIVE_GENERATION.get() == generation;
    }

    private static void contain(final Runnable callback) {
        try {
            callback.run();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(
                    LOG_COMPONENT,
                    "Temporary deform bridge callback failed safely: "
                            + failure.getClass().getSimpleName());
        }
    }

    private static Throwable removeExpected(
            final Properties properties, final String key, final Object expected, final Throwable prior) {
        try {
            properties.remove(key, expected);
            return prior;
        } catch (RuntimeException | Error failure) {
            // @containment-exempt: cleanup failure merged and rethrown with fatal prioritized
            return FatalErrors.mergeForCleanup(prior, failure);
        }
    }

    private static void rethrowCleanupFailure(final Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /** Coordinator callbacks dispatched from instrumented host temporary-deform methods. */
    public interface Handler {

        /** Notifies the coordinator that the native manager is unbinding any temporary handler. */
        void onHostUnbind(Object manager, Object actionContext);

        /** Notifies the coordinator that the native manager is switching to a built-in handler type. */
        void onHostSwitchToNative(Object manager, Object handlerType);

        /** Notifies the coordinator after the native Tool Details panel initializes its controls. */
        void onToolDetailInit(Object toolDetailPanel, Object completePack);

        /** Notifies the coordinator after the native Tool Details panel refreshes for a modeling view. */
        void onToolDetailRefresh(Object toolDetailPanel, Object modelingView);
    }
}
