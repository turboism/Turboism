package dev.turboism.kernel.context;

/**
 * Permission-scoped views of runtime-shared services for one plugin context.
 *
 * <p>A plugin never receives the runtime's shared baseline service directly: every accessor here
 * re-wraps it behind that plugin's own {@code PermissionChecker} and registers the wrapper with the
 * plugin's disposable scope. The wrappers are built lazily because plugins resolve them through
 * {@link dev.turboism.sdk.plugin.PluginServiceDirectory} slots, not through the context's eager
 * construction path.</p>
 */
final class CorePluginPermissionScopedServices {

    private CorePluginPermissionScopedServices() {}

    /**
     * Binds the shared performance probe to one plugin's permission scope, creating a plugin-owned
     * probe when the runtime published no shared baseline.
     *
     * @param dependencies the plugin's production dependencies
     * @return the permission-checked probe bound to this plugin generation
     */
    static dev.turboism.sdk.performance.PerformanceProbeService performanceProbe(
            final CorePluginContext.Dependencies dependencies) {
        final dev.turboism.sdk.performance.PerformanceProbeService shared = dependencies
                .eventBroker()
                .observationBaseline(dev.turboism.sdk.performance.PerformanceProbeService.class)
                .get();
        final dev.turboism.permissions.PermissionChecker permissionChecker =
                dev.turboism.permissions.PermissionChecker.from(dependencies.permissions());
        final dev.turboism.performance.RuntimePerformanceProbeService owned = shared == null
                ? new dev.turboism.performance.RuntimePerformanceProbeService(
                        dependencies.descriptor().id(), permissionChecker, dependencies.clock())
                : null;
        return dev.turboism.performance.PermissionCheckedPerformanceProbeService.bind(
                shared == null ? owned : shared, permissionChecker, dependencies.disposableScope(), owned);
    }

    /**
     * Binds the runtime-shared work-time tracker to one plugin's permission scope.
     *
     * @param dependencies the plugin's production dependencies
     * @return the permission-checked tracker, or an unavailable service when the runtime published
     *     no shared tracker
     */
    static dev.turboism.sdk.cubism.worktime.ProjectWorkTimeService projectWorkTime(
            final CorePluginContext.Dependencies dependencies) {
        final dev.turboism.sdk.cubism.worktime.ProjectWorkTimeService shared = dependencies
                .eventBroker()
                .observationBaseline(dev.turboism.sdk.cubism.worktime.ProjectWorkTimeService.class)
                .get();
        if (shared == null) {
            return dev.turboism.sdk.cubism.worktime.ProjectWorkTimeService.unavailable();
        }
        return dev.turboism.worktime.PermissionCheckedProjectWorkTimeService.bind(
                shared,
                dev.turboism.permissions.PermissionChecker.from(dependencies.permissions()),
                dependencies.disposableScope());
    }
}
