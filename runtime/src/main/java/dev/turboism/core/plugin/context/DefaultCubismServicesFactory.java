package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.backup.AutoBackupAdapter;
import dev.turboism.adapter.cubism.backup.AutoBackupCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.service.query.ModelHierarchyQueryServiceImpl;
import dev.turboism.adapter.cubism.service.query.ParameterQueryServiceImpl;
import dev.turboism.adapter.cubism.service.query.SelectionQueryServiceImpl;
import dev.turboism.adapter.cubism.service.read.CubismReadCapabilityServiceImpl;
import dev.turboism.adapter.cubism.service.read.CubismReadPermissionGate;
import dev.turboism.adapter.cubism.service.clipmask.CubismClipMaskServiceImpl;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.adapter.host.PluginScopedCubismModelAccess;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;
import dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator;

import dev.turboism.sdk.cubism.history.CubismHistory;
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
    private final java.util.function.Supplier<java.util.Optional<String>> cubismEditorVersion;
    private final CubismModelAccess modelAccess;
    private final HostSnapshotSource appearanceSource;
    private final CoreRuntimeInfo coreRuntimeInfo;
    private final ParameterLifecycleCoordinator parameterLifecycle;
    private final PartLifecycleCoordinator partLifecycle;
    private final EditorObjectLifecycleCoordinator editorObjectLifecycle;
    private final PhysicsEditorCoordinator physicsEditorCoordinator;
    private CubismHistory history = CubismHistory.unavailable();

    DefaultCubismServicesFactory() {
        this(RuntimeHostAdapters.safeMode());
    }

    private final PaletteAppearanceCoordinator paletteAppearanceCoordinator;
    private final TextureAtlasLayoutCoordinator textureAtlasLayouts;
    private final dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator textureAtlasNativeInvocations;
    private final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi textureAtlasEditorUi;
    private final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession textureAtlasEditorSession;
    private final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms;
    private final dev.turboism.adapter.cubism.command.EditorCommandAdapter editorCommands;
    private final dev.turboism.adapter.cubism.command.EditorFileCommandResolver editorFiles;
    private final AutoBackupAdapter autoBackup;

    DefaultCubismServicesFactory(final RuntimeHostAdapters hostAdapters) {
        this(
            hostAdapters,
            java.util.Optional::empty,
            UNAVAILABLE_MODEL_ACCESS,
            UNAVAILABLE_CORE_RUNTIME,
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            new PhysicsEditorCoordinator(),
            PluginScopedCubismModelAccess.appearanceSource(hostAdapters.projectWorkspace(), UNAVAILABLE_MODEL_ACCESS),
            new PaletteAppearanceCoordinator(),
            new TextureAtlasLayoutCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi(),
            dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession.unavailable(),
            new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry(),
            dev.turboism.adapter.cubism.command.EditorCommandAdapter.unavailable(),
            dev.turboism.adapter.cubism.command.EditorFileCommandResolver.unavailable(),
            hostAdapters.autoBackup(),
            CubismHistory.unavailable()
        );
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final java.util.function.Supplier<java.util.Optional<String>> cubismEditorVersion,
        final CubismModelAccess modelAccess,
        final CoreRuntimeInfo coreRuntimeInfo,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final PhysicsEditorCoordinator physicsEditorCoordinator,
        final HostSnapshotSource appearanceSource,
        final PaletteAppearanceCoordinator paletteAppearanceCoordinator,
        final TextureAtlasLayoutCoordinator textureAtlasLayouts,
        final dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator textureAtlasNativeInvocations,
        final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi textureAtlasEditorUi,
        final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession textureAtlasEditorSession,
        final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms,
        final dev.turboism.adapter.cubism.command.EditorCommandAdapter editorCommands,
        final dev.turboism.adapter.cubism.command.EditorFileCommandResolver editorFiles,
        final AutoBackupAdapter autoBackup,
        final CubismHistory history
    ) {
        this.hostAdapters = java.util.Objects.requireNonNull(hostAdapters, "hostAdapters");
        this.cubismEditorVersion = java.util.Objects.requireNonNull(
            cubismEditorVersion, "cubismEditorVersion"
        );
        this.modelAccess = java.util.Objects.requireNonNull(modelAccess, "modelAccess");
        this.appearanceSource = java.util.Objects.requireNonNull(appearanceSource, "appearanceSource");
        this.coreRuntimeInfo = java.util.Objects.requireNonNull(coreRuntimeInfo, "coreRuntimeInfo");
        this.parameterLifecycle = java.util.Objects.requireNonNull(parameterLifecycle, "parameterLifecycle");
        this.partLifecycle = java.util.Objects.requireNonNull(partLifecycle, "partLifecycle");
        this.editorObjectLifecycle = java.util.Objects.requireNonNull(editorObjectLifecycle, "editorObjectLifecycle");
        this.physicsEditorCoordinator = java.util.Objects.requireNonNull(
            physicsEditorCoordinator, "physicsEditorCoordinator"
        );
        this.paletteAppearanceCoordinator = java.util.Objects.requireNonNull(
            paletteAppearanceCoordinator, "paletteAppearanceCoordinator"
        );
        this.textureAtlasLayouts = java.util.Objects.requireNonNull(
            textureAtlasLayouts, "textureAtlasLayouts"
        );
        this.textureAtlasNativeInvocations = java.util.Objects.requireNonNull(
            textureAtlasNativeInvocations, "textureAtlasNativeInvocations"
        );
        this.textureAtlasEditorUi = java.util.Objects.requireNonNull(
            textureAtlasEditorUi, "textureAtlasEditorUi"
        );
        this.textureAtlasEditorSession = java.util.Objects.requireNonNull(
            textureAtlasEditorSession, "textureAtlasEditorSession"
        );
        this.textureAtlasAlgorithms = java.util.Objects.requireNonNull(
            textureAtlasAlgorithms, "textureAtlasAlgorithms"
        );
        this.editorCommands = java.util.Objects.requireNonNull(editorCommands, "editorCommands");
        this.editorFiles = java.util.Objects.requireNonNull(editorFiles, "editorFiles");
        this.autoBackup = java.util.Objects.requireNonNull(autoBackup, "autoBackup");
        this.history = java.util.Objects.requireNonNull(history, "history");
    }

    /** Wiring seam for tests: the adapter this factory forwards to the backup coordinator. */
    AutoBackupAdapter autoBackupAdapter() {
        return autoBackup;
    }

    DefaultCubismServicesFactory(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final PhysicsEditorCoordinator physicsEditorCoordinator,
        final CubismHistory history
    ) {
        this(
            hostAdapters,
            java.util.Optional::empty,
            modelAccess,
            UNAVAILABLE_CORE_RUNTIME,
            parameterLifecycle,
            partLifecycle,
            editorObjectLifecycle,
            physicsEditorCoordinator,
            PluginScopedCubismModelAccess.appearanceSource(hostAdapters.projectWorkspace(), UNAVAILABLE_MODEL_ACCESS),
            new PaletteAppearanceCoordinator(),
            new TextureAtlasLayoutCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi(),
            dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession.unavailable(),
            new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry(),
            dev.turboism.adapter.cubism.command.EditorCommandAdapter.unavailable(),
            dev.turboism.adapter.cubism.command.EditorFileCommandResolver.unavailable(),
            hostAdapters.autoBackup(),
            history
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
        final PermissionChecker permissionChecker = PermissionChecker.from(permissionGate);
        final AtomicBoolean activeScope = new AtomicBoolean(true);
        dependencies.disposableScope().register(() -> activeScope.set(false));
        final CubismModelAccess pluginModelAccess = PluginScopedCubismModelAccess.bind(
            modelAccess,
            dependencies.disposableScope(),
            dependencies.descriptor().id(),
            permissionChecker,
            appearanceSource,
            paletteAppearanceCoordinator,
            modelAccess instanceof NativeLabelColorAuthoring authoring
                ? authoring
                : NativeLabelColorAuthoring.unavailable()
        );
        final CubismFacadeImpl facade = new CubismFacadeImpl(
            dependencies.hostSnapshotSource(),
            permissionGate,
            pluginModelAccess,
            coreRuntimeInfo,
            parameterLifecycle,
            partLifecycle,
            textureAtlasLayouts,
            textureAtlasNativeInvocations,
            editorObjectLifecycle,
            activeScope::get,
            textureAtlasEditorUi,
            textureAtlasEditorSession,
            textureAtlasAlgorithms,
            history
        );
        final CubismReadCapabilityServiceImpl readCapabilityService = new CubismReadCapabilityServiceImpl(
            facade,
            dependencies.m12ReadSnapshotSource(),
            hostAdapters.themeStatus(),
            hostAdapters.renderStatus(),
            hostAdapters.projectWorkspace(),
            hostAdapters.clipMaskRead(),
            dependencies.descriptor().id(),
            CubismReadPermissionGate.from(permissionGate)
        );
        final AutoBackupCoordinator backupCoordinator = new AutoBackupCoordinator(
            autoBackup,
            dependencies.eventBus(),
            dependencies.clock(),
            AutoBackupCoordinator.DEFAULT_POLL_TIMEOUT_MILLIS,
            reason -> dependencies.logger().warn("auto-backup " + reason)
        );
        dependencies.disposableScope().register(backupCoordinator::close);
        final CubismContextServices services = new CubismContextServices(
            facade,
            new ParameterQueryServiceImpl(facade, permissionGate),
            new SelectionQueryServiceImpl(facade, permissionGate, dependencies.runtimeScheduler()),
            new ModelHierarchyQueryServiceImpl(facade, permissionGate),
            readCapabilityService,
            new dev.turboism.adapter.cubism.model.RuntimeModelObjectService(
                pluginModelAccess,
                permissionChecker,
                activeScope::get
            ),
            dependencies.permissions().stream().anyMatch(permission ->
                CubismFacadeImpl.MODEL_WRITE_PERMISSION.equals(permission.id())
            ) ? physicsEditorCoordinator : dev.turboism.sdk.cubism.physics.PhysicsEditorService.unavailable(),
            new CubismClipMaskServiceImpl(readCapabilityService, modelAccess),
            new dev.turboism.adapter.cubism.command.RuntimeEditorCommandService(
                editorCommands, permissionGate, editorFiles, activeScope::get
            ),
            backupCoordinator
        );
        return new CubismApiAvailabilityInterceptor(cubismEditorVersion).intercept(services);
    }
}
