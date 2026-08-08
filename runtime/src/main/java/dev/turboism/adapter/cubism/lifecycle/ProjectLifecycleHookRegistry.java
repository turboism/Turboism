package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.hook.AnimationFileHooks;
import dev.turboism.sdk.cubism.hook.EditorLifecycleHooks;
import dev.turboism.sdk.cubism.hook.ModelFileHooks;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.List;
import java.util.Objects;

/** Discovers project-file and editor lifecycle overrides from plugin entrypoints. */
public final class ProjectLifecycleHookRegistry {

    public static final String OBSERVE_PERMISSION = ParameterHookRegistry.OBSERVE_PERMISSION;

    private final ProjectFileLifecycleCoordinator projectFiles;
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
        if (!modelHooks.isEmpty() || !animationHooks.isEmpty()) {
            projectFiles.register(new ProjectFileLifecycleCoordinator.PluginHooks(
                plugin,
                modelHooks,
                animationHooks,
                pluginLogger,
                observeAllowed
            ));
        }
        final List<EditorLifecycleHooks> editorHooks = ordered.stream()
            .filter(EditorLifecycleHooks.class::isInstance)
            .map(EditorLifecycleHooks.class::cast)
            .toList();
        if (!editorHooks.isEmpty()) {
            editor.register(new EditorLifecycleCoordinator.PluginHooks(
                plugin,
                editorHooks,
                pluginLogger,
                observeAllowed
            ));
        }
    }

    public void unregister(final String pluginId) {
        projectFiles.unregister(pluginId);
        editor.unregister(pluginId);
    }

    private static boolean hasPermission(
        final PluginDescriptor descriptor,
        final String permissionId
    ) {
        return descriptor.permissions().stream()
            .anyMatch(permission -> permission.id().equals(permissionId));
    }
}
