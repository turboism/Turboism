package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.event.CubismOperationEvent;
import dev.turboism.sdk.cubism.event.CubismOperationOrigin;
import dev.turboism.sdk.cubism.hook.SemanticOperationHooks;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Runtime-owned lifecycle coordinator shared by typed model and Editor operations. */
public final class SemanticOperationLifecycleCoordinator implements AutoCloseable {

    private final CopyOnWriteArrayList<Registration> plugins = new CopyOnWriteArrayList<>();
    private final LifecycleCallbackExecutor callbacks;
    private final Object registrationLock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final ThreadLocal<EnumSet<CubismOperation>> active =
        ThreadLocal.withInitial(() -> EnumSet.noneOf(CubismOperation.class));

    /** Creates a coordinator with the standard bounded callback executor. */
    public SemanticOperationLifecycleCoordinator() {
        this(new PluginWorkExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()));
    }

    /** Creates a coordinator with an explicit bounded callback executor. */
    public SemanticOperationLifecycleCoordinator(final PluginWorkExecutorRegistry executors) {
        this.callbacks = new LifecycleCallbackExecutor("Semantic operation", executors);
    }

    /** Replaces one plugin's hook entrypoints. */
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

    /** Removes one plugin and quiesces its accepted callbacks. */
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
     * Runs one operation and compares authoritative state before and after it.
     * {@code on} is emitted only when the two immutable snapshots differ.
     */
    public <T> void runComparing(
        final CubismOperation operation,
        final CubismOperationOrigin origin,
        final Optional<String> subjectId,
        final Supplier<T> state,
        final Runnable invocation
    ) {
        final Supplier<T> snapshot = Objects.requireNonNull(state, "state");
        run(operation, origin, subjectId, () -> {
            final T before = snapshot.get();
            Objects.requireNonNull(invocation, "invocation").run();
            return !Objects.equals(before, snapshot.get());
        });
    }

    /**
     * Compares current state with an immutable requested final state without a
     * post-invocation read. Use only when normal completion guarantees that state.
     */
    public <T> void runComparingTo(
        final CubismOperation operation,
        final CubismOperationOrigin origin,
        final Optional<String> subjectId,
        final Supplier<T> state,
        final T finalState,
        final Runnable invocation
    ) {
        final Supplier<T> snapshot = Objects.requireNonNull(state, "state");
        run(operation, origin, subjectId, () -> {
            final T before = snapshot.get();
            Objects.requireNonNull(invocation, "invocation").run();
            return !Objects.equals(before, finalState);
        });
    }

    /** Runs one operation whose normal completion is itself the confirmed semantic fact. */
    public void runConfirmed(
        final CubismOperation operation,
        final CubismOperationOrigin origin,
        final Optional<String> subjectId,
        final Runnable invocation
    ) {
        run(operation, origin, subjectId, () -> {
            Objects.requireNonNull(invocation, "invocation").run();
            return true;
        });
    }

    private void run(
        final CubismOperation operation,
        final CubismOperationOrigin origin,
        final Optional<String> subjectId,
        final Supplier<Boolean> invocation
    ) {
        final CubismOperation semantic = Objects.requireNonNull(operation, "operation");
        final EnumSet<CubismOperation> operations = active.get();
        if (!operations.add(semantic)) {
            throw new IllegalStateException(
                "Recursive Cubism semantic lifecycle is not allowed: " + semantic.id()
            );
        }
        final CubismOperationEvent event = new CubismOperationEvent(
            sequence.incrementAndGet(),
            semantic,
            Objects.requireNonNull(origin, "origin"),
            Objects.requireNonNull(subjectId, "subjectId")
        );
        try {
            invokeBefore(event);
            final boolean confirmed = Objects.requireNonNull(invocation, "invocation").get();
            publishCompletion(event, confirmed);
        } finally {
            operations.remove(semantic);
            if (operations.isEmpty()) active.remove();
        }
    }

    private void invokeBefore(final CubismOperationEvent event) {
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (!plugin.interceptAllowed()) continue;
            for (SemanticOperationHooks hook : plugin.entrypoints()) {
                try {
                    hook.beforeCubismOperation(event);
                } catch (Throwable failure) {
                    logHookFailure(plugin, "beforeCubismOperation", failure);
                }
            }
        }
    }

    private void publishCompletion(final CubismOperationEvent event, final boolean confirmed) {
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (!plugin.observeAllowed()) continue;
            final List<? extends SemanticOperationHooks> hooks = plugin.entrypoints();
            submit(registration, event.operation().id(), () -> {
                for (SemanticOperationHooks hook : hooks) {
                    if (confirmed) {
                        try {
                            hook.onCubismOperationConfirmed(event);
                        } catch (Throwable failure) {
                            logHookFailure(plugin, "onCubismOperationConfirmed", failure);
                        }
                    }
                    try {
                        hook.afterCubismOperation(event);
                    } catch (Throwable failure) {
                        logHookFailure(plugin, "afterCubismOperation", failure);
                    }
                }
            });
        }
    }

    /** Waits for callbacks already accepted by bounded plugin executors. */
    public void awaitIdle() {
        callbacks.awaitIdle();
    }

    /** Removes all hooks and shuts down callback executors. */
    @Override
    public void close() {
        synchronized (registrationLock) {
            plugins.clear();
            callbacks.close();
        }
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
            plugin.logger().error("Cubism semantic lifecycle hook failed safely: " + phase, failure);
        } catch (Throwable ignored) {
            // Hook diagnostics must not escape into the Cubism operation.
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private record Registration(Object token, PluginHooks plugin) { }

    /** Ordered hook entrypoints and their permission-derived execution rights. */
    public record PluginHooks(
        PluginDescriptor descriptor,
        List<? extends SemanticOperationHooks> entrypoints,
        PluginLogger logger,
        boolean interceptAllowed,
        boolean observeAllowed
    ) {
        /** Creates a fully enabled hook set for internal tests and trusted wiring. */
        public PluginHooks(
            final PluginDescriptor descriptor,
            final List<? extends SemanticOperationHooks> entrypoints,
            final PluginLogger logger
        ) {
            this(descriptor, entrypoints, logger, true, true);
        }

        /** Validates and snapshots one plugin's ordered hook entrypoints. */
        public PluginHooks {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            entrypoints = List.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
            logger = Objects.requireNonNull(logger, "logger");
        }
    }
}
