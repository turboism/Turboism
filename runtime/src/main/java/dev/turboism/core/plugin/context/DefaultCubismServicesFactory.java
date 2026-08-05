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
import dev.turboism.adapter.host.PluginScopedCubismModelAccess;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;

import java.util.concurrent.atomic.AtomicBoolean;

final class DefaultCubismServicesFactory implements CubismServicesFactory {

    private static final CubismModelAccess UNAVAILABLE_MODEL_ACCESS = () -> {
        throw new IllegalStateException("No verified active Cubism Core model is available.");
    };

    private final RuntimeHostAdapters hostAdapters;
    private final CubismModelAccess modelAccess;
    private final ParameterLifecycleCoordinator parameterLifecycle;
    private final PartLifecycleCoordinator partLifecycle;
    private final EditorObjectLifecycleCoordinator editorObjectLifecycle;
    private final PhysicsEditorCoordinator physicsEditorCoordinator;
    private final dev.turboism.adapter.cubism.command.EditorCommandAdapter editorCommands;
    private final dev.turboism.adapter.cubism.command.EditorFileCommandResolver editorFiles;

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
            hostAdapters, modelAccess, parameterLifecycle, partLifecycle, editorObjectLifecycle,
            physicsEditorCoordinator, dev.turboism.adapter.cubism.command.EditorCommandAdapter.unavailable()
        );
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final PhysicsEditorCoordinator physicsEditorCoordinator,
        final dev.turboism.adapter.cubism.command.EditorCommandAdapter editorCommands
    ) {
        this(
            hostAdapters, modelAccess, parameterLifecycle, partLifecycle, editorObjectLifecycle,
            physicsEditorCoordinator, editorCommands,
            dev.turboism.adapter.cubism.command.EditorFileCommandResolver.unavailable()
        );
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final PhysicsEditorCoordinator physicsEditorCoordinator,
        final dev.turboism.adapter.cubism.command.EditorCommandAdapter editorCommands,
        final dev.turboism.adapter.cubism.command.EditorFileCommandResolver editorFiles
    ) {
        this.hostAdapters = java.util.Objects.requireNonNull(hostAdapters, "hostAdapters");
        this.modelAccess = java.util.Objects.requireNonNull(modelAccess, "modelAccess");
        this.parameterLifecycle = java.util.Objects.requireNonNull(parameterLifecycle, "parameterLifecycle");
        this.partLifecycle = java.util.Objects.requireNonNull(partLifecycle, "partLifecycle");
        this.editorObjectLifecycle = java.util.Objects.requireNonNull(editorObjectLifecycle, "editorObjectLifecycle");
        this.physicsEditorCoordinator = java.util.Objects.requireNonNull(
            physicsEditorCoordinator, "physicsEditorCoordinator"
        );
        this.editorCommands = java.util.Objects.requireNonNull(editorCommands, "editorCommands");
        this.editorFiles = java.util.Objects.requireNonNull(editorFiles, "editorFiles");
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
            ) ? physicsEditorCoordinator : dev.turboism.sdk.cubism.physics.PhysicsEditorService.unavailable(),
            new dev.turboism.adapter.cubism.command.RuntimeEditorCommandService(
                editorCommands, permissionGate, editorFiles, activeScope::get
            )
        );
    }
}
