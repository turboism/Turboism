package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
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
    private final ThreadLocal<Boolean> lifecycleActive = ThreadLocal.withInitial(() -> false);

    public DrawableLifecycleCoordinator() {
        this(new PluginWorkExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()));
    }

    public DrawableLifecycleCoordinator(final PluginWorkExecutorRegistry executors) {
        this.callbacks = new LifecycleCallbackExecutor("Drawable", executors);
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
            if (!Float.isFinite(effective)) throw new IllegalArgumentException("Drawable opacity must be finite.");
            final float oldValue = drawable.getOpacity();
            nativeOperation.accept(effective);
            final float newValue = drawable.getOpacity();
            publish(OPACITY_OPERATION_ID, plugin -> hook -> {
                if (Float.compare(oldValue, newValue) != 0) hook.onDrawableOpacityChanged(drawable, oldValue, newValue);
                hook.afterSetDrawableOpacity(drawable, newValue);
            });
        });
    }

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
            final boolean oldValue = drawable.visible();
            nativeOperation.accept(effective);
            final boolean newValue = drawable.visible();
            publish(VISIBILITY_OPERATION_ID, plugin -> hook -> {
                if (oldValue != newValue) hook.onDrawableVisibilityChanged(drawable, oldValue, newValue);
                hook.afterSetDrawableVisible(drawable, newValue);
            });
        });
    }

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
            final boolean oldValue = drawable.locked();
            nativeOperation.accept(effective);
            final boolean newValue = drawable.locked();
            publish(LOCK_OPERATION_ID, plugin -> hook -> {
                if (oldValue != newValue) hook.onDrawableLockChanged(drawable, oldValue, newValue);
                hook.afterSetDrawableLocked(drawable, newValue);
            });
        });
    }

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
            final ArtMeshGeometry oldValue = drawable.geometry();
            nativeOperation.accept(effective);
            final ArtMeshGeometry newValue = drawable.geometry();
            publish(GEOMETRY_OPERATION_ID, plugin -> hook -> {
                if (!oldValue.equals(newValue)) hook.onDrawableGeometryChanged(drawable, oldValue, newValue);
                hook.afterReplaceDrawableGeometry(drawable, newValue);
            });
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

    public record PluginHooks(
        PluginDescriptor descriptor,
        List<? extends DrawableHooks> entrypoints,
        PluginLogger logger,
        boolean interceptAllowed,
        boolean observeAllowed
    ) {
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
