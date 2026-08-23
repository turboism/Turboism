package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.event.cubism.DrawableGeometryEvent;
import dev.turboism.sdk.event.cubism.DrawableLockEvent;
import dev.turboism.sdk.event.cubism.DrawableOpacityEvent;
import dev.turboism.sdk.event.cubism.DrawableVisibilityEvent;
import dev.turboism.sdk.cubism.hook.DrawableHooks;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Runtime-owned lifecycle coordinator for ArtMesh authoring writes. */
public final class DrawableLifecycleCoordinator implements AutoCloseable {
    public static final String OPACITY_OPERATION_ID = "cubism.model.art-mesh.set-opacity";
    public static final String VISIBILITY_OPERATION_ID = "cubism.model.art-mesh.set-visible";
    public static final String LOCK_OPERATION_ID = "cubism.model.art-mesh.set-locked";
    public static final String GEOMETRY_OPERATION_ID = "cubism.model.art-mesh.replace-geometry";

    private final CopyOnWriteArrayList<Registration> plugins = new CopyOnWriteArrayList<>();
    private final LifecycleCallbackExecutor callbacks;
    private final Object registrationLock = new Object();
    private volatile RuntimeEventBroker eventBroker;
    private final ThreadLocal<Boolean> lifecycleActive = ThreadLocal.withInitial(() -> false);

    public DrawableLifecycleCoordinator() {
        this(new PluginWorkExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()));
    }

    public DrawableLifecycleCoordinator(final PluginWorkExecutorRegistry executors) {
        this.callbacks = new LifecycleCallbackExecutor("Drawable", executors);
    }

    /** Attaches the session event broker used by the preview plugin runtime. */
    public void attachEventBroker(final RuntimeEventBroker broker) {
        final RuntimeEventBroker value = Objects.requireNonNull(broker, "broker");
        synchronized (registrationLock) {
            if (eventBroker != null && eventBroker != value) {
                throw new IllegalStateException(
                    "Drawable lifecycle already belongs to another Runtime event broker."
                );
            }
            eventBroker = value;
        }
    }

    /**
     * Registers a plugin's ArtMesh hooks, replacing any earlier registration made under the same plugin
     * id and shutting down that plugin's pending callback queue before the new one is installed.
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
     * Runs the ArtMesh opacity write pipeline: intercept-capable hooks may rewrite the requested value
     * in registration order, the surviving value is passed to {@code nativeOperation}, and the
     * before/after values read back from the Drawable are published to observers asynchronously.
     *
     * <p>Non-finite interceptor results are ignored and logged; hook failures are logged and never
     * propagate to the caller. Runs on the calling host thread and refuses re-entry.
     *
     * @param drawable the ArtMesh being written
     * @param requested opacity requested by the caller, before interception
     * @param nativeOperation performs the actual Editor-side write with the effective value
     * @throws IllegalArgumentException when the effective opacity is not finite
     * @throws IllegalStateException when invoked from within another Drawable lifecycle operation
     */
    public void setOpacity(final Drawable drawable, final float requested, final Consumer<Float> nativeOperation) {
        runGuarded(OPACITY_OPERATION_ID, () -> {
            float effective = requested;
            for (Registration registration : plugins) {
                final PluginHooks plugin = registration.plugin();
                if (plugin.interceptAllowed()) {
                    for (DrawableHooks hook : plugin.entrypoints()) {
                        try {
                            final float transformed = hook.beforeSetDrawableOpacity(drawable, effective);
                            if (Float.isFinite(transformed)) effective = transformed;
                            else plugin.logger().warn("Ignored non-finite beforeSetDrawableOpacity result for " + OPACITY_OPERATION_ID);
                        } catch (Throwable failure) { logHookFailure(plugin, "beforeSetDrawableOpacity", failure); }
                    }
                }
            }
            final RuntimeEventBroker broker = eventBroker;
            if (broker != null) {
                final Drawable detached = DetachedDrawable.capture(drawable, drawable.getOpacity());
                effective = broker.publishRuntimeTransform(
                    DrawableOpacityEvent.Before.class,
                    effective,
                    candidate -> {
                        final DrawableOpacityEvent.Before.Callback callback =
                            DrawableOpacityEvent.Before.openCallback(
                                detached,
                                requested,
                                candidate
                            );
                        return new RuntimeEventBroker.TransformCallback() {
                            @Override public DrawableOpacityEvent.Before event() {
                                return callback.event();
                            }
                            @Override public void close() { callback.close(); }
                        };
                    },
                    event -> ((DrawableOpacityEvent.Before) event).opacity()
                );
            }
            if (!Float.isFinite(effective)) throw new IllegalArgumentException("Drawable opacity must be finite.");
            final float oldValue = drawable.getOpacity();
            nativeOperation.accept(effective);
            final float newValue = drawable.getOpacity();
            publish(OPACITY_OPERATION_ID, plugin -> hook -> {
                if (Float.compare(oldValue, newValue) != 0) hook.onDrawableOpacityChanged(drawable, oldValue, newValue);
                hook.afterSetDrawableOpacity(drawable, newValue);
            });
            if (broker != null) {
                final Drawable detached = DetachedDrawable.capture(drawable, newValue);
                if (Float.compare(oldValue, newValue) != 0) {
                    broker.publishRuntime(new DrawableOpacityEvent.On(
                        detached, oldValue, newValue
                    ));
                }
                broker.publishRuntime(new DrawableOpacityEvent.After(detached, newValue));
            }
        });
    }

    /**
     * Runs the ArtMesh visibility write pipeline: interceptors may rewrite the requested flag, the
     * effective value is applied through {@code nativeOperation}, and the observed before/after state
     * is published to observers asynchronously. Hook failures are logged, not propagated.
     *
     * @param drawable the ArtMesh being written
     * @param requested visibility requested by the caller, before interception
     * @param nativeOperation performs the actual Editor-side write with the effective value
     * @throws IllegalStateException when invoked from within another Drawable lifecycle operation
     */
    public void setVisible(final Drawable drawable, final boolean requested, final Consumer<Boolean> nativeOperation) {
        runGuarded(VISIBILITY_OPERATION_ID, () -> {
            boolean effective = requested;
            for (Registration registration : plugins) {
                final PluginHooks plugin = registration.plugin();
                if (plugin.interceptAllowed()) {
                    for (DrawableHooks hook : plugin.entrypoints()) {
                        try { effective = hook.beforeSetDrawableVisible(drawable, effective); }
                        catch (Throwable failure) { logHookFailure(plugin, "beforeSetDrawableVisible", failure); }
                    }
                }
            }
            final RuntimeEventBroker broker = eventBroker;
            if (broker != null) {
                final Drawable detached = DetachedDrawable.capture(drawable, drawable.getOpacity());
                effective = broker.publishRuntimeTransform(
                    DrawableVisibilityEvent.Before.class,
                    effective,
                    candidate -> {
                        final DrawableVisibilityEvent.Before.Callback callback =
                            DrawableVisibilityEvent.Before.openCallback(
                                detached, requested, candidate
                            );
                        return new RuntimeEventBroker.TransformCallback() {
                            @Override public DrawableVisibilityEvent.Before event() {
                                return callback.event();
                            }
                            @Override public void close() { callback.close(); }
                        };
                    },
                    event -> ((DrawableVisibilityEvent.Before) event).visible(),
                    ignored -> true
                );
            }
            final boolean oldValue = drawable.visible();
            nativeOperation.accept(effective);
            final boolean newValue = drawable.visible();
            publish(VISIBILITY_OPERATION_ID, plugin -> hook -> {
                if (oldValue != newValue) hook.onDrawableVisibilityChanged(drawable, oldValue, newValue);
                hook.afterSetDrawableVisible(drawable, newValue);
            });
            if (broker != null) {
                final Drawable detached = DetachedDrawable.capture(drawable, drawable.getOpacity());
                if (oldValue != newValue) {
                    broker.publishRuntime(new DrawableVisibilityEvent.On(
                        detached, oldValue, newValue
                    ));
                }
                broker.publishRuntime(new DrawableVisibilityEvent.After(detached, newValue));
            }
        });
    }

    /**
     * Runs the ArtMesh lock-flag write pipeline: interceptors may rewrite the requested flag, the
     * effective value is applied through {@code nativeOperation}, and the observed before/after state
     * is published to observers asynchronously. Hook failures are logged, not propagated.
     *
     * @param drawable the ArtMesh being written
     * @param requested lock state requested by the caller, before interception
     * @param nativeOperation performs the actual Editor-side write with the effective value
     * @throws IllegalStateException when invoked from within another Drawable lifecycle operation
     */
    public void setLocked(final Drawable drawable, final boolean requested, final Consumer<Boolean> nativeOperation) {
        runGuarded(LOCK_OPERATION_ID, () -> {
            boolean effective = requested;
            for (Registration registration : plugins) {
                final PluginHooks plugin = registration.plugin();
                if (plugin.interceptAllowed()) {
                    for (DrawableHooks hook : plugin.entrypoints()) {
                        try { effective = hook.beforeSetDrawableLocked(drawable, effective); }
                        catch (Throwable failure) { logHookFailure(plugin, "beforeSetDrawableLocked", failure); }
                    }
                }
            }
            final RuntimeEventBroker broker = eventBroker;
            if (broker != null) {
                final Drawable detached = DetachedDrawable.capture(drawable, drawable.getOpacity());
                effective = broker.publishRuntimeTransform(
                    DrawableLockEvent.Before.class,
                    effective,
                    candidate -> {
                        final DrawableLockEvent.Before.Callback callback =
                            DrawableLockEvent.Before.openCallback(detached, requested, candidate);
                        return new RuntimeEventBroker.TransformCallback() {
                            @Override public DrawableLockEvent.Before event() {
                                return callback.event();
                            }
                            @Override public void close() { callback.close(); }
                        };
                    },
                    event -> ((DrawableLockEvent.Before) event).locked(),
                    ignored -> true
                );
            }
            final boolean oldValue = drawable.locked();
            nativeOperation.accept(effective);
            final boolean newValue = drawable.locked();
            publish(LOCK_OPERATION_ID, plugin -> hook -> {
                if (oldValue != newValue) hook.onDrawableLockChanged(drawable, oldValue, newValue);
                hook.afterSetDrawableLocked(drawable, newValue);
            });
            if (broker != null) {
                final Drawable detached = DetachedDrawable.capture(drawable, drawable.getOpacity());
                if (oldValue != newValue) {
                    broker.publishRuntime(new DrawableLockEvent.On(
                        detached, oldValue, newValue
                    ));
                }
                broker.publishRuntime(new DrawableLockEvent.After(detached, newValue));
            }
        });
    }

    /**
     * Runs the ArtMesh geometry replacement pipeline. Interceptors may substitute the geometry but must
     * not return null; a null result aborts that hook (logged) and keeps the previous effective
     * geometry. Observers see the geometry actually read back from the Drawable after the write.
     *
     * @param drawable the ArtMesh being written
     * @param requested geometry requested by the caller, before interception
     * @param nativeOperation performs the actual Editor-side write with the effective geometry
     * @throws NullPointerException when {@code requested} is null
     * @throws IllegalStateException when invoked from within another Drawable lifecycle operation
     */
    public void replaceGeometry(
        final Drawable drawable,
        final ArtMeshGeometry requested,
        final Consumer<ArtMeshGeometry> nativeOperation
    ) {
        runGuarded(GEOMETRY_OPERATION_ID, () -> {
            ArtMeshGeometry effective = Objects.requireNonNull(requested, "geometry");
            for (Registration registration : plugins) {
                final PluginHooks plugin = registration.plugin();
                if (plugin.interceptAllowed()) {
                    for (DrawableHooks hook : plugin.entrypoints()) {
                        try {
                            effective = Objects.requireNonNull(
                                hook.beforeReplaceDrawableGeometry(drawable, effective),
                                "beforeReplaceDrawableGeometry result"
                            );
                        } catch (Throwable failure) { logHookFailure(plugin, "beforeReplaceDrawableGeometry", failure); }
                    }
                }
            }
            final RuntimeEventBroker broker = eventBroker;
            if (broker != null) {
                final Drawable detached = DetachedDrawable.capture(drawable, drawable.getOpacity());
                effective = broker.publishRuntimeTransform(
                    DrawableGeometryEvent.Before.class,
                    effective,
                    candidate -> {
                        final DrawableGeometryEvent.Before.Callback callback =
                            DrawableGeometryEvent.Before.openCallback(
                                detached, requested, candidate
                            );
                        return new RuntimeEventBroker.TransformCallback() {
                            @Override public DrawableGeometryEvent.Before event() {
                                return callback.event();
                            }
                            @Override public void close() { callback.close(); }
                        };
                    },
                    event -> ((DrawableGeometryEvent.Before) event).geometry(),
                    Objects::nonNull
                );
            }
            final ArtMeshGeometry oldValue = drawable.geometry();
            nativeOperation.accept(effective);
            final ArtMeshGeometry newValue = drawable.geometry();
            publish(GEOMETRY_OPERATION_ID, plugin -> hook -> {
                if (!oldValue.equals(newValue)) hook.onDrawableGeometryChanged(drawable, oldValue, newValue);
                hook.afterReplaceDrawableGeometry(drawable, newValue);
            });
            if (broker != null) {
                final Drawable detached = DetachedDrawable.capture(drawable, drawable.getOpacity());
                if (!oldValue.equals(newValue)) {
                    broker.publishRuntime(new DrawableGeometryEvent.On(
                        detached, oldValue, newValue
                    ));
                }
                broker.publishRuntime(new DrawableGeometryEvent.After(detached, newValue));
            }
        });
    }

    private void runGuarded(final String operationId, final Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (lifecycleActive.get()) {
            throw new IllegalStateException("Recursive Cubism Drawable lifecycle is not allowed: " + operationId);
        }
        lifecycleActive.set(true);
        try { operation.run(); }
        finally { lifecycleActive.remove(); }
    }

    private void publish(final String operationId, final HookInvocation invocation) {
        for (Registration registration : plugins) {
            final PluginHooks plugin = registration.plugin();
            if (!plugin.observeAllowed()) continue;
            final List<? extends DrawableHooks> hooks = plugin.entrypoints();
            submit(registration, operationId, () -> {
                for (DrawableHooks hook : hooks) {
                    try { invocation.forPlugin(plugin).invoke(hook); }
                    catch (Throwable failure) { logHookFailure(plugin, operationId, failure); }
                }
            });
        }
    }

    /**
     * Blocks until every observer callback queued so far has finished. Intended for tests and shutdown
     * sequencing; it makes no guarantee about callbacks submitted after the call begins.
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
        try { plugin.logger().error("Cubism Drawable lifecycle hook failed safely: " + phase, failure); }
        catch (Throwable ignored) { }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    @FunctionalInterface private interface HookInvocation {
        HookCall forPlugin(PluginHooks plugin);
    }
    @FunctionalInterface private interface HookCall { void invoke(DrawableHooks hook); }

    private record Registration(Object token, PluginHooks plugin) { }

    /**
     * One plugin's participation in the ArtMesh lifecycle.
     *
     * @param descriptor identity of the owning plugin, used as the registration key
     * @param entrypoints the plugin's ArtMesh hook implementations, defensively copied and immutable
     * @param logger sink for hook failures raised by this plugin
     * @param interceptAllowed whether this plugin's {@code before*} hooks may rewrite requested values
     * @param observeAllowed whether this plugin receives asynchronous {@code after*}/{@code on*} callbacks
     */
    public record PluginHooks(
        PluginDescriptor descriptor,
        List<? extends DrawableHooks> entrypoints,
        PluginLogger logger,
        boolean interceptAllowed,
        boolean observeAllowed
    ) {
        /**
         * Registers a plugin with both interception and observation permitted.
         *
         * @param descriptor identity of the owning plugin
         * @param entrypoints the plugin's ArtMesh hook implementations
         * @param logger sink for hook failures raised by this plugin
         */
        public PluginHooks(
            final PluginDescriptor descriptor,
            final List<? extends DrawableHooks> entrypoints,
            final PluginLogger logger
        ) { this(descriptor, entrypoints, logger, true, true); }

        public PluginHooks {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            entrypoints = List.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
            logger = Objects.requireNonNull(logger, "logger");
        }
    }
}
