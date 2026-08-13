package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.hook.ParameterHooks;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Runtime-owned lifecycle coordinator for the semantic parameter set-value operation. */
public final class ParameterLifecycleCoordinator implements AutoCloseable {

    public static final String OPERATION_ID = "cubism.model.parameter.set-value";

    private final CopyOnWriteArrayList<Registration> plugins = new CopyOnWriteArrayList<>();
    private final LifecycleCallbackExecutor callbacks;
    private final Object registrationLock = new Object();
    private final ThreadLocal<Boolean> parameterWriteActive = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<NativeInvocation> nativeInvocation = new ThreadLocal<>();

    public ParameterLifecycleCoordinator() {
        this(new PluginWorkExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()));
    }

    public ParameterLifecycleCoordinator(final PluginWorkExecutorRegistry executors) {
        this.callbacks = new LifecycleCallbackExecutor("Parameter", executors);
    }

    public void register(final PluginHooks plugin) {
        final PluginHooks value = Objects.requireNonNull(plugin, "plugin");
        final Object token = new Object();
        synchronized (registrationLock) {
            plugins.removeIf(registration -> registration.plugin().descriptor().id().equals(value.descriptor().id()));
            callbacks.shutdown(value.descriptor().id());
            plugins.add(new Registration(token, value));
        }
    }

    void register(final Object token, final PluginHooks plugin) {
        synchronized (registrationLock) {
            plugins.add(new Registration(
                Objects.requireNonNull(token, "token"),
                Objects.requireNonNull(plugin, "plugin")
            ));
        }
    }

    public void unregister(final String pluginId) {
        final String id = requireText(pluginId, "pluginId");
        synchronized (registrationLock) {
            plugins.removeIf(registration -> registration.plugin().descriptor().id().equals(id));
            callbacks.shutdown(id);
        }
    }

    void unregister(final String pluginId, final Object token) {
        final String id = requireText(pluginId, "pluginId");
        final Object generation = Objects.requireNonNull(token, "token");
        synchronized (registrationLock) {
            final boolean removed = plugins.removeIf(registration ->
                registration.token() == generation
                    && registration.plugin().descriptor().id().equals(id)
            );
            if (removed && plugins.stream().noneMatch(registration ->
                registration.plugin().descriptor().id().equals(id)
            )) {
                callbacks.shutdown(id);
            }
        }
    }

    public void setValue(
        final Parameter parameter,
        final float requestedValue,
        final Consumer<Float> nativeOperation
    ) {
        Objects.requireNonNull(parameter, "parameter");
        Objects.requireNonNull(nativeOperation, "nativeOperation");
        if (parameterWriteActive.get()) {
            throw new IllegalStateException(
                "Recursive Cubism parameter set-value lifecycle is not allowed."
            );
        }
        parameterWriteActive.set(true);
        try {
            setValueGuarded(parameter, requestedValue, nativeOperation);
        } finally {
            parameterWriteActive.remove();
        }
    }

    NativeInvocation beginNative(final Parameter parameter, final float requestedValue) {
        Objects.requireNonNull(parameter, "parameter");
        if (parameterWriteActive.get()) {
            final NativeInvocation correlated = new NativeInvocation(
                parameter,
                requestedValue,
                parameter.getValue(),
                true
            );
            nativeInvocation.set(correlated);
            return correlated;
        }
        if (nativeInvocation.get() != null) {
            throw new IllegalStateException("Recursive native parameter lifecycle is not allowed.");
        }
        float effectiveValue = requestedValue;
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (!plugin.interceptAllowed()) continue;
            for (ParameterHooks hook : plugin.entrypoints()) {
                try {
                    final float transformed = hook.beforeSetParameterValue(parameter, effectiveValue);
                    if (Float.isFinite(transformed)) effectiveValue = transformed;
                    else plugin.logger().warn(
                        "Ignored non-finite beforeSetParameterValue result for " + OPERATION_ID
                    );
                } catch (Throwable failure) {
                    logHookFailure(plugin, "beforeSetParameterValue", failure);
                }
            }
        }
        final NativeInvocation invocation = new NativeInvocation(
            parameter,
            effectiveValue,
            parameter.getValue(),
            false
        );
        nativeInvocation.set(invocation);
        return invocation;
    }

    void completeNative(final NativeInvocation invocation, final boolean succeeded) {
        if (nativeInvocation.get() != invocation) {
            throw new IllegalStateException("Native parameter lifecycle token is not current.");
        }
        nativeInvocation.remove();
        if (!succeeded || invocation.correlated()) {
            return;
        }
        publishCompletion(
            invocation.parameter(),
            invocation.oldValue(),
            invocation.parameter().getValue()
        );
    }

    private void setValueGuarded(
        final Parameter parameter,
        final float requestedValue,
        final Consumer<Float> nativeOperation
    ) {
        float effectiveValue = requestedValue;
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (!plugin.interceptAllowed()) {
                continue;
            }
            for (ParameterHooks hook : plugin.entrypoints()) {
                try {
                    final float transformed = hook.beforeSetParameterValue(parameter, effectiveValue);
                    if (Float.isFinite(transformed)) {
                        effectiveValue = transformed;
                    } else {
                        plugin.logger().warn(
                            "Ignored non-finite beforeSetParameterValue result for " + OPERATION_ID
                        );
                    }
                } catch (Throwable failure) {
                    logHookFailure(plugin, "beforeSetParameterValue", failure);
                }
            }
        }

        final float oldValue = parameter.getValue();
        nativeOperation.accept(effectiveValue);
        final float finalValue = parameter.getValue();
        publishCompletion(parameter, oldValue, finalValue);
    }

    private void publishCompletion(
        final Parameter parameter,
        final float oldValue,
        final float finalValue
    ) {
        final boolean changed = Float.compare(oldValue, finalValue) != 0;
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (!plugin.observeAllowed()) {
                continue;
            }
            final List<? extends ParameterHooks> entrypoints = plugin.entrypoints();
            submit(registration, () -> {
                for (ParameterHooks hook : entrypoints) {
                    if (changed) {
                        try {
                            hook.onParameterValueChanged(parameter, oldValue, finalValue);
                        } catch (Throwable failure) {
                            logHookFailure(plugin, "onParameterValueChanged", failure);
                        }
                    }
                    try {
                        hook.afterSetParameterValue(parameter, finalValue);
                    } catch (Throwable failure) {
                        logHookFailure(plugin, "afterSetParameterValue", failure);
                    }
                }
            });
        }
    }

    /** Waits for callbacks already accepted by the bounded plugin executors. */
    public void awaitIdle() {
        callbacks.awaitIdle();
    }

    @Override
    public void close() {
        synchronized (registrationLock) {
            plugins.clear();
            callbacks.close();
        }
    }

    private void submit(final Registration registration, final Runnable callback) {
        synchronized (registrationLock) {
            if (!plugins.contains(registration)) {
                return;
            }
            callbacks.submit(
                registration.plugin().descriptor().id(),
                OPERATION_ID,
                callback
            );
        }
    }

    private static void logHookFailure(
        final PluginHooks plugin,
        final String phase,
        final Throwable failure
    ) {
        try {
            plugin.logger().error(
                "Cubism parameter lifecycle hook failed safely: " + phase,
                failure
            );
        } catch (Throwable ignored) {
            // Hook and diagnostic failures must not escape into the Cubism operation.
        }
    }

    private record Registration(Object token, PluginHooks plugin) { }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record PluginHooks(
        PluginDescriptor descriptor,
        List<? extends ParameterHooks> entrypoints,
        PluginLogger logger,
        boolean interceptAllowed,
        boolean observeAllowed
    ) {
        public PluginHooks(
            final PluginDescriptor descriptor,
            final List<? extends ParameterHooks> entrypoints,
            final PluginLogger logger
        ) {
            this(descriptor, entrypoints, logger, true, true);
        }

        public PluginHooks {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            entrypoints = List.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
            logger = Objects.requireNonNull(logger, "logger");
        }
    }

    record NativeInvocation(
        Parameter parameter,
        float effectiveValue,
        float oldValue,
        boolean correlated
    ) {
        NativeInvocation {
            parameter = Objects.requireNonNull(parameter, "parameter");
        }
    }
}
