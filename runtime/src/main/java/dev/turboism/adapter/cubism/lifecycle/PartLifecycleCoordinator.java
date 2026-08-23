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

    /**
     * Registers a plugin's Part hooks, replacing any earlier registration under the same plugin id and
     * shutting down that plugin's pending callback queue first.
     *
     * @param plugin descriptor, entrypoints and logger for the registering plugin
     * @throws NullPointerException when {@code plugin} is null
     */
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

    /**
     * Removes every registration owned by the given plugin id and shuts down its callback queue.
     * Unknown ids are ignored.
     *
     * @param pluginId id of the plugin to detach
     * @throws NullPointerException when {@code pluginId} is null
     * @throws IllegalArgumentException when {@code pluginId} is blank
     */
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

    /**
     * Runs the Part opacity write pipeline: interceptors may rewrite the requested value in
     * registration order, the surviving value is passed to {@code nativeOperation}, and the
     * before/after opacity read back from the Part is published to observers asynchronously.
     * Non-finite interceptor results are ignored and logged; hook failures never propagate.
     *
     * <p>Runs on the calling host thread and refuses re-entry from within another Part opacity write.
     *
     * @param part the Part being written
     * @param requestedOpacity opacity requested by the caller, before interception
     * @param nativeOperation performs the actual Editor-side write with the effective opacity
     * @throws NullPointerException when {@code part} or {@code nativeOperation} is null
     * @throws IllegalArgumentException when the effective opacity is not finite
     * @throws IllegalStateException when invoked from within another Part opacity write on this thread
     */
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

    /**
     * Runs the Part rename pipeline: interceptors may substitute the name, and a null or blank result
     * aborts that hook (logged) leaving the previous effective name in place. The name read back from
     * the Part after the write is published to observers asynchronously.
     *
     * <p>Runs on the calling host thread and refuses re-entry from within another Part rename.
     *
     * @param part the Part being renamed
     * @param requestedName name requested by the caller, before interception; must not be blank
     * @param nativeOperation performs the actual Editor-side write with the effective name
     * @throws NullPointerException when {@code part}, {@code requestedName} or {@code nativeOperation}
     *     is null
     * @throws IllegalArgumentException when {@code requestedName} is blank
     * @throws IllegalStateException when invoked from within another Part rename on this thread
     */
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

    /**
     * One plugin's participation in the Part lifecycle.
     *
     * @param descriptor identity of the owning plugin, used as the registration key
     * @param entrypoints the plugin's Part hooks, defensively copied and immutable
     * @param logger sink for hook failures raised by this plugin
     * @param interceptAllowed whether this plugin's {@code before*} hooks may rewrite requested values
     * @param observeAllowed whether this plugin receives asynchronous {@code after*}/{@code on*} callbacks
     */
    public record PluginHooks(
        PluginDescriptor descriptor,
        List<? extends PartHooks> entrypoints,
        PluginLogger logger,
        boolean interceptAllowed,
        boolean observeAllowed
    ) {
        /**
         * Registers a plugin with both interception and observation permitted.
         *
         * @param descriptor identity of the owning plugin
         * @param entrypoints the plugin's Part hooks
         * @param logger sink for hook failures raised by this plugin
         */
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
