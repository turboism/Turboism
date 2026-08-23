package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.PluginEventOwnerKey;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.sdk.cubism.hook.DeformerHooks;
import dev.turboism.sdk.event.cubism.CubismOperationLifecycleEvent;
import dev.turboism.sdk.event.cubism.DrawableGeometryEvent;
import dev.turboism.sdk.event.cubism.DrawableLockEvent;
import dev.turboism.sdk.event.cubism.DrawableOpacityEvent;
import dev.turboism.sdk.event.cubism.DrawableVisibilityEvent;
import dev.turboism.sdk.event.cubism.DeformerLockEvent;
import dev.turboism.sdk.event.cubism.DeformerOpacityEvent;
import dev.turboism.sdk.event.cubism.DeformerVisibilityEvent;
import dev.turboism.sdk.event.cubism.RotationDeformerBaseAngleEvent;
import dev.turboism.sdk.event.cubism.RotationDeformerFormEvent;
import dev.turboism.sdk.event.cubism.WarpDeformerGridEvent;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.cubism.hook.DrawableHooks;
import dev.turboism.sdk.cubism.hook.SemanticOperationHooks;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Discovers ArtMesh, Deformer, and shared semantic hooks from ordered plugin entrypoints. */
public final class EditorObjectHookRegistry {
    public static final String OBSERVE_PERMISSION = ParameterHookRegistry.OBSERVE_PERMISSION;
    public static final String INTERCEPT_PERMISSION = ParameterHookRegistry.INTERCEPT_PERMISSION;

    private final EditorObjectLifecycleCoordinator coordinator;
    private final ConcurrentHashMap<String, HookOwnership> ownerships = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PluginEventOwnerKey, List<Registration>> eventAdapters =
        new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();

    public EditorObjectHookRegistry(final EditorObjectLifecycleCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /** Returns the shared coordinator that owns editor-object lifecycle delivery. */
    public EditorObjectLifecycleCoordinator coordinator() {
        return coordinator;
    }

    /**
     * Registers a plugin's ArtMesh, Deformer, and semantic hooks for the lifetime of the host session.
     * Entrypoints are filtered by the hook interfaces they implement and registered in the given order;
     * a plugin contributing none of them installs nothing. Intercept and observe capability is derived
     * from the descriptor's declared permissions.
     *
     * @param descriptor identity and permissions of the registering plugin
     * @param entrypoints the plugin's entrypoint instances, in invocation order
     * @param logger sink for hook failures raised by this plugin
     * @throws IllegalStateException when editor-object hooks are already registered for this plugin id
     */
    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger
    ) {
        final Object token = new Object();
        synchronized (lifecycleLock) {
            if (ownerships.containsKey(descriptor.id())) {
                throw new IllegalStateException("Editor-object hooks already registered for " + descriptor.id());
            }
            unregisterHooks(descriptor.id());
            registerHooks(token, descriptor, entrypoints, logger, true, true, true);
        }
    }

    /**
     * Registers a plugin's editor-object hooks bound to a disposable scope, so closing the scope
     * detaches exactly this registration generation. If installation fails the partial registration is
     * rolled back before the failure is rethrown.
     *
     * @param descriptor identity and permissions of the registering plugin
     * @param entrypoints the plugin's entrypoint instances, in invocation order
     * @param logger sink for hook failures raised by this plugin
     * @param scope plugin scope whose disposal unregisters this generation
     * @throws NullPointerException when {@code descriptor} or {@code scope} is null
     * @throws IllegalStateException when editor-object hooks are already registered for this plugin id
     */
    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger,
        final DisposableScope scope
    ) {
        final PluginDescriptor plugin = Objects.requireNonNull(descriptor, "descriptor");
        final DisposableScope pluginScope = Objects.requireNonNull(scope, "scope");
        final HookOwnership ownership = new HookOwnership(plugin.id());
        synchronized (lifecycleLock) {
            if (ownerships.putIfAbsent(plugin.id(), ownership) != null) {
                throw new IllegalStateException("Editor-object hooks already registered for " + plugin.id());
            }
            try {
                pluginScope.register(ownership::close);
                registerHooks(ownership.token, plugin, entrypoints, logger, true, true, true);
                ownership.installed = true;
            } catch (RuntimeException | Error failure) {
                ownerships.remove(plugin.id(), ownership);
                unregisterHooks(plugin.id(), ownership.token);
                throw failure;
            }
        }
    }

    /** Registers migrated editor-object overrides as exact-generation broker adapters. */
    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger,
        final DisposableScope scope,
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner
    ) {
        final PluginDescriptor plugin = Objects.requireNonNull(descriptor, "descriptor");
        final List<? extends TurboismPlugin> instances = List.copyOf(
            Objects.requireNonNull(entrypoints, "entrypoints")
        );
        final PluginLogger sink = Objects.requireNonNull(logger, "logger");
        final DisposableScope pluginScope = Objects.requireNonNull(scope, "scope");
        final RuntimeEventBroker runtimeBroker = Objects.requireNonNull(broker, "broker");
        final PluginEventOwnerKey eventOwner = Objects.requireNonNull(owner, "owner");
        final HookOwnership ownership = new HookOwnership(plugin.id());
        synchronized (lifecycleLock) {
            if (ownerships.putIfAbsent(plugin.id(), ownership) != null) {
                throw new IllegalStateException(
                    "Editor-object hooks already registered for " + plugin.id()
                );
            }
            try {
                pluginScope.register(ownership::close);
                registerHooks(
                    ownership.token, plugin, instances, sink, false, false, false
                );
                ownership.installed = true;
            } catch (RuntimeException | Error failure) {
                ownerships.remove(plugin.id(), ownership);
                unregisterHooks(plugin.id(), ownership.token);
                throw failure;
            }
        }
        if (!hasPermission(plugin, INTERCEPT_PERMISSION)
            && !hasPermission(plugin, OBSERVE_PERMISSION)) {
            return;
        }
        final List<Registration> adapters = new java.util.ArrayList<>();
        int entrypointOrdinal = 0;
        for (TurboismPlugin entrypoint : instances) {
            if (entrypoint instanceof DrawableHooks hooks) {
                if (hasPermission(plugin, INTERCEPT_PERMISSION)) {
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 0, entrypoint,
                        "beforeSetDrawableOpacity", DrawableOpacityEvent.Before.class,
                        event -> event.setOpacity(hooks.beforeSetDrawableOpacity(
                            event.drawable(), event.opacity()
                        )), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 3, entrypoint,
                        "beforeSetDrawableVisible", DrawableVisibilityEvent.Before.class,
                        event -> event.setVisible(hooks.beforeSetDrawableVisible(
                            event.drawable(), event.visible()
                        )), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 6, entrypoint,
                        "beforeSetDrawableLocked", DrawableLockEvent.Before.class,
                        event -> event.setLocked(hooks.beforeSetDrawableLocked(
                            event.drawable(), event.locked()
                        )), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 9, entrypoint,
                        "beforeReplaceDrawableGeometry", DrawableGeometryEvent.Before.class,
                        event -> event.setGeometry(hooks.beforeReplaceDrawableGeometry(
                            event.drawable(), event.geometry()
                        )), sink, adapters
                    );
                }
                if (hasPermission(plugin, OBSERVE_PERMISSION)) {
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 1, entrypoint,
                        "onDrawableOpacityChanged", DrawableOpacityEvent.On.class,
                        event -> hooks.onDrawableOpacityChanged(
                            event.drawable(), event.oldOpacity(), event.newOpacity()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 2, entrypoint,
                        "afterSetDrawableOpacity", DrawableOpacityEvent.After.class,
                        event -> hooks.afterSetDrawableOpacity(
                            event.drawable(), event.finalOpacity()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 4, entrypoint,
                        "onDrawableVisibilityChanged", DrawableVisibilityEvent.On.class,
                        event -> hooks.onDrawableVisibilityChanged(
                            event.drawable(), event.oldVisible(), event.newVisible()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 5, entrypoint,
                        "afterSetDrawableVisible", DrawableVisibilityEvent.After.class,
                        event -> hooks.afterSetDrawableVisible(
                            event.drawable(), event.finalVisible()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 7, entrypoint,
                        "onDrawableLockChanged", DrawableLockEvent.On.class,
                        event -> hooks.onDrawableLockChanged(
                            event.drawable(), event.oldLocked(), event.newLocked()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 8, entrypoint,
                        "afterSetDrawableLocked", DrawableLockEvent.After.class,
                        event -> hooks.afterSetDrawableLocked(
                            event.drawable(), event.finalLocked()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 10, entrypoint,
                        "onDrawableGeometryChanged", DrawableGeometryEvent.On.class,
                        event -> hooks.onDrawableGeometryChanged(
                            event.drawable(), event.oldGeometry(), event.newGeometry()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 11, entrypoint,
                        "afterReplaceDrawableGeometry", DrawableGeometryEvent.After.class,
                        event -> hooks.afterReplaceDrawableGeometry(
                            event.drawable(), event.finalGeometry()
                        ), sink, adapters
                    );
                }
            }
            if (entrypoint instanceof SemanticOperationHooks hooks) {
                if (hasPermission(plugin, INTERCEPT_PERMISSION)) {
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 30, entrypoint,
                        "beforeCubismOperation", CubismOperationLifecycleEvent.Before.class,
                        event -> hooks.beforeCubismOperation(event.operation()),
                        sink, adapters
                    );
                }
                if (hasPermission(plugin, OBSERVE_PERMISSION)) {
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 31, entrypoint,
                        "onCubismOperationConfirmed", CubismOperationLifecycleEvent.On.class,
                        event -> hooks.onCubismOperationConfirmed(event.operation()),
                        sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 32, entrypoint,
                        "afterCubismOperation", CubismOperationLifecycleEvent.After.class,
                        event -> hooks.afterCubismOperation(event.operation()),
                        sink, adapters
                    );
                }
            }
            if (entrypoint instanceof DeformerHooks hooks) {
                if (hasPermission(plugin, INTERCEPT_PERMISSION)) {
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 12, entrypoint,
                        "beforeSetDeformerOpacity", DeformerOpacityEvent.Before.class,
                        event -> event.setOpacity(hooks.beforeSetDeformerOpacity(
                            event.deformer(), event.opacity()
                        )), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 15, entrypoint,
                        "beforeSetDeformerVisible", DeformerVisibilityEvent.Before.class,
                        event -> event.setVisible(hooks.beforeSetDeformerVisible(
                            event.deformer(), event.visible()
                        )), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 18, entrypoint,
                        "beforeSetDeformerLocked", DeformerLockEvent.Before.class,
                        event -> event.setLocked(hooks.beforeSetDeformerLocked(
                            event.deformer(), event.locked()
                        )), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 21, entrypoint,
                        "beforeReplaceWarpDeformerGrid", WarpDeformerGridEvent.Before.class,
                        event -> event.setGrid(hooks.beforeReplaceWarpDeformerGrid(
                            event.deformer(), event.grid()
                        )), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 24, entrypoint,
                        "beforeSetRotationDeformerBaseAngle",
                        RotationDeformerBaseAngleEvent.Before.class,
                        event -> event.setAngle(hooks.beforeSetRotationDeformerBaseAngle(
                            event.deformer(), event.angle()
                        )), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 27, entrypoint,
                        "beforeReplaceRotationDeformerForm",
                        RotationDeformerFormEvent.Before.class,
                        event -> event.setForm(hooks.beforeReplaceRotationDeformerForm(
                            event.deformer(), event.form()
                        )), sink, adapters
                    );
                }
                if (hasPermission(plugin, OBSERVE_PERMISSION)) {
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 13, entrypoint,
                        "onDeformerOpacityChanged", DeformerOpacityEvent.On.class,
                        event -> hooks.onDeformerOpacityChanged(
                            event.deformer(), event.oldOpacity(), event.newOpacity()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 14, entrypoint,
                        "afterSetDeformerOpacity", DeformerOpacityEvent.After.class,
                        event -> hooks.afterSetDeformerOpacity(
                            event.deformer(), event.finalOpacity()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 16, entrypoint,
                        "onDeformerVisibilityChanged", DeformerVisibilityEvent.On.class,
                        event -> hooks.onDeformerVisibilityChanged(
                            event.deformer(), event.oldVisible(), event.newVisible()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 17, entrypoint,
                        "afterSetDeformerVisible", DeformerVisibilityEvent.After.class,
                        event -> hooks.afterSetDeformerVisible(
                            event.deformer(), event.finalVisible()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 19, entrypoint,
                        "onDeformerLockChanged", DeformerLockEvent.On.class,
                        event -> hooks.onDeformerLockChanged(
                            event.deformer(), event.oldLocked(), event.newLocked()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 20, entrypoint,
                        "afterSetDeformerLocked", DeformerLockEvent.After.class,
                        event -> hooks.afterSetDeformerLocked(
                            event.deformer(), event.finalLocked()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 22, entrypoint,
                        "onWarpDeformerGridChanged", WarpDeformerGridEvent.On.class,
                        event -> hooks.onWarpDeformerGridChanged(
                            event.deformer(), event.oldGrid(), event.newGrid()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 23, entrypoint,
                        "afterReplaceWarpDeformerGrid", WarpDeformerGridEvent.After.class,
                        event -> hooks.afterReplaceWarpDeformerGrid(
                            event.deformer(), event.finalGrid()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 25, entrypoint,
                        "onRotationDeformerBaseAngleChanged",
                        RotationDeformerBaseAngleEvent.On.class,
                        event -> hooks.onRotationDeformerBaseAngleChanged(
                            event.deformer(), event.oldAngle(), event.newAngle()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 26, entrypoint,
                        "afterSetRotationDeformerBaseAngle",
                        RotationDeformerBaseAngleEvent.After.class,
                        event -> hooks.afterSetRotationDeformerBaseAngle(
                            event.deformer(), event.finalAngle()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 28, entrypoint,
                        "onRotationDeformerFormChanged", RotationDeformerFormEvent.On.class,
                        event -> hooks.onRotationDeformerFormChanged(
                            event.deformer(), event.oldForm(), event.newForm()
                        ), sink, adapters
                    );
                    adapt(
                        runtimeBroker, eventOwner, entrypointOrdinal, 29, entrypoint,
                        "afterReplaceRotationDeformerForm",
                        RotationDeformerFormEvent.After.class,
                        event -> hooks.afterReplaceRotationDeformerForm(
                            event.deformer(), event.finalForm()
                        ), sink, adapters
                    );
                }
            }
            entrypointOrdinal++;
        }
        if (adapters.isEmpty()) {
            return;
        }
        final List<Registration> installed = List.copyOf(adapters);
        if (eventAdapters.putIfAbsent(eventOwner, installed) != null) {
            closeEventAdapters(installed);
            unregister(plugin.id());
            throw new IllegalStateException(
                "Editor-object event adapters already registered for " + eventOwner
            );
        }
        try {
            pluginScope.register(() -> closeEventAdapters(eventOwner));
        } catch (RuntimeException | Error failure) {
            closeEventAdapters(eventOwner);
            unregister(plugin.id());
            throw failure;
        }
    }

    private void registerHooks(
        final Object token,
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger,
        final boolean includeDrawable,
        final boolean includeDeformer,
        final boolean includeSemantic
    ) {
        final PluginDescriptor plugin = Objects.requireNonNull(descriptor, "descriptor");
        final List<? extends TurboismPlugin> ordered = Objects.requireNonNull(entrypoints, "entrypoints");
        final PluginLogger pluginLogger = Objects.requireNonNull(logger, "logger");
        final boolean intercept = hasPermission(plugin, INTERCEPT_PERMISSION);
        final boolean observe = hasPermission(plugin, OBSERVE_PERMISSION);
        final List<DrawableHooks> drawableHooks = ordered.stream()
            .filter(DrawableHooks.class::isInstance).map(DrawableHooks.class::cast).toList();
        final List<DeformerHooks> deformerHooks = ordered.stream()
            .filter(DeformerHooks.class::isInstance).map(DeformerHooks.class::cast).toList();
        final List<SemanticOperationHooks> semanticHooks = ordered.stream()
            .filter(SemanticOperationHooks.class::isInstance)
            .map(SemanticOperationHooks.class::cast)
            .toList();
        if (includeDrawable && !drawableHooks.isEmpty()) {
            coordinator.drawable().register(token, new DrawableLifecycleCoordinator.PluginHooks(
                plugin, drawableHooks, pluginLogger, intercept, observe
            ));
        }
        if (includeDeformer && !deformerHooks.isEmpty()) {
            coordinator.deformer().register(token, new DeformerLifecycleCoordinator.PluginHooks(
                plugin, deformerHooks, pluginLogger, intercept, observe
            ));
        }
        if (includeSemantic && !semanticHooks.isEmpty()) {
            coordinator.semantic().register(token, new SemanticOperationLifecycleCoordinator.PluginHooks(
                plugin, semanticHooks, pluginLogger, intercept, observe
            ));
        }
    }

    /**
     * Detaches a plugin's ArtMesh, Deformer, and semantic hooks. A scope-bound registration is closed
     * through its scope hook and the call blocks until that disposal completes; otherwise the hooks are
     * removed directly. Unknown ids are ignored.
     *
     * @param pluginId id of the plugin to detach
     * @throws NullPointerException when {@code pluginId} is null
     */
    public void unregister(final String pluginId) {
        final String id = Objects.requireNonNull(pluginId, "pluginId");
        closeEventAdapters(id);
        final HookOwnership ownership = ownerships.get(id);
        if (ownership != null) {
            ownership.close();
            return;
        }
        synchronized (lifecycleLock) {
            unregisterHooks(id);
        }
    }

    private void unregisterHooks(final String pluginId) {
        coordinator.drawable().unregister(pluginId);
        coordinator.deformer().unregister(pluginId);
        coordinator.semantic().unregister(pluginId);
    }

    private void unregisterHooks(final String pluginId, final Object token) {
        coordinator.drawable().unregister(pluginId, token);
        coordinator.deformer().unregister(pluginId, token);
        coordinator.semantic().unregister(pluginId, token);
    }

    /** Removes every editor-object event adapter owned by the plugin generation. */
    public void unregister(final PluginEventOwnerKey owner) {
        closeEventAdapters(Objects.requireNonNull(owner, "owner"));
    }

    private void closeEventAdapters(final String pluginId) {
        eventAdapters.entrySet().removeIf(entry -> {
            if (!entry.getKey().pluginId().equals(pluginId)) {
                return false;
            }
            closeEventAdapters(entry.getValue());
            return true;
        });
    }

    private void closeEventAdapters(final PluginEventOwnerKey owner) {
        final List<Registration> adapters = eventAdapters.remove(owner);
        if (adapters != null) {
            closeEventAdapters(adapters);
        }
    }

    private static void closeEventAdapters(final List<Registration> adapters) {
        for (int index = adapters.size() - 1; index >= 0; index--) {
            adapters.get(index).close();
        }
    }

    private static <T extends dev.turboism.sdk.event.EventBus.TurboismEvent> void adapt(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final int entrypointOrdinal,
        final int methodOrdinal,
        final TurboismPlugin entrypoint,
        final String methodName,
        final Class<T> eventType,
        final java.util.function.Consumer<T> invocation,
        final PluginLogger logger,
        final List<Registration> installed
    ) {
        if (!overrides(entrypoint, methodName) || subscribes(entrypoint, eventType)) {
            return;
        }
        installed.add(broker.subscribeAdapter(
            owner, eventType, entrypointOrdinal, methodOrdinal, event -> {
                try {
                    invocation.accept(event);
                } catch (ThreadDeath | VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    throw hookFailure(logger, methodName, failure);
                }
            }
        ));
    }

    private static boolean overrides(final Object entrypoint, final String methodName) {
        try {
            final Class<?>[] parameterTypes = switch (methodName) {
                case "beforeSetDrawableOpacity", "afterSetDrawableOpacity" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.Drawable.class,
                        float.class
                    };
                case "onDrawableOpacityChanged" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.Drawable.class,
                        float.class,
                        float.class
                    };
                case "beforeSetDrawableVisible", "afterSetDrawableVisible",
                     "beforeSetDrawableLocked", "afterSetDrawableLocked" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.Drawable.class,
                        boolean.class
                    };
                case "onDrawableVisibilityChanged", "onDrawableLockChanged" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.Drawable.class,
                        boolean.class,
                        boolean.class
                    };
                case "beforeReplaceDrawableGeometry", "afterReplaceDrawableGeometry" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.Drawable.class,
                        dev.turboism.sdk.cubism.model.ArtMeshGeometry.class
                    };
                case "onDrawableGeometryChanged" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.Drawable.class,
                        dev.turboism.sdk.cubism.model.ArtMeshGeometry.class,
                        dev.turboism.sdk.cubism.model.ArtMeshGeometry.class
                    };
                case "beforeSetDeformerOpacity", "afterSetDeformerOpacity" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.Deformer.class,
                        float.class
                    };
                case "onDeformerOpacityChanged" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.Deformer.class,
                        float.class,
                        float.class
                    };
                case "beforeSetDeformerVisible", "afterSetDeformerVisible",
                     "beforeSetDeformerLocked", "afterSetDeformerLocked" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.Deformer.class,
                        boolean.class
                    };
                case "onDeformerVisibilityChanged", "onDeformerLockChanged" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.Deformer.class,
                        boolean.class,
                        boolean.class
                    };
                case "beforeReplaceWarpDeformerGrid", "afterReplaceWarpDeformerGrid" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.WarpDeformer.class,
                        dev.turboism.sdk.cubism.model.WarpGrid.class
                    };
                case "onWarpDeformerGridChanged" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.WarpDeformer.class,
                        dev.turboism.sdk.cubism.model.WarpGrid.class,
                        dev.turboism.sdk.cubism.model.WarpGrid.class
                    };
                case "beforeSetRotationDeformerBaseAngle",
                     "afterSetRotationDeformerBaseAngle" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.RotationDeformer.class,
                        float.class
                    };
                case "onRotationDeformerBaseAngleChanged" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.RotationDeformer.class,
                        float.class,
                        float.class
                    };
                case "beforeReplaceRotationDeformerForm",
                     "afterReplaceRotationDeformerForm" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.RotationDeformer.class,
                        dev.turboism.sdk.cubism.model.RotationDeformerForm.class
                    };
                case "onRotationDeformerFormChanged" ->
                    new Class<?>[]{
                        dev.turboism.sdk.cubism.model.RotationDeformer.class,
                        dev.turboism.sdk.cubism.model.RotationDeformerForm.class,
                        dev.turboism.sdk.cubism.model.RotationDeformerForm.class
                    };
                case "beforeCubismOperation", "onCubismOperationConfirmed",
                     "afterCubismOperation" ->
                    new Class<?>[]{dev.turboism.sdk.cubism.event.CubismOperationEvent.class};
                default -> throw new IllegalArgumentException(
                    "Unknown editor-object hook method: " + methodName
                );
            };
            final java.lang.reflect.Method method = entrypoint.getClass()
                .getMethod(methodName, parameterTypes);
            return method.getDeclaringClass() != DrawableHooks.class
                && method.getDeclaringClass() != DeformerHooks.class
                && method.getDeclaringClass() != SemanticOperationHooks.class
                && method.getDeclaringClass() != dev.turboism.sdk.cubism.CubismPlugin.class;
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException(
                "Editor-object hook contract is unavailable: " + methodName,
                failure
            );
        }
    }

    private static RuntimeException hookFailure(
        final PluginLogger logger,
        final String phase,
        final Throwable failure
    ) {
        try {
            logger.error("Cubism editor-object lifecycle hook failed safely: " + phase, failure);
        } catch (Throwable ignored) {
            // Diagnostic failure must not replace the hook failure.
        }
        return failure instanceof RuntimeException runtimeFailure
            ? runtimeFailure
            : new IllegalStateException("Legacy editor-object hook failed: " + phase, failure);
    }

    private static boolean subscribes(
        final Object entrypoint,
        final Class<? extends dev.turboism.sdk.event.EventBus.TurboismEvent> eventType
    ) {
        return java.util.Arrays.stream(entrypoint.getClass().getMethods()).anyMatch(method ->
            method.isAnnotationPresent(dev.turboism.sdk.event.SubscribeEvent.class)
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0].isAssignableFrom(eventType)
        );
    }

    private static boolean hasPermission(final PluginDescriptor descriptor, final String permissionId) {
        return descriptor.permissions().stream().anyMatch(permission -> permission.id().equals(permissionId));
    }

    private final class HookOwnership {
        private final String pluginId;
        private final Object token = new Object();
        private final AtomicBoolean closing = new AtomicBoolean();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile boolean installed;

        private HookOwnership(final String pluginId) {
            this.pluginId = pluginId;
        }

        private void close() {
            if (closing.compareAndSet(false, true)) {
                try {
                    synchronized (lifecycleLock) {
                        if (installed) {
                            unregisterHooks(pluginId, token);
                        }
                        ownerships.remove(pluginId, this);
                    }
                    completion.complete(null);
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            }
            completion.join();
        }
    }
}
