package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.hook.PartHooks;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Runtime-owned lifecycle coordinator for the semantic Part opacity operation. */
public final class PartLifecycleCoordinator implements AutoCloseable {

    public static final String OPERATION_ID = "cubism.model.part.set-opacity";
    public static final String NAME_OPERATION_ID = "cubism.model.part.set-name";

    private final CopyOnWriteArrayList<PluginHooks> plugins = new CopyOnWriteArrayList<>();
    private final PluginWorkExecutorRegistry executors;
    private final CopyOnWriteArrayList<CompletionStage<?>> pending = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Boolean> partWriteActive = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> partNameWriteActive = ThreadLocal.withInitial(() -> false);

    public PartLifecycleCoordinator() {
        this(new PluginWorkExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()));
    }

    public PartLifecycleCoordinator(final PluginWorkExecutorRegistry executors) {
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    public void register(final PluginHooks plugin) {
        final PluginHooks value = Objects.requireNonNull(plugin, "plugin");
        unregister(value.descriptor().id());
        plugins.add(value);
    }

    public void unregister(final String pluginId) {
        final String id = requireText(pluginId, "pluginId");
        plugins.removeIf(plugin -> plugin.descriptor().id().equals(id));
        executors.shutdown(id);
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
        for (PluginHooks plugin : plugins) {
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
        for (PluginHooks plugin : plugins) {
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
        for (PluginHooks plugin : plugins) {
            if (!plugin.observeAllowed()) continue;
            final List<? extends PartHooks> entrypoints = plugin.entrypoints();
            submit(plugin, () -> {
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
        for (PluginHooks plugin : plugins) {
            if (!plugin.observeAllowed()) continue;
            final List<? extends PartHooks> entrypoints = plugin.entrypoints();
            submit(plugin, NAME_OPERATION_ID, () -> {
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
        final CompletionStage<?>[] snapshot = pending.toArray(CompletionStage[]::new);
        try {
            CompletableFuture.allOf(java.util.Arrays.stream(snapshot)
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new))
                .get(2, TimeUnit.SECONDS);
            pending.removeAll(java.util.List.of(snapshot));
        } catch (Exception failure) {
            throw new IllegalStateException("Part lifecycle callbacks did not quiesce.", failure);
        }
    }

    @Override
    public void close() {
        plugins.clear();
        executors.shutdownAll();
    }

    private void submit(final PluginHooks plugin, final Runnable callback) {
        submit(plugin, OPERATION_ID, callback);
    }

    private void submit(final PluginHooks plugin, final String operationId, final Runnable callback) {
        final var submission = executors.get(plugin.descriptor().id())
            .submit(task(plugin.descriptor().id(), operationId), callback);
        if (submission.accepted()) pending.add(submission.completion());
    }

    private static PluginTask task(final String pluginId) {
        return task(pluginId, OPERATION_ID);
    }

    private static PluginTask task(final String pluginId, final String operationId) {
        return new PluginTask("event.subscribe", pluginId, operationId, "none");
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
