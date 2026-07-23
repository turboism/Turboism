package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.service.query.ModelHierarchyQueryServiceImpl;
import dev.turboism.adapter.cubism.service.query.ParameterQueryServiceImpl;
import dev.turboism.adapter.cubism.service.query.SelectionQueryServiceImpl;
import dev.turboism.adapter.cubism.service.read.CubismReadCapabilityServiceImpl;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.model.CubismModelAccess;

final class DefaultCubismServicesFactory implements CubismServicesFactory {

    private static final CubismModelAccess UNAVAILABLE_MODEL_ACCESS = () -> {
        throw new IllegalStateException("No verified active Cubism Core model is available.");
    };

    private final RuntimeHostAdapters hostAdapters;
    private final CubismModelAccess modelAccess;
    private final ParameterLifecycleCoordinator parameterLifecycle;

    DefaultCubismServicesFactory() {
        this(RuntimeHostAdapters.safeMode());
    }

    DefaultCubismServicesFactory(final RuntimeHostAdapters hostAdapters) {
        this(hostAdapters, UNAVAILABLE_MODEL_ACCESS, new ParameterLifecycleCoordinator());
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess
    ) {
        this(hostAdapters, modelAccess, new ParameterLifecycleCoordinator());
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle
    ) {
        this.hostAdapters = java.util.Objects.requireNonNull(hostAdapters, "hostAdapters");
        this.modelAccess = java.util.Objects.requireNonNull(modelAccess, "modelAccess");
        this.parameterLifecycle = java.util.Objects.requireNonNull(
            parameterLifecycle,
            "parameterLifecycle"
        );
    }

    @Override
    public CubismContextServices create(final CorePluginContext.Dependencies dependencies) {
        final CubismPermissionGate permissionGate = new CubismPermissionGate(
            dependencies.descriptor().id(),
            dependencies.permissions(),
            dependencies.cubismAuditSink(),
            dependencies.clock()
        );
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            dependencies.hostSnapshotSource(),
            permissionGate,
            modelAccess,
            parameterLifecycle
        );
        return new CubismContextServices(
            facade,
            new ParameterQueryServiceImpl(facade, permissionGate),
            new SelectionQueryServiceImpl(facade, permissionGate, dependencies.runtimeScheduler()),
            new ModelHierarchyQueryServiceImpl(facade, permissionGate),
            new CubismReadCapabilityServiceImpl(
                facade,
                dependencies.m12ReadSnapshotSource(),
                hostAdapters.themeStatus(),
                hostAdapters.renderStatus(),
                hostAdapters.projectWorkspace(),
                hostAdapters.clipMaskRead(),
                dependencies.descriptor().id(),
                permissionGate
            )
        );
    }
}
