package dev.turboism.core.plugin.context;

import dev.turboism.config.RuntimePluginConfigRegistry;
import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.core.event.PluginEventBus;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.failure.RuntimeFailureSink;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.context.RuntimeContextMenuRegistry;
import dev.turboism.ui.filter.RuntimePaletteFilterRegistry;
import dev.turboism.ui.menu.RuntimeMenuRegistry;
import dev.turboism.ui.toolbar.RuntimeMainToolbarRegistry;
import dev.turboism.ui.toolbar.RuntimePaletteToolbarRegistry;
import java.time.Clock;
import java.util.List;
import java.util.function.Consumer;

/**
 * Permission-scoped service wiring for {@link CorePluginContext.Dependencies}.
 *
 * <p>Every registry a plugin reaches through its context is created here from the plugin's own
 * descriptor permissions, so the runtime permission model cannot diverge from the descriptor. The
 * factory is package-private and stateless: it exists so the dependencies record keeps only its
 * data contract instead of carrying the wiring of seven registries as well.</p>
 */
final class CorePluginServiceFactory {

    private CorePluginServiceFactory() {}

    /**
     * @param descriptor plugin descriptor whose declared permissions scope every service
     * @return the descriptor's permissions as context-facing permission values
     */
    static List<PluginPermission> permissionsFromDescriptor(final PluginDescriptor descriptor) {
        return descriptor.permissions().stream()
                .<PluginPermission>map(ref -> new DescriptorPermission(
                        ref.id(), ref.scope(), ref.reason().orElse("")))
                .toList();
    }

    /**
     * Builds the permission-scoped registries one plugin context owns.
     *
     * @param descriptor plugin descriptor supplying the identity every registry tags
     * @param permissions permissions to enforce, already resolved from the descriptor or injected
     * @param paths plugin paths the config registry reads and writes under
     * @param runtimeScheduler scheduler that dispatches plugin work
     * @param disposableScope scope that owns and closes the config registry
     * @param cubismAuditSink receives permission-gate audit events
     * @param clock clock used for audit timestamps
     * @param logger plugin logger the configuration diagnostics are reported through
     * @param failureSink runtime failure collector for configuration faults
     * @param eventBroker shared event broker every plugin event is published through
     * @param eventOwner admitted event owner for this plugin generation
     * @param pluginClassLoader class loader callbacks are dispatched under
     * @return the assembled registries
     */
    static DefaultServices defaultServices(
            final PluginDescriptor descriptor,
            final List<PluginPermission> permissions,
            final PluginPaths paths,
            final RuntimeScheduler runtimeScheduler,
            final DisposableScope disposableScope,
            final Consumer<CubismFacadeAuditEvent> cubismAuditSink,
            final Clock clock,
            final PluginLogger logger,
            final RuntimeFailureSink failureSink,
            final RuntimeEventBroker eventBroker,
            final dev.turboism.core.event.PluginEventOwnerKey eventOwner,
            final ClassLoader pluginClassLoader) {
        final PermissionChecker checker =
                PermissionChecker.from(new CubismPermissionGate(descriptor.id(), permissions, cubismAuditSink, clock));
        final Consumer<StartupReport.DiagnosticProblem> diagnosticSink =
                problem -> logger.warn(problem.code() + ": " + problem.message() + " @ " + problem.path());
        return new DefaultServices(
                new PluginEventBus(eventBroker, eventOwner, checker, pluginClassLoader),
                new RuntimeActionRegistry(runtimeScheduler, diagnosticSink, descriptor.id(), checker, eventBroker),
                new RuntimeMenuRegistry(runtimeScheduler, descriptor.id(), checker),
                new RuntimeMainToolbarRegistry(checker, runtimeScheduler, descriptor.id()),
                new RuntimePaletteToolbarRegistry(checker, runtimeScheduler, descriptor.id()),
                new RuntimePaletteFilterRegistry(checker, runtimeScheduler, descriptor.id()),
                new RuntimeContextMenuRegistry(checker, descriptor.id()),
                legacyConfig(
                        checker,
                        runtimeScheduler,
                        paths.configDir(),
                        descriptor.id(),
                        diagnosticSink,
                        failureSink,
                        disposableScope));
    }

    /**
     * Owns the legacy config registry through the plugin scope, closing it when registration fails
     * so a half-composed context never leaks a registry.
     */
    private static RuntimePluginConfigRegistry legacyConfig(
            final PermissionChecker checker,
            final RuntimeScheduler runtimeScheduler,
            final java.nio.file.Path configDir,
            final String pluginId,
            final Consumer<StartupReport.DiagnosticProblem> diagnosticSink,
            final RuntimeFailureSink failureSink,
            final DisposableScope disposableScope) {
        final RuntimePluginConfigRegistry config = new RuntimePluginConfigRegistry(
                checker, runtimeScheduler, configDir, pluginId, diagnosticSink, failureSink);
        try {
            disposableScope.register(config);
            return config;
        } catch (RuntimeException | Error failure) {
            config.close();
            throw failure;
        }
    }

    private record DescriptorPermission(String id, String scope, String reason) implements PluginPermission {}

    /** The permission-scoped registries one plugin context receives. */
    record DefaultServices(
            EventBus eventBus,
            ActionRegistry actions,
            MenuRegistry menus,
            MainToolbarRegistry mainToolbar,
            PaletteToolbarRegistry paletteToolbar,
            PaletteFilterRegistry paletteFilter,
            ContextMenuRegistry contextMenu,
            PluginConfigRegistry config) {}
}
