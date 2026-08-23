package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.PluginEventOwnerKey;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.hook.AnimationFileHooks;
import dev.turboism.sdk.cubism.hook.EditorLifecycleHooks;
import dev.turboism.sdk.cubism.hook.ModelFileHooks;
import dev.turboism.sdk.event.cubism.EditorExitEvent;
import dev.turboism.sdk.event.cubism.EditorStartupEvent;
import dev.turboism.sdk.event.cubism.ProjectFileLifecycleEvent;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.List;
import java.util.Objects;

/** Discovers project-file and editor lifecycle overrides from plugin entrypoints. */
public final class ProjectLifecycleHookRegistry {

    public static final String OBSERVE_PERMISSION = ParameterHookRegistry.OBSERVE_PERMISSION;

    private final ProjectFileLifecycleCoordinator projectFiles;
    private final Object lifecycleLock = new Object();
    private final EditorLifecycleCoordinator editor;
    private final java.util.Map<String, List<Registration>> eventAdapters =
        new java.util.HashMap<>();

    public ProjectLifecycleHookRegistry(
        final ProjectFileLifecycleCoordinator projectFiles,
        final EditorLifecycleCoordinator editor
    ) {
        this.projectFiles = Objects.requireNonNull(projectFiles, "projectFiles");
        this.editor = Objects.requireNonNull(editor, "editor");
    }

    public ProjectFileLifecycleCoordinator projectFiles() {
        return projectFiles;
    }

    public EditorLifecycleCoordinator editor() {
        return editor;
    }

    /**
     * Registers a plugin's model, animation, and editor lifecycle hooks for the lifetime of the host
     * session, with no scope-bound detachment.
     *
     * @param descriptor identity and permissions of the registering plugin
     * @param entrypoints the plugin's entrypoint instances, in invocation order
     * @param logger sink for hook failures raised by this plugin
     * @throws NullPointerException when any argument is null
     */
    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger
    ) {
        register(descriptor, entrypoints, logger, null);
    }

    /**
     * Registers a plugin's project-file and editor lifecycle hooks, filtered from the ordered
     * entrypoints by the hook interfaces they implement. Observation capability comes from the
     * descriptor's declared permissions; a plugin lacking it is still registered but receives no
     * callbacks. When {@code scope} is non-null any earlier registration for this plugin id is dropped
     * first and this generation detaches on scope disposal, with rollback if arming the scope fails.
     *
     * @param descriptor identity and permissions of the registering plugin
     * @param entrypoints the plugin's entrypoint instances, in invocation order
     * @param logger sink for hook failures raised by this plugin
     * @param scope plugin scope whose disposal unregisters this generation, or null for session scope
     * @throws NullPointerException when {@code descriptor}, {@code entrypoints} or {@code logger} is null
     */
    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger,
        final DisposableScope scope
    ) {
        final PluginDescriptor plugin = Objects.requireNonNull(descriptor, "descriptor");
        final List<? extends TurboismPlugin> ordered = List.copyOf(
            Objects.requireNonNull(entrypoints, "entrypoints")
        );
        final PluginLogger pluginLogger = Objects.requireNonNull(logger, "logger");
        final boolean observeAllowed = hasPermission(plugin, OBSERVE_PERMISSION);
        final List<ModelFileHooks> modelHooks = ordered.stream()
            .filter(ModelFileHooks.class::isInstance)
            .map(ModelFileHooks.class::cast)
            .toList();
        final List<AnimationFileHooks> animationHooks = ordered.stream()
            .filter(AnimationFileHooks.class::isInstance)
            .map(AnimationFileHooks.class::cast)
            .toList();
        final List<EditorLifecycleHooks> editorHooks = ordered.stream()
            .filter(EditorLifecycleHooks.class::isInstance)
            .map(EditorLifecycleHooks.class::cast)
            .toList();
        final boolean hasProjectHooks = !modelHooks.isEmpty() || !animationHooks.isEmpty();
        synchronized (lifecycleLock) {
            if (scope == null) {
                if (hasProjectHooks) {
                    projectFiles.register(new ProjectFileLifecycleCoordinator.PluginHooks(
                        plugin, modelHooks, animationHooks, pluginLogger, observeAllowed
                    ));
                }
                if (!editorHooks.isEmpty()) {
                    editor.register(new EditorLifecycleCoordinator.PluginHooks(
                        plugin, editorHooks, pluginLogger, observeAllowed
                    ));
                }
                return;
            }
            projectFiles.unregister(plugin.id());
            editor.unregister(plugin.id());
            if (!hasProjectHooks && editorHooks.isEmpty()) {
                return;
            }
            final Object token = new Object();
            try {
                if (hasProjectHooks) {
                    projectFiles.register(token, new ProjectFileLifecycleCoordinator.PluginHooks(
                        plugin, modelHooks, animationHooks, pluginLogger, observeAllowed
                    ));
                }
                if (!editorHooks.isEmpty()) {
                    editor.register(token, new EditorLifecycleCoordinator.PluginHooks(
                        plugin, editorHooks, pluginLogger, observeAllowed
                    ));
                }
                scope.register(() -> unregisterGeneration(plugin.id(), token));
            } catch (RuntimeException | Error failure) {
                unregisterGeneration(plugin.id(), token);
                throw failure;
            }
        }
    }

    /** Registers file-hook overrides as exact-generation broker adapters in Preview. */
    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger,
        final DisposableScope scope,
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner
    ) {
        final PluginDescriptor plugin = Objects.requireNonNull(descriptor, "descriptor");
        final List<? extends TurboismPlugin> ordered = List.copyOf(
            Objects.requireNonNull(entrypoints, "entrypoints")
        );
        final DisposableScope pluginScope = Objects.requireNonNull(scope, "scope");
        final RuntimeEventBroker runtimeBroker = Objects.requireNonNull(broker, "broker");
        final PluginEventOwnerKey eventOwner = Objects.requireNonNull(owner, "owner");
        final PluginLogger sink = Objects.requireNonNull(logger, "logger");
        registerProjectCompatibility(plugin, ordered, sink, pluginScope);
        if (!hasPermission(plugin, OBSERVE_PERMISSION)) {
            return;
        }
        final List<Registration> installed = new java.util.ArrayList<>();
        int entrypointOrdinal = 0;
        for (TurboismPlugin entrypoint : ordered) {
            if (entrypoint instanceof ModelFileHooks hooks) {
                adaptModel(
                    runtimeBroker, eventOwner, entrypointOrdinal, entrypoint, hooks,
                    sink, installed
                );
            }
            if (entrypoint instanceof AnimationFileHooks hooks) {
                adaptAnimation(
                    runtimeBroker, eventOwner, entrypointOrdinal, entrypoint, hooks,
                    sink, installed
                );
            }
            if (entrypoint instanceof EditorLifecycleHooks hooks) {
                adaptEditor(
                    runtimeBroker, eventOwner, entrypointOrdinal, entrypoint, hooks,
                    sink, installed
                );
            }
            entrypointOrdinal++;
        }
        if (installed.isEmpty()) {
            return;
        }
        synchronized (lifecycleLock) {
            closeEventAdapters(plugin.id());
            eventAdapters.put(plugin.id(), List.copyOf(installed));
            try {
                pluginScope.register(() -> closeEventAdapters(plugin.id()));
            } catch (RuntimeException | Error failure) {
                closeEventAdapters(plugin.id());
                unregister(plugin.id());
                throw failure;
            }
        }
    }

    private void registerProjectCompatibility(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger,
        final DisposableScope scope
    ) {
        final List<? extends TurboismPlugin> editorOnly = entrypoints.stream()
            .filter(EditorLifecycleHooks.class::isInstance)
            .toList();
        register(descriptor, editorOnly, logger, scope);
    }

    /**
     * Detaches every project-file and editor lifecycle registration held under the given plugin id,
     * regardless of generation. Unknown ids are ignored.
     *
     * @param pluginId id of the plugin to detach
     */
    public void unregister(final String pluginId) {
        synchronized (lifecycleLock) {
            closeEventAdapters(pluginId);
            projectFiles.unregister(pluginId);
            editor.unregister(pluginId);
        }
    }

    private static void adaptEditor(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final int entrypointOrdinal,
        final TurboismPlugin entrypoint,
        final EditorLifecycleHooks hooks,
        final PluginLogger logger,
        final List<Registration> installed
    ) {
        adaptEditorState(
            broker, owner, entrypointOrdinal, 24, entrypoint,
            "beforeEditorStartup", EditorStartupEvent.Before.class,
            event -> hooks.beforeEditorStartup(event.editor()), logger, installed
        );
        adaptEditorState(
            broker, owner, entrypointOrdinal, 25, entrypoint,
            "onEditorStarted", EditorStartupEvent.On.class,
            event -> hooks.onEditorStarted(event.editor()), logger, installed
        );
        adaptEditorState(
            broker, owner, entrypointOrdinal, 26, entrypoint,
            "afterEditorStartup", EditorStartupEvent.After.class,
            event -> hooks.afterEditorStartup(event.editor()), logger, installed
        );
        adaptEditorState(
            broker, owner, entrypointOrdinal, 27, entrypoint,
            "beforeEditorExit", EditorExitEvent.Before.class,
            event -> hooks.beforeEditorExit(event.editor()), logger, installed
        );
        adaptEditorState(
            broker, owner, entrypointOrdinal, 28, entrypoint,
            "onEditorExiting", EditorExitEvent.On.class,
            event -> hooks.onEditorExiting(event.editor()), logger, installed
        );
        adaptEditorState(
            broker, owner, entrypointOrdinal, 29, entrypoint,
            "afterEditorExit", EditorExitEvent.After.class,
            event -> hooks.afterEditorExit(event.result()), logger, installed
        );
    }

    private static <T extends dev.turboism.sdk.event.EventBus.TurboismEvent> void adaptEditorState(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final int entrypointOrdinal,
        final int methodOrdinal,
        final TurboismPlugin entrypoint,
        final String methodName,
        final Class<T> eventType,
        final java.util.function.Consumer<T> callback,
        final PluginLogger logger,
        final List<Registration> installed
    ) {
        if (!overridesEditor(entrypoint, methodName) || subscribes(entrypoint, eventType)) {
            return;
        }
        installed.add(broker.subscribeAdapter(
            owner, eventType, entrypointOrdinal, methodOrdinal,
            event -> invoke(logger, methodName, () -> callback.accept(event))
        ));
    }

    private static boolean overridesEditor(
        final Object entrypoint,
        final String methodName
    ) {
        final Class<?> parameterType = methodName.equals("afterEditorExit")
            ? dev.turboism.sdk.cubism.EditorExitResult.class
            : dev.turboism.sdk.cubism.EditorLifecycleSnapshot.class;
        try {
            final java.lang.reflect.Method method = entrypoint.getClass()
                .getMethod(methodName, parameterType);
            return method.getDeclaringClass() != EditorLifecycleHooks.class
                && method.getDeclaringClass() != dev.turboism.sdk.cubism.CubismPlugin.class;
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException(
                "Editor lifecycle hook contract is unavailable: " + methodName,
                failure
            );
        }
    }

    private static void adaptModel(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final int entrypointOrdinal,
        final TurboismPlugin entrypoint,
        final ModelFileHooks hooks,
        final PluginLogger logger,
        final List<Registration> installed
    ) {
        adaptFile(
            broker, owner, entrypointOrdinal, entrypoint, ProjectContentKind.MODEL,
            hooks, null, logger, installed
        );
    }

    private static void adaptAnimation(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final int entrypointOrdinal,
        final TurboismPlugin entrypoint,
        final AnimationFileHooks hooks,
        final PluginLogger logger,
        final List<Registration> installed
    ) {
        adaptFile(
            broker, owner, entrypointOrdinal, entrypoint, ProjectContentKind.ANIMATION,
            null, hooks, logger, installed
        );
    }

    private static void adaptFile(
        final RuntimeEventBroker broker,
        final PluginEventOwnerKey owner,
        final int entrypointOrdinal,
        final TurboismPlugin entrypoint,
        final ProjectContentKind kind,
        final ModelFileHooks model,
        final AnimationFileHooks animation,
        final PluginLogger logger,
        final List<Registration> installed
    ) {
        final int base = kind == ProjectContentKind.MODEL ? 0 : 12;
        for (dev.turboism.sdk.cubism.ProjectFileOperationType operation :
            dev.turboism.sdk.cubism.ProjectFileOperationType.values()) {
            final int offset = operation.ordinal() * 3;
            final String before = methodName("before", operation, kind);
            final String on = methodName("on", operation, kind);
            final String after = methodName("after", operation, kind);
            if (overrides(entrypoint, before)
                && !subscribes(entrypoint, ProjectFileLifecycleEvent.Before.class)) {
                installed.add(broker.subscribeAdapter(
                    owner, ProjectFileLifecycleEvent.Before.class,
                    entrypointOrdinal, base + offset, event -> {
                        if (event.operation().kind() != kind
                            || event.operation().operation() != operation) return;
                        invoke(logger, before, () -> invokeBefore(model, animation, event));
                    }
                ));
            }
            if (overrides(entrypoint, on)
                && !subscribes(entrypoint, ProjectFileLifecycleEvent.On.class)) {
                installed.add(broker.subscribeAdapter(
                    owner, ProjectFileLifecycleEvent.On.class,
                    entrypointOrdinal, base + offset + 1, event -> {
                        if (event.operation().kind() != kind
                            || event.operation().operation() != operation) return;
                        invoke(logger, on, () -> invokeOn(model, animation, event));
                    }
                ));
            }
            if (overrides(entrypoint, after)
                && !subscribes(entrypoint, ProjectFileLifecycleEvent.After.class)) {
                installed.add(broker.subscribeAdapter(
                    owner, ProjectFileLifecycleEvent.After.class,
                    entrypointOrdinal, base + offset + 2, event -> {
                        if (event.operation().kind() != kind
                            || event.operation().operation() != operation) return;
                        invoke(logger, after, () -> invokeAfter(model, animation, event));
                    }
                ));
            }
        }
    }

    private static void invokeBefore(
        final ModelFileHooks model,
        final AnimationFileHooks animation,
        final ProjectFileLifecycleEvent.Before event
    ) {
        if (model != null) {
            switch (event.operation().operation()) {
                case CREATE -> model.beforeCreateModel(event.operation());
                case OPEN -> model.beforeOpenModel(event.operation());
                case SAVE -> model.beforeSaveModel(event.operation());
                case CLOSE -> model.beforeCloseModel(event.operation());
            }
        } else {
            switch (event.operation().operation()) {
                case CREATE -> animation.beforeCreateAnimation(event.operation());
                case OPEN -> animation.beforeOpenAnimation(event.operation());
                case SAVE -> animation.beforeSaveAnimation(event.operation());
                case CLOSE -> animation.beforeCloseAnimation(event.operation());
            }
        }
    }

    private static void invokeOn(
        final ModelFileHooks model,
        final AnimationFileHooks animation,
        final ProjectFileLifecycleEvent.On event
    ) {
        if (model != null) {
            switch (event.operation().operation()) {
                case CREATE -> model.onModelCreated(event.content());
                case OPEN -> model.onModelOpened(event.content());
                case SAVE -> model.onModelSaved(event.content());
                case CLOSE -> model.onModelClosed(event.content());
            }
        } else {
            switch (event.operation().operation()) {
                case CREATE -> animation.onAnimationCreated(event.content());
                case OPEN -> animation.onAnimationOpened(event.content());
                case SAVE -> animation.onAnimationSaved(event.content());
                case CLOSE -> animation.onAnimationClosed(event.content());
            }
        }
    }

    private static void invokeAfter(
        final ModelFileHooks model,
        final AnimationFileHooks animation,
        final ProjectFileLifecycleEvent.After event
    ) {
        if (model != null) {
            switch (event.operation().operation()) {
                case CREATE -> model.afterCreateModel(event.result());
                case OPEN -> model.afterOpenModel(event.result());
                case SAVE -> model.afterSaveModel(event.result());
                case CLOSE -> model.afterCloseModel(event.result());
            }
        } else {
            switch (event.operation().operation()) {
                case CREATE -> animation.afterCreateAnimation(event.result());
                case OPEN -> animation.afterOpenAnimation(event.result());
                case SAVE -> animation.afterSaveAnimation(event.result());
                case CLOSE -> animation.afterCloseAnimation(event.result());
            }
        }
    }

    private static String methodName(
        final String phase,
        final dev.turboism.sdk.cubism.ProjectFileOperationType operation,
        final ProjectContentKind kind
    ) {
        final String action = switch (operation) {
            case CREATE -> "Create";
            case OPEN -> "Open";
            case SAVE -> "Save";
            case CLOSE -> "Close";
        };
        final String content = kind == ProjectContentKind.MODEL ? "Model" : "Animation";
        if (phase.equals("on")) {
            final String past = switch (operation) {
                case CREATE -> "Created";
                case OPEN -> "Opened";
                case SAVE -> "Saved";
                case CLOSE -> "Closed";
            };
            return "on" + content + past;
        }
        return phase + action + content;
    }

    private static boolean overrides(final Object entrypoint, final String methodName) {
        final boolean after = methodName.startsWith("after");
        final boolean on = methodName.startsWith("on");
        final Class<?> parameterType = after
            ? dev.turboism.sdk.cubism.ProjectFileOperationResult.class
            : on
                ? dev.turboism.sdk.cubism.ProjectContentSnapshot.class
                : dev.turboism.sdk.cubism.ProjectFileOperation.class;
        try {
            final java.lang.reflect.Method method = entrypoint.getClass()
                .getMethod(methodName, parameterType);
            return method.getDeclaringClass() != ModelFileHooks.class
                && method.getDeclaringClass() != AnimationFileHooks.class
                && method.getDeclaringClass() != dev.turboism.sdk.cubism.CubismPlugin.class;
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException(
                "Project-file hook contract is unavailable: " + methodName,
                failure
            );
        }
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

    private static void invoke(
        final PluginLogger logger,
        final String phase,
        final Runnable invocation
    ) {
        try {
            invocation.run();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            try {
                logger.error("Cubism project-file hook failed safely: " + phase, failure);
            } catch (Throwable ignored) {
                // Diagnostic failure must not replace the hook failure.
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException("Legacy project-file hook failed: " + phase, failure);
        }
    }

    private void closeEventAdapters(final String pluginId) {
        final List<Registration> registrations = eventAdapters.remove(pluginId);
        if (registrations == null) return;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            registrations.get(index).close();
        }
    }

    private void unregisterGeneration(final String pluginId, final Object token) {
        synchronized (lifecycleLock) {
            projectFiles.unregister(pluginId, token);
            editor.unregister(pluginId, token);
        }
    }

    private static boolean hasPermission(
        final PluginDescriptor descriptor,
        final String permissionId
    ) {
        return descriptor.permissions().stream()
            .anyMatch(permission -> permission.id().equals(permissionId));
    }
}
