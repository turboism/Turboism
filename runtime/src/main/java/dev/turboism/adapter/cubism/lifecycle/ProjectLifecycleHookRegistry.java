package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.hook.AnimationFileHooks;
import dev.turboism.sdk.cubism.hook.EditorLifecycleHooks;
import dev.turboism.sdk.cubism.hook.ModelFileHooks;
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

    public ProjectLifecycleHookRegistry(
        final ProjectFileLifecycleCoordinator projectFiles,
        final EditorLifecycleCoordinator editor
    ) {
        this.projectFiles = Objects.requireNonNull(projectFiles, "projectFiles");
        this.editor = Objects.requireNonNull(editor, "editor");
    }

    public void register(
        final PluginDescriptor descriptor,
        final List<? extends TurboismPlugin> entrypoints,
        final PluginLogger logger
    ) {
        register(descriptor, entrypoints, logger, null);
    }

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

    public void unregister(final String pluginId) {
        synchronized (lifecycleLock) {
            projectFiles.unregister(pluginId);
            editor.unregister(pluginId);
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
