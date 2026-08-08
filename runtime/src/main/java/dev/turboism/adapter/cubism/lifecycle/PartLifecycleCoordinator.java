package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.hook.PartHooks;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Runtime-owned lifecycle coordinator for the semantic Part opacity operation. */
public final class PartLifecycleCoordinator implements AutoCloseable {

    public static final String OPERATION_ID = "cubism.model.part.set-opacity";
    public static final String NAME_OPERATION_ID = "cubism.model.part.set-name";

    private final CopyOnWriteArrayList<Registration> plugins = new CopyOnWriteArrayList<>();
    private final LifecycleCallbackExecutor callbacks;
    private final Object registrationLock = new Object();
    private final ThreadLocal<Boolean> partWriteActive = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> partNameWriteActive = ThreadLocal.withInitial(() -> false);

    public PartLifecycleCoordinator() {
        this(new PluginWorkExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()));
    }

    public PartLifecycleCoordinator(final PluginWorkExecutorRegistry executors) {
        this.callbacks = new LifecycleCallbackExecutor("Part", executors);
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

    public void setOpacity(
        final Part part,
        final float requestedOpacity,
        final Consumer<Float> nativeOperation
    ) {
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(nativeOperation, "nativeOperation");
        if (partWriteActive.get()) {
            throw new IllegalStateException("Recursive Cubism Part opacity lifecycle is not allowed.");
        }
        partWriteActive.set(true);
        try {
            setOpacityGuarded(part, requestedOpacity, nativeOperation);
        } finally {
            partWriteActive.remove();
        }
    }

    public void setName(
        final Part part,
        final String requestedName,
        final Consumer<String> nativeOperation
    ) {
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(nativeOperation, "nativeOperation");
        if (partNameWriteActive.get()) {
            throw new IllegalStateException("Recursive Cubism Part name lifecycle is not allowed.");
        }
        partNameWriteActive.set(true);
        try {
            setNameGuarded(part, requestedName, nativeOperation);
        } finally {
            partNameWriteActive.remove();
        }
    }

    private void setOpacityGuarded(
        final Part part,
        final float requestedOpacity,
        final Consumer<Float> nativeOperation
    ) {
        float effectiveOpacity = requestedOpacity;
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (!plugin.interceptAllowed()) continue;
            for (PartHooks hook : plugin.entrypoints()) {
                try {
                    final float transformed = hook.beforeSetPartOpacity(part, effectiveOpacity);
                    if (Float.isFinite(transformed)) effectiveOpacity = transformed;
                    else plugin.logger().warn(
                        "Ignored non-finite beforeSetPartOpacity result for " + OPERATION_ID
                    );
                } catch (Throwable failure) {
                    logHookFailure(plugin, "beforeSetPartOpacity", failure);
                }
            }
        }

        if (!Float.isFinite(effectiveOpacity)) {
            throw new IllegalArgumentException("Part opacity must be finite.");
        }
        final float oldOpacity = part.getOpacity();
        nativeOperation.accept(effectiveOpacity);
        final float finalOpacity = part.getOpacity();
        publishCompletion(part, oldOpacity, finalOpacity);
    }

    private void setNameGuarded(
        final Part part,
        final String requestedName,
        final Consumer<String> nativeOperation
    ) {
        String effectiveName = requireName(requestedName);
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (!plugin.interceptAllowed()) continue;
            for (PartHooks hook : plugin.entrypoints()) {
                try {
                    effectiveName = requireName(hook.beforeSetPartName(part, effectiveName));
                } catch (Throwable failure) {
                    logHookFailure(plugin, "beforeSetPartName", failure);
                }
            }
        }
        final String oldName = part.name();
        nativeOperation.accept(effectiveName);
        final String finalName = part.name();
        publishNameCompletion(part, oldName, finalName);
    }

    private void publishCompletion(
        final Part part,
        final float oldOpacity,
        final float finalOpacity
    ) {
        final boolean changed = Float.compare(oldOpacity, finalOpacity) != 0;
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (!plugin.observeAllowed()) continue;
            final List<? extends PartHooks> entrypoints = plugin.entrypoints();
            submit(registration, () -> {
                for (PartHooks hook : entrypoints) {
                    if (changed) {
                        try {
                            hook.onPartOpacityChanged(part, oldOpacity, finalOpacity);
                        } catch (Throwable failure) {
                            logHookFailure(plugin, "onPartOpacityChanged", failure);
                        }
                    }
                    try {
                        hook.afterSetPartOpacity(part, finalOpacity);
                    } catch (Throwable failure) {
                        logHookFailure(plugin, "afterSetPartOpacity", failure);
                    }
                }
            });
        }
    }

    private void publishNameCompletion(
        final Part part,
        final String oldName,
        final String finalName
    ) {
        final boolean changed = !oldName.equals(finalName);
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (!plugin.observeAllowed()) continue;
            final List<? extends PartHooks> entrypoints = plugin.entrypoints();
            submit(registration, NAME_OPERATION_ID, () -> {
                for (PartHooks hook : entrypoints) {
                    if (changed) {
                        try {
                            hook.onPartNameChanged(part, oldName, finalName);
                        } catch (Throwable failure) {
                            logHookFailure(plugin, "onPartNameChanged", failure);
                        }
                    }
                    try {
                        hook.afterSetPartName(part, finalName);
                    } catch (Throwable failure) {
                        logHookFailure(plugin, "afterSetPartName", failure);
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
        submit(registration, OPERATION_ID, callback);
    }

    private void submit(
        final Registration registration,
        final String operationId,
        final Runnable callback
    ) {
        synchronized (registrationLock) {
            if (!plugins.contains(registration)) {
                return;
            }
            callbacks.submit(
                registration.plugin().descriptor().id(),
                operationId,
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
            plugin.logger().error("Cubism Part lifecycle hook failed safely: " + phase, failure);
        } catch (Throwable ignored) {
            // Hook and diagnostic failures must not escape into the Cubism operation.
        }
    }

    private record Registration(Object token, PluginHooks plugin) { }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String requireName(final String value) {
        final String name = Objects.requireNonNull(value, "name");
        if (name.isBlank()) throw new IllegalArgumentException("Part name must not be blank.");
        return name;
    }

    public record PluginHooks(
        PluginDescriptor descriptor,
        List<? extends PartHooks> entrypoints,
        PluginLogger logger,
        boolean interceptAllowed,
        boolean observeAllowed
    ) {
        public PluginHooks(
            final PluginDescriptor descriptor,
            final List<? extends PartHooks> entrypoints,
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
}
