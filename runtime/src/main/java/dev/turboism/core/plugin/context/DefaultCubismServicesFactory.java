package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.service.query.ModelHierarchyQueryServiceImpl;
import dev.turboism.adapter.cubism.service.query.ParameterQueryServiceImpl;
import dev.turboism.adapter.cubism.service.query.SelectionQueryServiceImpl;
import dev.turboism.adapter.cubism.service.read.CubismReadCapabilityServiceImpl;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.adapter.host.PluginScopedCubismModelAccess;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;

import java.util.concurrent.atomic.AtomicBoolean;

final class DefaultCubismServicesFactory implements CubismServicesFactory {

    private static final CubismModelAccess UNAVAILABLE_MODEL_ACCESS = () -> {
        throw new IllegalStateException("No verified active Cubism Core model is available.");
    };

    private static final CoreRuntimeInfo UNAVAILABLE_CORE_RUNTIME = new CoreRuntimeInfo() {
        private UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("Core runtime metadata is unavailable.");
        }
        @Override public dev.turboism.sdk.cubism.core.CoreVersion version() { throw unavailable(); }
        @Override public dev.turboism.sdk.cubism.core.CoreCapabilities capabilities() { throw unavailable(); }
        @Override public dev.turboism.sdk.cubism.core.MocInspector mocInspector() { throw unavailable(); }
    };

    private final RuntimeHostAdapters hostAdapters;
    private final CubismModelAccess modelAccess;
    private final CoreRuntimeInfo coreRuntimeInfo;
    private final ParameterLifecycleCoordinator parameterLifecycle;
    private final PartLifecycleCoordinator partLifecycle;
    private final EditorObjectLifecycleCoordinator editorObjectLifecycle;
    private final PhysicsEditorCoordinator physicsEditorCoordinator;

    DefaultCubismServicesFactory() {
        this(RuntimeHostAdapters.safeMode());
    }

    DefaultCubismServicesFactory(final RuntimeHostAdapters hostAdapters) {
        this(
            hostAdapters,
            UNAVAILABLE_MODEL_ACCESS,
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator()
        );
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess
    ) {
        this(
            hostAdapters,
            modelAccess,
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator()
        );
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle
    ) {
        this(
            hostAdapters,
            modelAccess,
            parameterLifecycle,
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator()
        );
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle
    ) {
        this(
            hostAdapters,
            modelAccess,
            parameterLifecycle,
            partLifecycle,
            new EditorObjectLifecycleCoordinator()
        );
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle
    ) {
        this(hostAdapters, modelAccess, parameterLifecycle, partLifecycle, editorObjectLifecycle,
            new PhysicsEditorCoordinator());
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final PhysicsEditorCoordinator physicsEditorCoordinator
    ) {
        this(
            hostAdapters,
            modelAccess,
            UNAVAILABLE_CORE_RUNTIME,
            parameterLifecycle,
            partLifecycle,
            editorObjectLifecycle,
            physicsEditorCoordinator
        );
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess,
        final CoreRuntimeInfo coreRuntimeInfo,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final PhysicsEditorCoordinator physicsEditorCoordinator
    ) {
        this.hostAdapters = java.util.Objects.requireNonNull(hostAdapters, "hostAdapters");
        this.modelAccess = java.util.Objects.requireNonNull(modelAccess, "modelAccess");
        this.coreRuntimeInfo = java.util.Objects.requireNonNull(coreRuntimeInfo, "coreRuntimeInfo");
        this.parameterLifecycle = java.util.Objects.requireNonNull(parameterLifecycle, "parameterLifecycle");
        this.partLifecycle = java.util.Objects.requireNonNull(partLifecycle, "partLifecycle");
        this.editorObjectLifecycle = java.util.Objects.requireNonNull(editorObjectLifecycle, "editorObjectLifecycle");
        this.physicsEditorCoordinator = java.util.Objects.requireNonNull(
            physicsEditorCoordinator, "physicsEditorCoordinator"
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
        final AtomicBoolean activeScope = new AtomicBoolean(true);
        dependencies.disposableScope().register(() -> activeScope.set(false));
        final CubismModelAccess pluginModelAccess = PluginScopedCubismModelAccess.bind(
            modelAccess,
            dependencies.disposableScope()
        );
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            dependencies.hostSnapshotSource(),
            permissionGate,
            pluginModelAccess,
            coreRuntimeInfo,
            parameterLifecycle,
            partLifecycle,
            editorObjectLifecycle,
            activeScope::get
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
            ),
            dependencies.permissions().stream().anyMatch(permission ->
                CubismFacadeImpl.MODEL_WRITE_PERMISSION.equals(permission.id())
            ) ? physicsEditorCoordinator : dev.turboism.sdk.cubism.physics.PhysicsEditorService.unavailable()
        );
    }
}
