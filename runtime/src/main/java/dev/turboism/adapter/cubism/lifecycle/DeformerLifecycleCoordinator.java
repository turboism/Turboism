package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.event.cubism.DeformerLockEvent;
import dev.turboism.sdk.event.cubism.DeformerOpacityEvent;
import dev.turboism.sdk.event.cubism.DeformerVisibilityEvent;
import dev.turboism.sdk.event.cubism.RotationDeformerBaseAngleEvent;
import dev.turboism.sdk.event.cubism.RotationDeformerFormEvent;
import dev.turboism.sdk.event.cubism.WarpDeformerGridEvent;
import dev.turboism.sdk.cubism.hook.DeformerHooks;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Runtime-owned lifecycle coordinator for Warp and Rotation Deformer authoring writes. */
public final class DeformerLifecycleCoordinator implements AutoCloseable {
    public static final String OPACITY_OPERATION_ID = "cubism.model.deformer.set-opacity";
    public static final String VISIBILITY_OPERATION_ID = "cubism.model.deformer.set-visible";
    public static final String LOCK_OPERATION_ID = "cubism.model.deformer.set-locked";
    public static final String WARP_GRID_OPERATION_ID = "cubism.model.warp-deformer.replace-grid";
    public static final String ROTATION_ANGLE_OPERATION_ID = "cubism.model.rotation-deformer.set-base-angle";
    public static final String ROTATION_FORM_OPERATION_ID = "cubism.model.rotation-deformer.replace-form";

    private final CopyOnWriteArrayList<Registration> plugins = new CopyOnWriteArrayList<>();
    private final LifecycleCallbackExecutor callbacks;
    private final Object registrationLock = new Object();
    private volatile RuntimeEventBroker eventBroker;
    private final ThreadLocal<Boolean> lifecycleActive = ThreadLocal.withInitial(() -> false);

    public DeformerLifecycleCoordinator() {
        this(new PluginWorkExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()));
    }

    public DeformerLifecycleCoordinator(final PluginWorkExecutorRegistry executors) {
        this.callbacks = new LifecycleCallbackExecutor("Deformer", executors);
    }

    /** Attaches the session event broker used by the preview plugin runtime. */
    public void attachEventBroker(final RuntimeEventBroker broker) {
        final RuntimeEventBroker value = Objects.requireNonNull(broker, "broker");
        synchronized (registrationLock) {
            if (eventBroker != null && eventBroker != value) {
                throw new IllegalStateException(
                    "Deformer lifecycle already belongs to another Runtime event broker."
                );
            }
            eventBroker = value;
        }
    }

    /**
     * Registers a plugin's Deformer hooks, replacing any registration previously made under the same
     * plugin id and shutting down that plugin's pending callback queue before the new one is installed.
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
     * Removes every registration owned by the given plugin id and shuts down its callback queue, so no
     * further observer callbacks are delivered for it. Unknown ids are ignored.
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
     * Runs the opacity write pipeline: intercept-capable hooks may rewrite the requested value in
     * registration order, the surviving value is handed to {@code nativeOperation}, and the actual
     * before/after values read back from the Deformer are published to observers asynchronously.
     *
     * <p>Non-finite values returned by an interceptor are ignored (and logged) rather than applied;
     * hook failures are logged and never propagate to the caller. Runs on the calling host thread and
     * refuses re-entry.
     *
     * @param deformer the Deformer being written
     * @param requested opacity requested by the caller, before interception
     * @param nativeOperation performs the actual Editor-side write with the effective value
     * @throws IllegalArgumentException when the effective opacity is not finite
     * @throws IllegalStateException when invoked from within another Deformer lifecycle operation
     */
    public void setOpacity(final Deformer deformer, final float requested, final Consumer<Float> nativeOperation) {
        runGuarded(OPACITY_OPERATION_ID, () -> {
            float effective = requested;
            for (Registration registration : plugins) {
                final PluginHooks plugin = registration.plugin();
                if (plugin.interceptAllowed()) for (DeformerHooks hook : plugin.entrypoints()) {
                    try {
                        final float transformed = hook.beforeSetDeformerOpacity(deformer, effective);
                        if (Float.isFinite(transformed)) effective = transformed;
                        else plugin.logger().warn("Ignored non-finite beforeSetDeformerOpacity result for " + OPACITY_OPERATION_ID);
                    } catch (Throwable failure) { logHookFailure(plugin, "beforeSetDeformerOpacity", failure); }
                }
            }
            final RuntimeEventBroker broker = eventBroker;
            if (broker != null) {
                final Deformer detached = DetachedDeformer.capture(
                    deformer, deformer.getOpacity()
                );
                effective = broker.publishRuntimeTransform(
                    DeformerOpacityEvent.Before.class,
                    effective,
                    candidate -> {
                        final DeformerOpacityEvent.Before.Callback callback =
                            DeformerOpacityEvent.Before.openCallback(
                                detached, requested, candidate
                            );
                        return new RuntimeEventBroker.TransformCallback() {
                            @Override public DeformerOpacityEvent.Before event() {
                                return callback.event();
                            }
                            @Override public void close() { callback.close(); }
                        };
                    },
                    event -> ((DeformerOpacityEvent.Before) event).opacity()
                );
            }
            if (!Float.isFinite(effective)) throw new IllegalArgumentException("Deformer opacity must be finite.");
            final float oldValue = deformer.getOpacity();
            nativeOperation.accept(effective);
            final float newValue = deformer.getOpacity();
            publish(OPACITY_OPERATION_ID, hook -> {
                if (Float.compare(oldValue, newValue) != 0) hook.onDeformerOpacityChanged(deformer, oldValue, newValue);
                hook.afterSetDeformerOpacity(deformer, newValue);
            });
            if (broker != null) {
                final Deformer detached = DetachedDeformer.capture(deformer, newValue);
                if (Float.compare(oldValue, newValue) != 0) {
                    broker.publishRuntime(new DeformerOpacityEvent.On(
                        detached, oldValue, newValue
                    ));
                }
                broker.publishRuntime(new DeformerOpacityEvent.After(detached, newValue));
            }
        });
    }

    /**
     * Runs the visibility write pipeline: interceptors may rewrite the requested flag, the effective
     * value is applied through {@code nativeOperation}, and the observed before/after state is
     * published to observers asynchronously. Hook failures are logged, not propagated.
     *
     * @param deformer the Deformer being written
     * @param requested visibility requested by the caller, before interception
     * @param nativeOperation performs the actual Editor-side write with the effective value
     * @throws IllegalStateException when invoked from within another Deformer lifecycle operation
     */
    public void setVisible(final Deformer deformer, final boolean requested, final Consumer<Boolean> nativeOperation) {
        runGuarded(VISIBILITY_OPERATION_ID, () -> {
            boolean effective = requested;
            for (Registration registration : plugins) {
                final PluginHooks plugin = registration.plugin();
                if (plugin.interceptAllowed()) for (DeformerHooks hook : plugin.entrypoints()) {
                    try { effective = hook.beforeSetDeformerVisible(deformer, effective); }
                    catch (Throwable failure) { logHookFailure(plugin, "beforeSetDeformerVisible", failure); }
                }
            }
            final RuntimeEventBroker broker = eventBroker;
            if (broker != null) {
                final Deformer detached = DetachedDeformer.capture(
                    deformer, deformer.getOpacity()
                );
                effective = broker.publishRuntimeTransform(
                    DeformerVisibilityEvent.Before.class,
                    effective,
                    candidate -> {
                        final DeformerVisibilityEvent.Before.Callback callback =
                            DeformerVisibilityEvent.Before.openCallback(
                                detached, requested, candidate
                            );
                        return new RuntimeEventBroker.TransformCallback() {
                            @Override public DeformerVisibilityEvent.Before event() {
                                return callback.event();
                            }
                            @Override public void close() { callback.close(); }
                        };
                    },
                    event -> ((DeformerVisibilityEvent.Before) event).visible(),
                    ignored -> true
                );
            }
            final boolean oldValue = deformer.visible();
            nativeOperation.accept(effective);
            final boolean newValue = deformer.visible();
            publish(VISIBILITY_OPERATION_ID, hook -> {
                if (oldValue != newValue) hook.onDeformerVisibilityChanged(deformer, oldValue, newValue);
                hook.afterSetDeformerVisible(deformer, newValue);
            });
            if (broker != null) {
                final Deformer detached = DetachedDeformer.capture(
                    deformer, deformer.getOpacity()
                );
                if (oldValue != newValue) {
                    broker.publishRuntime(new DeformerVisibilityEvent.On(
                        detached, oldValue, newValue
                    ));
                }
                broker.publishRuntime(new DeformerVisibilityEvent.After(detached, newValue));
            }
        });
    }

    /**
     * Runs the lock-flag write pipeline: interceptors may rewrite the requested flag, the effective
     * value is applied through {@code nativeOperation}, and the observed before/after state is
     * published to observers asynchronously. Hook failures are logged, not propagated.
     *
     * @param deformer the Deformer being written
     * @param requested lock state requested by the caller, before interception
     * @param nativeOperation performs the actual Editor-side write with the effective value
     * @throws IllegalStateException when invoked from within another Deformer lifecycle operation
     */
    public void setLocked(final Deformer deformer, final boolean requested, final Consumer<Boolean> nativeOperation) {
        runGuarded(LOCK_OPERATION_ID, () -> {
            boolean effective = requested;
            for (Registration registration : plugins) {
                final PluginHooks plugin = registration.plugin();
                if (plugin.interceptAllowed()) for (DeformerHooks hook : plugin.entrypoints()) {
                    try { effective = hook.beforeSetDeformerLocked(deformer, effective); }
                    catch (Throwable failure) { logHookFailure(plugin, "beforeSetDeformerLocked", failure); }
                }
            }
            final RuntimeEventBroker broker = eventBroker;
            if (broker != null) {
                final Deformer detached = DetachedDeformer.capture(
                    deformer, deformer.getOpacity()
                );
                effective = broker.publishRuntimeTransform(
                    DeformerLockEvent.Before.class,
                    effective,
                    candidate -> {
                        final DeformerLockEvent.Before.Callback callback =
                            DeformerLockEvent.Before.openCallback(
                                detached, requested, candidate
                            );
                        return new RuntimeEventBroker.TransformCallback() {
                            @Override public DeformerLockEvent.Before event() {
                                return callback.event();
                            }
                            @Override public void close() { callback.close(); }
                        };
                    },
                    event -> ((DeformerLockEvent.Before) event).locked(),
                    ignored -> true
                );
            }
            final boolean oldValue = deformer.locked();
            nativeOperation.accept(effective);
            final boolean newValue = deformer.locked();
            publish(LOCK_OPERATION_ID, hook -> {
                if (oldValue != newValue) hook.onDeformerLockChanged(deformer, oldValue, newValue);
                hook.afterSetDeformerLocked(deformer, newValue);
            });
            if (broker != null) {
                final Deformer detached = DetachedDeformer.capture(
                    deformer, deformer.getOpacity()
                );
                if (oldValue != newValue) {
                    broker.publishRuntime(new DeformerLockEvent.On(
                        detached, oldValue, newValue
                    ));
                }
                broker.publishRuntime(new DeformerLockEvent.After(detached, newValue));
            }
        });
    }

    /**
     * Runs the Warp Deformer grid replacement pipeline. Interceptors may substitute the grid but must
     * not return null; a null result aborts that hook (logged) and leaves the previous effective grid
     * in place. Observers see the grid actually read back from the Deformer after the write.
     *
     * @param deformer the Warp Deformer being written
     * @param requested grid requested by the caller, before interception
     * @param nativeOperation performs the actual Editor-side write with the effective grid
     * @throws NullPointerException when {@code requested} is null
     * @throws IllegalStateException when invoked from within another Deformer lifecycle operation
     */
    public void replaceGrid(
        final WarpDeformer deformer, final WarpGrid requested, final Consumer<WarpGrid> nativeOperation
    ) {
        runGuarded(WARP_GRID_OPERATION_ID, () -> {
            WarpGrid effective = Objects.requireNonNull(requested, "grid");
            for (Registration registration : plugins) {
                final PluginHooks plugin = registration.plugin();
                if (plugin.interceptAllowed()) for (DeformerHooks hook : plugin.entrypoints()) {
                    try {
                        effective = Objects.requireNonNull(
                            hook.beforeReplaceWarpDeformerGrid(deformer, effective),
                            "beforeReplaceWarpDeformerGrid result"
                        );
                    } catch (Throwable failure) { logHookFailure(plugin, "beforeReplaceWarpDeformerGrid", failure); }
                }
            }
            final RuntimeEventBroker broker = eventBroker;
            if (broker != null) {
                final WarpDeformer detached = DetachedWarpDeformer.capture(
                    deformer, deformer.getOpacity(), deformer.grid()
                );
                effective = broker.publishRuntimeTransform(
                    WarpDeformerGridEvent.Before.class,
                    effective,
                    candidate -> {
                        final WarpDeformerGridEvent.Before.Callback callback =
                            WarpDeformerGridEvent.Before.openCallback(
                                detached, requested, candidate
                            );
                        return new RuntimeEventBroker.TransformCallback() {
                            @Override public WarpDeformerGridEvent.Before event() {
                                return callback.event();
                            }
                            @Override public void close() { callback.close(); }
                        };
                    },
                    event -> ((WarpDeformerGridEvent.Before) event).grid(),
                    Objects::nonNull
                );
            }
            final WarpGrid oldValue = deformer.grid();
            nativeOperation.accept(effective);
            final WarpGrid newValue = deformer.grid();
            publish(WARP_GRID_OPERATION_ID, hook -> {
                if (!oldValue.equals(newValue)) hook.onWarpDeformerGridChanged(deformer, oldValue, newValue);
                hook.afterReplaceWarpDeformerGrid(deformer, newValue);
            });
            if (broker != null) {
                final WarpDeformer detached = DetachedWarpDeformer.capture(
                    deformer, deformer.getOpacity(), newValue
                );
                if (!oldValue.equals(newValue)) {
                    broker.publishRuntime(new WarpDeformerGridEvent.On(
                        detached, oldValue, newValue
                    ));
                }
                broker.publishRuntime(new WarpDeformerGridEvent.After(detached, newValue));
            }
        });
    }

    /**
     * Runs the Rotation Deformer base-angle write pipeline. Non-finite interceptor results are ignored
     * and logged; the effective angle must still be finite when the native write is reached.
     *
     * @param deformer the Rotation Deformer being written
     * @param requested base angle requested by the caller, before interception
     * @param nativeOperation performs the actual Editor-side write with the effective angle
     * @throws IllegalArgumentException when the effective base angle is not finite
     * @throws IllegalStateException when invoked from within another Deformer lifecycle operation
     */
    public void setBaseAngle(
        final RotationDeformer deformer, final float requested, final Consumer<Float> nativeOperation
    ) {
        runGuarded(ROTATION_ANGLE_OPERATION_ID, () -> {
            float effective = requested;
            for (Registration registration : plugins) {
                final PluginHooks plugin = registration.plugin();
                if (plugin.interceptAllowed()) for (DeformerHooks hook : plugin.entrypoints()) {
                    try {
                        final float transformed = hook.beforeSetRotationDeformerBaseAngle(deformer, effective);
                        if (Float.isFinite(transformed)) effective = transformed;
                        else plugin.logger().warn("Ignored non-finite beforeSetRotationDeformerBaseAngle result for " + ROTATION_ANGLE_OPERATION_ID);
                    } catch (Throwable failure) { logHookFailure(plugin, "beforeSetRotationDeformerBaseAngle", failure); }
                }
            }
            final RuntimeEventBroker broker = eventBroker;
            if (broker != null) {
                final RotationDeformer detached = DetachedRotationDeformer.capture(
                    deformer, deformer.getOpacity(), deformer.baseAngle(), deformer.form()
                );
                effective = broker.publishRuntimeTransform(
                    RotationDeformerBaseAngleEvent.Before.class,
                    effective,
                    candidate -> {
                        final RotationDeformerBaseAngleEvent.Before.Callback callback =
                            RotationDeformerBaseAngleEvent.Before.openCallback(
                                detached, requested, candidate
                            );
                        return new RuntimeEventBroker.TransformCallback() {
                            @Override public RotationDeformerBaseAngleEvent.Before event() {
                                return callback.event();
                            }
                            @Override public void close() { callback.close(); }
                        };
                    },
                    event -> ((RotationDeformerBaseAngleEvent.Before) event).angle()
                );
            }
            if (!Float.isFinite(effective)) throw new IllegalArgumentException("Rotation Deformer base angle must be finite.");
            final float oldValue = deformer.baseAngle();
            nativeOperation.accept(effective);
            final float newValue = deformer.baseAngle();
            publish(ROTATION_ANGLE_OPERATION_ID, hook -> {
                if (Float.compare(oldValue, newValue) != 0) hook.onRotationDeformerBaseAngleChanged(deformer, oldValue, newValue);
                hook.afterSetRotationDeformerBaseAngle(deformer, newValue);
            });
            if (broker != null) {
                final RotationDeformer detached = DetachedRotationDeformer.capture(
                    deformer, deformer.getOpacity(), newValue, deformer.form()
                );
                if (Float.compare(oldValue, newValue) != 0) {
                    broker.publishRuntime(new RotationDeformerBaseAngleEvent.On(
                        detached, oldValue, newValue
                    ));
                }
                broker.publishRuntime(new RotationDeformerBaseAngleEvent.After(
                    detached, newValue
                ));
            }
        });
    }

    /**
     * Runs the Rotation Deformer form replacement pipeline. Interceptors may substitute the form but
     * must not return null; a null result aborts that hook (logged) and keeps the previous effective
     * form. Observers see the form actually read back from the Deformer after the write.
     *
     * @param deformer the Rotation Deformer being written
     * @param requested form requested by the caller, before interception
     * @param nativeOperation performs the actual Editor-side write with the effective form
     * @throws NullPointerException when {@code requested} is null
     * @throws IllegalStateException when invoked from within another Deformer lifecycle operation
     */
    public void replaceForm(
        final RotationDeformer deformer,
        final RotationDeformerForm requested,
        final Consumer<RotationDeformerForm> nativeOperation
    ) {
        runGuarded(ROTATION_FORM_OPERATION_ID, () -> {
            RotationDeformerForm effective = Objects.requireNonNull(requested, "form");
            for (Registration registration : plugins) {
                final PluginHooks plugin = registration.plugin();
                if (plugin.interceptAllowed()) for (DeformerHooks hook : plugin.entrypoints()) {
                    try {
                        effective = Objects.requireNonNull(
                            hook.beforeReplaceRotationDeformerForm(deformer, effective),
                            "beforeReplaceRotationDeformerForm result"
                        );
                    } catch (Throwable failure) { logHookFailure(plugin, "beforeReplaceRotationDeformerForm", failure); }
                }
            }
            final RuntimeEventBroker broker = eventBroker;
            if (broker != null) {
                final RotationDeformer detached = DetachedRotationDeformer.capture(
                    deformer, deformer.getOpacity(), deformer.baseAngle(), deformer.form()
                );
                effective = broker.publishRuntimeTransform(
                    RotationDeformerFormEvent.Before.class,
                    effective,
                    candidate -> {
                        final RotationDeformerFormEvent.Before.Callback callback =
                            RotationDeformerFormEvent.Before.openCallback(
                                detached, requested, candidate
                            );
                        return new RuntimeEventBroker.TransformCallback() {
                            @Override public RotationDeformerFormEvent.Before event() {
                                return callback.event();
                            }
                            @Override public void close() { callback.close(); }
                        };
                    },
                    event -> ((RotationDeformerFormEvent.Before) event).form(),
                    Objects::nonNull
                );
            }
            final RotationDeformerForm oldValue = deformer.form();
            nativeOperation.accept(effective);
            final RotationDeformerForm newValue = deformer.form();
            publish(ROTATION_FORM_OPERATION_ID, hook -> {
                if (!oldValue.equals(newValue)) hook.onRotationDeformerFormChanged(deformer, oldValue, newValue);
                hook.afterReplaceRotationDeformerForm(deformer, newValue);
            });
            if (broker != null) {
                final RotationDeformer detached = DetachedRotationDeformer.capture(
                    deformer, deformer.getOpacity(), deformer.baseAngle(), newValue
                );
                if (!oldValue.equals(newValue)) {
                    broker.publishRuntime(new RotationDeformerFormEvent.On(
                        detached, oldValue, newValue
                    ));
                }
                broker.publishRuntime(new RotationDeformerFormEvent.After(
                    detached, newValue
                ));
            }
        });
    }

    private void runGuarded(final String operationId, final Runnable operation) {
        if (lifecycleActive.get()) {
            throw new IllegalStateException("Recursive Cubism Deformer lifecycle is not allowed: " + operationId);
        }
        lifecycleActive.set(true);
        try { operation.run(); }
        finally { lifecycleActive.remove(); }
    }

    private void publish(final String operationId, final HookCall call) {
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (!plugin.observeAllowed()) continue;
            final List<? extends DeformerHooks> hooks = plugin.entrypoints();
            submit(registration, operationId, () -> {
                for (DeformerHooks hook : hooks) {
                    try { call.invoke(hook); }
                    catch (Throwable failure) { logHookFailure(plugin, operationId, failure); }
                }
            });
        }
    }

    /**
     * Blocks until every observer callback queued so far has finished. Intended for tests and for
     * shutdown sequencing; it makes no guarantee about callbacks submitted after the call begins.
     */
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

    private static void logHookFailure(final PluginHooks plugin, final String phase, final Throwable failure) {
        try { plugin.logger().error("Cubism Deformer lifecycle hook failed safely: " + phase, failure); }
        catch (Throwable ignored) { }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    @FunctionalInterface private interface HookCall { void invoke(DeformerHooks hook); }

    private record Registration(Object token, PluginHooks plugin) { }

    /**
     * One plugin's participation in the Deformer lifecycle.
     *
     * @param descriptor identity of the owning plugin, used as the registration key
     * @param entrypoints the plugin's Deformer hook implementations, defensively copied and immutable
     * @param logger sink for hook failures raised by this plugin
     * @param interceptAllowed whether this plugin's {@code before*} hooks may rewrite requested values
     * @param observeAllowed whether this plugin receives asynchronous {@code after*}/{@code on*} callbacks
     */
    public record PluginHooks(
        PluginDescriptor descriptor,
        List<? extends DeformerHooks> entrypoints,
        PluginLogger logger,
        boolean interceptAllowed,
        boolean observeAllowed
    ) {
        /**
         * Registers a plugin with both interception and observation permitted.
         *
         * @param descriptor identity of the owning plugin
         * @param entrypoints the plugin's Deformer hook implementations
         * @param logger sink for hook failures raised by this plugin
         */
        public PluginHooks(
            final PluginDescriptor descriptor,
            final List<? extends DeformerHooks> entrypoints,
            final PluginLogger logger
        ) { this(descriptor, entrypoints, logger, true, true); }

        public PluginHooks {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            entrypoints = List.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
            logger = Objects.requireNonNull(logger, "logger");
        }
    }
}
