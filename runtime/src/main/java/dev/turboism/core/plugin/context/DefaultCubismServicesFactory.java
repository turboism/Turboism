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
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator;
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
    private final TextureAtlasLayoutCoordinator textureAtlasLayouts;
    private final dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator textureAtlasNativeInvocations;
    private final EditorObjectLifecycleCoordinator editorObjectLifecycle;
    private final PhysicsEditorCoordinator physicsEditorCoordinator;
    private final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi textureAtlasEditorUi;
    private final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession textureAtlasEditorSession;

    DefaultCubismServicesFactory() {
        this(RuntimeHostAdapters.safeMode());
    }

    DefaultCubismServicesFactory(final RuntimeHostAdapters hostAdapters) {
        this(
            hostAdapters,
            UNAVAILABLE_MODEL_ACCESS,
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new TextureAtlasLayoutCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi(),
            dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession.unavailable()
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
            new TextureAtlasLayoutCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi(),
            dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession.unavailable()
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
            new TextureAtlasLayoutCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi(),
            dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession.unavailable()
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
            new TextureAtlasLayoutCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi(),
            dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession.unavailable()
        );
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final TextureAtlasLayoutCoordinator textureAtlasLayouts,
        final TextureAtlasNativeInvocationCoordinator textureAtlasNativeInvocations,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi textureAtlasEditorUi,
        final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession textureAtlasEditorSession
    ) {
        this(hostAdapters, modelAccess, parameterLifecycle, partLifecycle, textureAtlasLayouts,
            textureAtlasNativeInvocations, editorObjectLifecycle, new PhysicsEditorCoordinator(),
            textureAtlasEditorUi, textureAtlasEditorSession);
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final TextureAtlasLayoutCoordinator textureAtlasLayouts,
        final TextureAtlasNativeInvocationCoordinator textureAtlasNativeInvocations,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final PhysicsEditorCoordinator physicsEditorCoordinator,
        final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi textureAtlasEditorUi,
        final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession textureAtlasEditorSession
    ) {
        this.hostAdapters = java.util.Objects.requireNonNull(hostAdapters, "hostAdapters");
        this.modelAccess = java.util.Objects.requireNonNull(modelAccess, "modelAccess");
        this.parameterLifecycle = java.util.Objects.requireNonNull(parameterLifecycle, "parameterLifecycle");
        this.partLifecycle = java.util.Objects.requireNonNull(partLifecycle, "partLifecycle");
        this.textureAtlasLayouts = java.util.Objects.requireNonNull(
            textureAtlasLayouts,
            "textureAtlasLayouts"
        );
        this.textureAtlasNativeInvocations = java.util.Objects.requireNonNull(
            textureAtlasNativeInvocations,
            "textureAtlasNativeInvocations"
        );
        this.editorObjectLifecycle = java.util.Objects.requireNonNull(editorObjectLifecycle, "editorObjectLifecycle");
        this.physicsEditorCoordinator = java.util.Objects.requireNonNull(
            physicsEditorCoordinator, "physicsEditorCoordinator"

        );
        this.textureAtlasEditorUi = java.util.Objects.requireNonNull(
            textureAtlasEditorUi, "textureAtlasEditorUi"
        );
        this.textureAtlasEditorSession = java.util.Objects.requireNonNull(
            textureAtlasEditorSession, "textureAtlasEditorSession"
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
            parameterLifecycle,
            partLifecycle,
            textureAtlasLayouts,
            textureAtlasNativeInvocations,
            editorObjectLifecycle,
            activeScope::get,
            textureAtlasEditorUi,
            textureAtlasEditorSession
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
