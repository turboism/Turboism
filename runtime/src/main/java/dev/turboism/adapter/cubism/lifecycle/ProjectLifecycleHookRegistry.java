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

    /**
     * Detaches every project-file and editor lifecycle registration held under the given plugin id,
     * regardless of generation. Unknown ids are ignored.
     *
     * @param pluginId id of the plugin to detach
     */
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
