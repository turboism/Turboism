package dev.turboism.core.plugin.context;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.service.query.ModelHierarchyQueryServiceImpl;
import dev.turboism.adapter.cubism.service.query.ParameterQueryServiceImpl;
import dev.turboism.adapter.cubism.service.query.SelectionQueryServiceImpl;
import dev.turboism.permissions.CubismPermissionGate;

final class DefaultCubismServicesFactory implements CubismServicesFactory {

    @Override
    public CubismContextServices create(final CorePluginContext.Dependencies dependencies) {
        final CubismPermissionGate permissionGate = new CubismPermissionGate(
            dependencies.descriptor().id(),
            dependencies.permissions(),
            dependencies.cubismAuditSink(),
            dependencies.clock()
        );
        final CubismFacadeImpl facade = new CubismFacadeImpl(dependencies.hostSnapshotSource(), permissionGate);
        return new CubismContextServices(
            facade,
            new ParameterQueryServiceImpl(facade, permissionGate),
            new SelectionQueryServiceImpl(facade, permissionGate, dependencies.runtimeScheduler()),
            new ModelHierarchyQueryServiceImpl(facade, permissionGate)
        );
    }
}
