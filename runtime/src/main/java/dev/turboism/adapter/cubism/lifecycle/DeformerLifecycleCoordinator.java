package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
    private final PluginWorkExecutorRegistry executors;
    private final CopyOnWriteArrayList<CompletionStage<?>> pending = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Boolean> lifecycleActive = ThreadLocal.withInitial(() -> false);

    public DeformerLifecycleCoordinator() {
        this(new PluginWorkExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()));
    }

    public DeformerLifecycleCoordinator(final PluginWorkExecutorRegistry executors) {
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    public void register(final PluginHooks plugin) {
        final PluginHooks value = Objects.requireNonNull(plugin, "plugin");
        final Object token = new Object();
        unregister(value.descriptor().id());
        register(token, value);
    }

    void register(final Object token, final PluginHooks plugin) {
        plugins.add(new Registration(
            Objects.requireNonNull(token, "token"),
            Objects.requireNonNull(plugin, "plugin")
        ));
    }

    public void unregister(final String pluginId) {
        final String id = requireText(pluginId, "pluginId");
        plugins.removeIf(registration -> registration.plugin().descriptor().id().equals(id));
        executors.shutdown(id);
    }

    void unregister(final String pluginId, final Object token) {
        final String id = requireText(pluginId, "pluginId");
        final Object generation = Objects.requireNonNull(token, "token");
        final boolean removed = plugins.removeIf(registration ->
            registration.token() == generation
                && registration.plugin().descriptor().id().equals(id)
        );
        if (removed && plugins.stream().noneMatch(registration ->
            registration.plugin().descriptor().id().equals(id)
        )) {
            executors.shutdown(id);
        }
    }

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
            if (!Float.isFinite(effective)) throw new IllegalArgumentException("Deformer opacity must be finite.");
            final float oldValue = deformer.getOpacity();
            nativeOperation.accept(effective);
            final float newValue = deformer.getOpacity();
            publish(OPACITY_OPERATION_ID, hook -> {
                if (Float.compare(oldValue, newValue) != 0) hook.onDeformerOpacityChanged(deformer, oldValue, newValue);
                hook.afterSetDeformerOpacity(deformer, newValue);
            });
        });
    }

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
            final boolean oldValue = deformer.visible();
            nativeOperation.accept(effective);
            final boolean newValue = deformer.visible();
            publish(VISIBILITY_OPERATION_ID, hook -> {
                if (oldValue != newValue) hook.onDeformerVisibilityChanged(deformer, oldValue, newValue);
                hook.afterSetDeformerVisible(deformer, newValue);
            });
        });
    }

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
            final boolean oldValue = deformer.locked();
            nativeOperation.accept(effective);
            final boolean newValue = deformer.locked();
            publish(LOCK_OPERATION_ID, hook -> {
                if (oldValue != newValue) hook.onDeformerLockChanged(deformer, oldValue, newValue);
                hook.afterSetDeformerLocked(deformer, newValue);
            });
        });
    }

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
            final WarpGrid oldValue = deformer.grid();
            nativeOperation.accept(effective);
            final WarpGrid newValue = deformer.grid();
            publish(WARP_GRID_OPERATION_ID, hook -> {
                if (!oldValue.equals(newValue)) hook.onWarpDeformerGridChanged(deformer, oldValue, newValue);
                hook.afterReplaceWarpDeformerGrid(deformer, newValue);
            });
        });
    }

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
            if (!Float.isFinite(effective)) throw new IllegalArgumentException("Rotation Deformer base angle must be finite.");
            final float oldValue = deformer.baseAngle();
            nativeOperation.accept(effective);
            final float newValue = deformer.baseAngle();
            publish(ROTATION_ANGLE_OPERATION_ID, hook -> {
                if (Float.compare(oldValue, newValue) != 0) hook.onRotationDeformerBaseAngleChanged(deformer, oldValue, newValue);
                hook.afterSetRotationDeformerBaseAngle(deformer, newValue);
            });
        });
    }

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
            final RotationDeformerForm oldValue = deformer.form();
            nativeOperation.accept(effective);
            final RotationDeformerForm newValue = deformer.form();
            publish(ROTATION_FORM_OPERATION_ID, hook -> {
                if (!oldValue.equals(newValue)) hook.onRotationDeformerFormChanged(deformer, oldValue, newValue);
                hook.afterReplaceRotationDeformerForm(deformer, newValue);
            });
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
            submit(plugin, operationId, () -> {
                for (DeformerHooks hook : hooks) {
                    try { call.invoke(hook); }
                    catch (Throwable failure) { logHookFailure(plugin, operationId, failure); }
                }
            });
        }
    }

    public void awaitIdle() {
        final CompletionStage<?>[] snapshot = pending.toArray(CompletionStage[]::new);
        try {
            CompletableFuture.allOf(java.util.Arrays.stream(snapshot)
                .map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new))
                .get(2, TimeUnit.SECONDS);
            pending.removeAll(java.util.List.of(snapshot));
        } catch (Exception failure) {
            throw new IllegalStateException("Deformer lifecycle callbacks did not quiesce.", failure);
        }
    }

    @Override public void close() { plugins.clear(); executors.shutdownAll(); }

    private void submit(final PluginHooks plugin, final String operationId, final Runnable callback) {
        final var submission = executors.get(plugin.descriptor().id()).submit(
            new PluginTask("event.subscribe", plugin.descriptor().id(), operationId, "none"), callback
        );
        if (submission.accepted()) pending.add(submission.completion());
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

    public record PluginHooks(
        PluginDescriptor descriptor,
        List<? extends DeformerHooks> entrypoints,
        PluginLogger logger,
        boolean interceptAllowed,
        boolean observeAllowed
    ) {
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
