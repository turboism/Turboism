package dev.turboism.sdk.plugin;

import dev.turboism.sdk.Incubating;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.hostread.AsyncHostReadService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.script.ScriptService;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.UserFileAccessService;
import java.util.List;

/**
 * Runtime context provided to a plugin during {@link TurboismPlugin#init(PluginContext)}.
 *
 * <p>Optional services are discovered exclusively through {@link #services()}: the
 * directory reports which services the context installed and resolves them through
 * {@link PluginServiceDirectory#find(Class)} / {@link PluginServiceDirectory#require(Class)}.
 * Every service also exposes an {@code unavailable()} sentinel and {@code isAvailable()}
 * for probing ({@code false} only on the sentinel); plugins that prefer sentinel-driven
 * degradation resolve with {@code find(...).orElse(Service.unavailable())}.</p>
 */
public interface PluginContext {

    /**
     * Returns the current plugin's descriptor as read from plugin meta. */
    PluginDescriptor descriptor();

    /**
     * Returns the framework logger scoped to {@link #descriptor() the current plugin}.
     *
     * <p>Every record automatically carries the plugin descriptor id and is written to Turboism's
     * session log and, when available, Cubism's host logger.</p>
     *
     * @return the current plugin's logger
     */
    PluginLogger logger();

    /** Returns the persistent and runtime paths available to the plugin. */
    PluginPaths paths();

    /**
     * Returns the plugin-scoped localization catalog.
     *
     * @return the catalog, never {@code null}; the {@link PluginLocalization#unavailable()}
     *     sentinel when no catalog is installed
     */
    default PluginLocalization localization() {
        return PluginLocalization.unavailable();
    }

    /** Returns the plugin's task scheduler. */
    default PluginTaskScheduler tasks() {
        return PluginTaskScheduler.unavailable();
    }

    /** Returns the asynchronous host read service. */
    default AsyncHostReadService hostReads() {
        return AsyncHostReadService.unavailable();
    }

    /** Returns the plugin's bounded storage service. */
    default PluginStorage storage() {
        return PluginStorage.unavailable();
    }

    /** Returns the script discovery and execution service. */
    @Incubating
    default ScriptService scripts() {
        return ScriptService.unavailable();
    }

    /** Returns the mediated user file access service. */
    default UserFileAccessService userFiles() {
        return UserFileAccessService.unavailable();
    }

    /**
     * Returns the Cubism-facing facade for the current plugin. */
    CubismFacade cubism();

    /** Returns the parameter query service. */
    default ParameterQueryService parameterQuery() {
        return ParameterQueryService.unavailable();
    }

    /** Returns the selection query service. */
    default SelectionQueryService selectionQuery() {
        return SelectionQueryService.unavailable();
    }

    /** Returns the model hierarchy query service. */
    default ModelHierarchyQueryService modelHierarchyQuery() {
        return ModelHierarchyQueryService.unavailable();
    }

    /** Returns the grouped Cubism read service. */
    default CubismReadCapabilityService cubismRead() {
        return CubismReadCapabilityService.unavailable();
    }

    /** Returns the model-object authoring service. */
    default ModelObjectService modelObjects() {
        return ModelObjectService.unavailable();
    }

    /**
     * Returns the permissions the plugin declared in its meta. */
    List<PluginPermission> permissions();

    /**
     * Returns the typed directory over this context's optional services — the canonical
     * service-discovery surface. New optional services land on the directory and on
     * {@link PluginService}; they do not gain a {@link PluginContext} accessor.
     *
     * <p>The default resolves the context's remaining optional accessors
     * ({@link #localization()}, {@link #tasks()}, {@link #hostReads()}, {@link #storage()},
     * {@link #scripts()}, {@link #userFiles()}, {@link #parameterQuery()},
     * {@link #selectionQuery()}, {@link #modelHierarchyQuery()}, {@link #cubismRead()},
     * {@link #modelObjects()} and {@link #config()}); the runtime installs a directory
     * that covers every {@link PluginService} member.</p>
     *
     * @return the service directory for this context; never {@code null}
     */
    default PluginServiceDirectory services() {
        return PluginServices.of(this);
    }

    /** Returns the typed event bus. */
    EventBus eventBus();

    /** Returns the action registry. */
    ActionRegistry actions();

    /** Returns the menu contribution registry. */
    MenuRegistry menus();

    /**
     * Returns the plugin configuration registry. */
    default PluginConfigRegistry config() {
        return PluginConfigRegistry.unavailable();
    }

    /**
     * Returns the scheduler for UI-thread work. */
    UiScheduler uiScheduler();

    /** Returns the plugin's diagnostic report view. */
    DiagnosticReport diagnostics();

    /**
     * Returns the plugin's disposable scope; resources registered into it close in reverse
     * order when the scope closes.
     */
    DisposableScope disposableScope();
}
