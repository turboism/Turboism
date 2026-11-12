package dev.turboism.kernel.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.host.HostSessionSnapshotSource;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import java.util.List;
import java.util.Objects;

/**
 * Host-service composition for one {@link CorePluginContext}.
 *
 * <p>Assembles the capability bundles a context exposes: the framework core services, the mesh and
 * UI bundles, and the host bundle. It is kept out of the context so the context stays the plugin's
 * façade — resolving one service per SDK call — while the wiring decisions (which admission the
 * editor-API interceptor sees, whether the host session supplies a snapshot source) live in one
 * place.</p>
 */
final class CorePluginContextComposition {

    private CorePluginContextComposition() {}

    /**
     * Composes the host services a plugin context exposes.
     *
     * @param dependencies the caller's dependencies; a verified host session replaces the host
     *     snapshot source so plugins read the live session instead of a static one
     * @param environment the assembled host environment and scoped services
     * @return the composed services and the dependencies actually bound
     */
    static Composition compose(
            final CorePluginContext.Dependencies dependencies, final PluginContextEnvironment environment) {
        Objects.requireNonNull(dependencies, "dependencies");
        final PluginContextEnvironment env = Objects.requireNonNull(environment, "environment");
        final RuntimeHostAdapterAccess hostAccess = env.hostAccess();
        final CorePluginContext.Dependencies resolvedDependencies = hostAccess == null
                ? dependencies
                : dependencies.withHostSnapshotSource(
                        dependencies.hostSnapshotSource() instanceof HostSessionSnapshotSource
                                ? dependencies.hostSnapshotSource()
                                : HostSessionSnapshotSource.forSession(
                                        hostAccess.adapters().projectWorkspace(),
                                        hostAccess::currentHostSelection,
                                        dev.turboism.adapter.cubism.EditorForegroundState::isForeground));
        final RuntimeHostAdapters adapters = env.hostAdapters();
        final CubismServicesFactory servicesFactory = env.servicesFactory();
        final CubismEditorApiAvailabilityInterceptor editorApiAvailability = new CubismEditorApiAvailabilityInterceptor(
                servicesFactory instanceof DefaultCubismServicesFactory defaultFactory
                        ? defaultFactory.cubismEditorVersion()
                        : java.util.Optional::empty,
                servicesFactory instanceof DefaultCubismServicesFactory defaultFactory
                        ? defaultFactory.admittedCubismCapabilities()
                        : java.util.Set::of,
                servicesFactory instanceof DefaultCubismServicesFactory defaultFactory
                        ? defaultFactory.admittedCubismGeneration()
                        : java.util.Optional::empty);
        final CubismContextServices cubismServices = servicesFactory.create(
                resolvedDependencies,
                env.taskScheduler() instanceof dev.turboism.task.RuntimePluginTaskScheduler runtimeTasks
                        ? runtimeTasks
                        : null,
                env.generationAdmission());
        final MeshCapabilityBundle meshCapabilities = MeshCapabilityBundle.create(hostAccess, resolvedDependencies);
        final UiCapabilityBundle uiCapabilities = UiCapabilityBundle.create(
                hostAccess, adapters, resolvedDependencies, env.localization(), editorApiAvailability);
        final HostCapabilityBundle hostCapabilities =
                HostCapabilityBundle.create(hostAccess, env, editorApiAvailability);
        return new Composition(
                resolvedDependencies,
                cubismServices,
                hostCapabilities,
                List.of(cubismServices, meshCapabilities, uiCapabilities, hostCapabilities));
    }

    /**
     * The composed context state.
     *
     * @param dependencies the dependencies bound to the context, with the host snapshot source
     *     replaced when a verified host session is present
     * @param cubismServices the framework core services exposed through the SDK façade
     * @param hostCapabilities the host bundle supplying localized, scheduled and storage services
     * @param capabilityBundles every bundle whose slots the service directory registers
     */
    record Composition(
            CorePluginContext.Dependencies dependencies,
            CubismContextServices cubismServices,
            HostCapabilityBundle hostCapabilities,
            List<PluginCapabilityBundle> capabilityBundles) {}
}
