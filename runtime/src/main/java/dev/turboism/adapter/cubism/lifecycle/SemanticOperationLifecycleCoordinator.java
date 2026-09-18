package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.event.cubism.CubismOperationLifecycleEvent;
import dev.turboism.sdk.event.cubism.ModelUpdateEvent;
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
    private volatile RuntimeEventBroker eventBroker;
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

    /** Attaches the session event broker used by the preview plugin runtime. */
    public void attachEventBroker(final RuntimeEventBroker broker) {
        final RuntimeEventBroker value = Objects.requireNonNull(broker, "broker");
        synchronized (registrationLock) {
            if (eventBroker != null && eventBroker != value) {
                throw new IllegalStateException(
                    "Semantic lifecycle already belongs to another Runtime event broker."
                );
            }
            eventBroker = value;
        }
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
        run(operation, origin, subjectId, Optional.empty(), () -> {
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
        run(operation, origin, subjectId, Optional.empty(), () -> {
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
        run(operation, origin, subjectId, Optional.empty(), () -> {
            Objects.requireNonNull(invocation, "invocation").run();
            return true;
        });
    }

    /**
     * Publishes one already-performed host edit as a confirmed semantic operation.
     *
     * <p>Use this for an edit the Cubism user interface performed and Turboism only observed. The
     * invocation is not owned by this coordinator, so only the {@code on} and {@code after} phases
     * are produced, and only when the observer established the operation from an exact native
     * entry. The same recursion guard as {@link #runComparing} applies, so an observation that
     * re-enters an active operation on the same thread fails closed instead of nesting.</p>
     *
     * @param operation the semantical operation the observation proved
     * @param origin the best-known source of the observed edit
     * @param subjectId optional Turboism-owned object identity the edit applies to
     */
    public void publishObserved(
        final CubismOperation operation,
        final CubismOperationOrigin origin,
        final Optional<String> subjectId
    ) {
        publishObserved(operation, origin, subjectId, Optional.empty());
    }

    /**
     * Publishes one already-performed host edit as a confirmed semantic operation, carrying the
     * presentation label the observation established.
     *
     * <p>The label is presentation only. It is never an identity and never proof of what the
     * operation changed, so a caller that cannot read an exact label must pass
     * {@link Optional#empty()} rather than a value derived from a native name.</p>
     *
     * @param operation the semantical operation the observation proved
     * @param origin the best-known source of the observed edit
     * @param subjectId optional Turboism-owned object identity the edit applies to
     * @param label optional human-readable name of the observed edit
     */
    public void publishObserved(
        final CubismOperation operation,
        final CubismOperationOrigin origin,
        final Optional<String> subjectId,
        final Optional<String> label
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
            Objects.requireNonNull(subjectId, "subjectId"),
            Objects.requireNonNull(label, "label")
        );
        try {
            publishCompletion(event, true);
            final RuntimeEventBroker broker = eventBroker;
            if (broker == null) return;
            broker.publishRuntime(new CubismOperationLifecycleEvent.On(event));
            publishModelUpdateOn(broker, event);
            broker.publishRuntime(new CubismOperationLifecycleEvent.After(event, true));
            publishModelUpdateAfter(broker, event, true);
        } finally {
            operations.remove(semantic);
            if (operations.isEmpty()) active.remove();
        }
    }

    /**
     * Publishes the observed start of one native edit Turboism is watching.
     *
     * <p>This is the only phase available before the host mutates the model, so the operation is the
     * conservative generic editor command and the label is a native name the host showed: neither is
     * evidence of what the edit will change. The specific operation follows when the observer
     * decodes the committed entry and calls {@link #publishObserved}, which is the call that
     * actually establishes facts. An edit that never reaches undo therefore reports a start and no
     * confirmation, which is the truth.</p>
     *
     * <p>No recursion guard is taken here: the guard exists to stop an operation nesting inside
     * another, and this call only opens a frame. It is also how a hook that fires during a Turboism
     * authoring transaction is suppressed before it reaches this method.</p>
     *
     * @param label optional native edit name, presented but never treated as an identity
     */
    public void publishObservedStart(final Optional<String> label) {
        final CubismOperationEvent event = new CubismOperationEvent(
            sequence.incrementAndGet(),
            CubismOperation.EXECUTE_EDITOR_COMMAND,
            CubismOperationOrigin.HOST_UI,
            Optional.empty(),
            Objects.requireNonNull(label, "label")
        );
        final RuntimeEventBroker broker = eventBroker;
        if (broker == null) return;
        broker.publishRuntime(new CubismOperationLifecycleEvent.Before(event));
        publishModelUpdateBefore(broker, event);
    }

    private void run(
        final CubismOperation operation,
        final CubismOperationOrigin origin,
        final Optional<String> subjectId,
        final Optional<String> label,
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
            Objects.requireNonNull(subjectId, "subjectId"),
            Objects.requireNonNull(label, "label")
        );
        try {
            invokeBefore(event);
            final RuntimeEventBroker broker = eventBroker;
            if (broker != null) {
                broker.publishRuntime(new CubismOperationLifecycleEvent.Before(event));
                publishModelUpdateBefore(broker, event);
            }
            final boolean confirmed = Objects.requireNonNull(invocation, "invocation").get();
            publishCompletion(event, confirmed);
            if (broker != null) {
                if (confirmed) {
                    broker.publishRuntime(new CubismOperationLifecycleEvent.On(event));
                    publishModelUpdateOn(broker, event);
                }
                broker.publishRuntime(new CubismOperationLifecycleEvent.After(
                    event, confirmed
                ));
                publishModelUpdateAfter(broker, event, confirmed);
            }
        } finally {
            operations.remove(semantic);
            if (operations.isEmpty()) active.remove();
        }
    }

    private static void publishModelUpdateBefore(
        final RuntimeEventBroker broker,
        final CubismOperationEvent event
    ) {
        if (event.operation() == CubismOperation.UPDATE_MODEL) {
            broker.publishRuntime(new ModelUpdateEvent.Before(event));
        }
    }

    private static void publishModelUpdateOn(
        final RuntimeEventBroker broker,
        final CubismOperationEvent event
    ) {
        if (event.operation() == CubismOperation.UPDATE_MODEL) {
            broker.publishRuntime(new ModelUpdateEvent.On(event));
        }
    }

    private static void publishModelUpdateAfter(
        final RuntimeEventBroker broker,
        final CubismOperationEvent event,
        final boolean confirmed
    ) {
        if (confirmed && event.operation() == CubismOperation.UPDATE_MODEL) {
            broker.publishRuntime(new ModelUpdateEvent.After(event));
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
