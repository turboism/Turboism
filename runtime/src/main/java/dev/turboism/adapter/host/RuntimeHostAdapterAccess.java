package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.lifecycle.EditorLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ProjectFileLifecycleCoordinator;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.ui.action.RuntimeEditorUiActionRouter;
import dev.turboism.ui.appearance.AppearanceCoordinator;
import dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.EditorUiHostLifecycle;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;
import dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator;

/** Unforgeable runtime composition handle for a verified, fail-closed host session. */
public sealed interface RuntimeHostAdapterAccess permits HostSession, SessionRuntimeHostAdapterAccess {

    /**
     * @return the composed adapter set of the verified session; never null
     */
    RuntimeHostAdapters adapters();

    /**
     * @return the exact reviewed Cubism Editor version of the active connection,
     *     or empty while no verified Editor-model slice is active
     */
    java.util.Optional<String> cubismEditorVersion();

    /**
     * @return the unified Cubism model object access; never null
     */
    CubismModelAccess modelAccess();

    /**
     * Live Editor object selection for the session snapshot seam. Implementations return
     * {@link HostSnapshotSource.HostSelection#empty()} while no verified selection read is
     * wired; a wired read may propagate live-read failures.
     */
    HostSnapshotSource.HostSelection currentHostSelection();

    /**
     * @return the host undo-history facade; never null
     */
    CubismHistory history();

    /**
     * @return the snapshot source used only by model-appearance projections; never null
     */
    HostSnapshotSource modelAppearanceSource();

    /**
     * @return the Core runtime information surface; never null
     */
    dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntimeInfo();

    /**
     * @return the versioned Editor command adapter; never null
     */
    dev.turboism.adapter.cubism.command.EditorCommandAdapter editorCommands();

    /**
     * @return the parameter write/lifecycle coordinator; never null
     */
    ParameterLifecycleCoordinator parameterLifecycle();

    /**
     * @return the part opacity write/lifecycle coordinator; never null
     */
    PartLifecycleCoordinator partLifecycle();

    /**
     * @return the texture-atlas layout apply coordinator; never null
     */
    TextureAtlasLayoutCoordinator textureAtlasLayouts();

    /**
     * @return the coordinator wrapping verified texture-atlas native invocations; never null
     */
    dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator textureAtlasNativeInvocations();

    /**
     * @return the texture-atlas editor UI this access was composed with; never null
     */
    dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi textureAtlasEditorUi();

    /**
     * @return the texture-atlas editor session this access was composed with; never null
     */
    dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession textureAtlasEditorSession();

    /**
     * @return the registry of texture-atlas layout algorithms available to plugins; never null
     */
    dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms();

    /**
     * @return the coordinator for editor object (document/model) lifecycle observation; never null
     */
    EditorObjectLifecycleCoordinator editorObjectLifecycle();

    /**
     * @return the coordinator for project file open/save/close lifecycle events; never null
     */
    ProjectFileLifecycleCoordinator projectFileLifecycle();

    /**
     * @return the coordinator for editor-level lifecycle events such as exit; never null
     */
    EditorLifecycleCoordinator editorLifecycleEvents();

    /**
     * @return the physics editor bridge coordinator; never null
     */
    PhysicsEditorCoordinator physicsEditorCoordinator();

    /**
     * @return the mesh mirror-axis service; never null
     */
    RuntimeMeshMirrorAxisService meshMirrorAxisService();

    /**
     * @return the mesh edit UI service; never null
     */
    RuntimeMeshEditUiService meshEditUiService();

    /**
     * @return the editor UI host lifecycle surface; never null
     */
    EditorUiHostLifecycle editorUiLifecycle();

    /**
     * @return the authority admitting editor UI contributions; never null
     */
    EditorUiContributionAuthority editorUiContributions();

    /**
     * @return the coordinator activating the embedded panel; never null
     */
    RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation();

    /**
     * @return the router dispatching editor UI actions; never null
     */
    RuntimeEditorUiActionRouter editorUiActionRouter();

    /**
     * @return the registry of plugin-contributed UI resources; never null
     */
    EditorUiPluginResourceRegistry editorUiPluginResources();

    /**
     * @return the handler augmenting native object context menus; never null
     */
    dev.turboism.ui.context.NativeObjectContextMenuBridge.Handler objectContextMenuHandler();

    /**
     * @return the handler for parameter-point context menu notifications; never null
     */
    dev.turboism.ui.context.NativeParameterPointContextMenuBridge.Handler parameterPointMenuHandler();

    /**
     * @return the coordinator cleaning up empty docks; never null
     */
    dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance();

    /**
     * @return the verified member resolver backing bounding-box overlays, or empty when the
     *     active connection cannot supply one
     */
    java.util.Optional<dev.turboism.mapping.verification.VerifiedMemberResolver> boundingBoxOverlayResolver();

    /**
     * @return the appearance (theme/LaF) coordinator; never null
     */
    AppearanceCoordinator appearanceCoordinator();

    /**
     * @return the scene table service; never null
     */
    dev.turboism.sdk.ui.table.SceneTableService sceneTable();

    /**
     * @return the Cubism log service; never null
     */
    dev.turboism.sdk.runtime.CubismLogService cubismLog();

    /**
     * @return the sink reporting palette filter visibility changes; never null
     */
    dev.turboism.ui.filter.PaletteFilterVisibilitySink paletteFilterSink();

    /**
     * @return the palette appearance coordinator; never null
     */
    PaletteAppearanceCoordinator paletteAppearanceCoordinator();

    /**
     * @return the workspace coordinator; never null
     */
    dev.turboism.ui.workspace.WorkspaceCoordinator workspaceCoordinator();

    /**
     * @return the workspace layout coordinator; may be null before the first connection, in
     *     which case callers fall back to an unavailable layout service
     */
    dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator workspaceLayoutCoordinator();

    /**
     * @return the runtime-owned native texture-atlas automatic-layout dispatch callback;
     *     the verified host hook wraps it in the native-invocation scope, and a
     *     {@code false} result defers to the host's own packing
     */
    java.util.function.BooleanSupplier textureAtlasAutoLayoutDispatch();
}

/** Non-closeable adapter view used when lifecycle ownership remains with bootstrap ingress. */
final class SessionRuntimeHostAdapterAccess implements RuntimeHostAdapterAccess {

    private final RuntimeHostAdapters adapters;
    private final java.util.function.Supplier<java.util.Optional<String>> cubismEditorVersion;
    private final CubismModelAccess modelAccess;
    private final CubismHistory history;
    private final HostSnapshotSource modelAppearanceSource;
    private final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntimeInfo;
    private final dev.turboism.adapter.cubism.command.EditorCommandAdapter editorCommands;
    private final ParameterLifecycleCoordinator parameterLifecycle;
    private final PartLifecycleCoordinator partLifecycle;
    private final TextureAtlasLayoutCoordinator textureAtlasLayouts;
    private final dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator textureAtlasNativeInvocations;
    private final EditorObjectLifecycleCoordinator editorObjectLifecycle;
    private final ProjectFileLifecycleCoordinator projectFileLifecycle;
    private final EditorLifecycleCoordinator editorLifecycleEvents;
    private final PhysicsEditorCoordinator physicsEditorCoordinator;
    private final RuntimeMeshMirrorAxisService meshMirrorAxisService;
    private final RuntimeMeshEditUiService meshEditUiService;
    private final EditorUiHostLifecycle editorUiLifecycle;
    private final EditorUiContributionAuthority editorUiContributions;
    private final RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation;
    private final RuntimeEditorUiActionRouter editorUiActionRouter;
    private final EditorUiPluginResourceRegistry editorUiPluginResources;
    private final dev.turboism.ui.context.NativeObjectContextMenuBridge.Handler objectContextMenuHandler;
    private final dev.turboism.ui.context.NativeParameterPointContextMenuBridge.Handler parameterPointMenuHandler;
    private final dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance;
    private final java.util.Optional<dev.turboism.mapping.verification.VerifiedMemberResolver> boundingBoxOverlayResolver;
    private final AppearanceCoordinator appearanceCoordinator;
    private final dev.turboism.sdk.ui.table.SceneTableService sceneTable;
    private final dev.turboism.sdk.runtime.CubismLogService cubismLog;
    private final dev.turboism.ui.filter.PaletteFilterVisibilitySink paletteFilterSink;
    private final PaletteAppearanceCoordinator paletteAppearanceCoordinator;
    private final dev.turboism.ui.workspace.WorkspaceCoordinator workspaceCoordinator;
    private final dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator workspaceLayoutCoordinator;
    private final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi textureAtlasEditorUi;
    private final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession textureAtlasEditorSession;
    private final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms;
    private final java.util.function.BooleanSupplier textureAtlasAutoLayoutDispatch;

    SessionRuntimeHostAdapterAccess(
        final RuntimeHostAdapters adapters,
        final java.util.function.Supplier<java.util.Optional<String>> cubismEditorVersion,
        final CubismModelAccess modelAccess,
        final CubismHistory history,
        final HostSnapshotSource modelAppearanceSource,
        final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntimeInfo,
        final dev.turboism.adapter.cubism.command.EditorCommandAdapter editorCommands,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final TextureAtlasLayoutCoordinator textureAtlasLayouts,
        final dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator textureAtlasNativeInvocations,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final ProjectFileLifecycleCoordinator projectFileLifecycle,
        final EditorLifecycleCoordinator editorLifecycleEvents,
        final PhysicsEditorCoordinator physicsEditorCoordinator,
        final RuntimeMeshMirrorAxisService meshMirrorAxisService,
        final RuntimeMeshEditUiService meshEditUiService,
        final EditorUiHostLifecycle editorUiLifecycle,
        final EditorUiContributionAuthority editorUiContributions,
        final RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation,
        final RuntimeEditorUiActionRouter editorUiActionRouter,
        final EditorUiPluginResourceRegistry editorUiPluginResources,
        final dev.turboism.ui.context.NativeObjectContextMenuBridge.Handler objectContextMenuHandler,
        final dev.turboism.ui.context.NativeParameterPointContextMenuBridge.Handler parameterPointMenuHandler,
        final dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance,
        final java.util.Optional<dev.turboism.mapping.verification.VerifiedMemberResolver> boundingBoxOverlayResolver,
        final AppearanceCoordinator appearanceCoordinator,
        final dev.turboism.sdk.ui.table.SceneTableService sceneTable,
        final dev.turboism.sdk.runtime.CubismLogService cubismLog,
        final dev.turboism.ui.filter.PaletteFilterVisibilitySink paletteFilterSink,
        final PaletteAppearanceCoordinator paletteAppearanceCoordinator,
        final dev.turboism.ui.workspace.WorkspaceCoordinator workspaceCoordinator,
        final dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator workspaceLayoutCoordinator,
        final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi textureAtlasEditorUi,
        final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession textureAtlasEditorSession,
        final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms,
        final java.util.function.BooleanSupplier textureAtlasAutoLayoutDispatch
    ) {
        this.adapters = java.util.Objects.requireNonNull(adapters, "adapters");
        this.cubismEditorVersion = java.util.Objects.requireNonNull(
            cubismEditorVersion, "cubismEditorVersion"
        );
        this.modelAccess = java.util.Objects.requireNonNull(modelAccess, "modelAccess");
        this.history = java.util.Objects.requireNonNull(history, "history");
        this.modelAppearanceSource = java.util.Objects.requireNonNull(
            modelAppearanceSource, "modelAppearanceSource"
        );
        this.coreRuntimeInfo = java.util.Objects.requireNonNull(coreRuntimeInfo, "coreRuntimeInfo");
        this.editorCommands = java.util.Objects.requireNonNull(editorCommands, "editorCommands");
        this.parameterLifecycle = java.util.Objects.requireNonNull(
            parameterLifecycle,
            "parameterLifecycle"
        );
        this.partLifecycle = java.util.Objects.requireNonNull(partLifecycle, "partLifecycle");
        this.textureAtlasLayouts = java.util.Objects.requireNonNull(
            textureAtlasLayouts,
            "textureAtlasLayouts"
        );
        this.textureAtlasNativeInvocations = java.util.Objects.requireNonNull(
            textureAtlasNativeInvocations,
            "textureAtlasNativeInvocations"
        );
        this.editorObjectLifecycle = java.util.Objects.requireNonNull(
            editorObjectLifecycle,
            "editorObjectLifecycle"
        );
        this.projectFileLifecycle = java.util.Objects.requireNonNull(
            projectFileLifecycle,
            "projectFileLifecycle"
        );
        this.editorLifecycleEvents = java.util.Objects.requireNonNull(
            editorLifecycleEvents,
            "editorLifecycleEvents"
        );
        this.physicsEditorCoordinator = java.util.Objects.requireNonNull(
            physicsEditorCoordinator,
            "physicsEditorCoordinator"
        );
        this.meshMirrorAxisService = java.util.Objects.requireNonNull(
            meshMirrorAxisService,
            "meshMirrorAxisService"
        );
        this.meshEditUiService = java.util.Objects.requireNonNull(meshEditUiService, "meshEditUiService");
        this.editorUiLifecycle = java.util.Objects.requireNonNull(
            editorUiLifecycle,
            "editorUiLifecycle"
        );
        this.editorUiContributions = java.util.Objects.requireNonNull(
            editorUiContributions,
            "editorUiContributions"
        );
        this.embeddedPanelActivation = java.util.Objects.requireNonNull(
            embeddedPanelActivation,
            "embeddedPanelActivation"
        );
        this.editorUiActionRouter = java.util.Objects.requireNonNull(
            editorUiActionRouter,
            "editorUiActionRouter"
        );
        this.editorUiPluginResources = java.util.Objects.requireNonNull(
            editorUiPluginResources,
            "editorUiPluginResources"
        );
        this.objectContextMenuHandler = objectContextMenuHandler;
        this.parameterPointMenuHandler = parameterPointMenuHandler;
        this.dockMaintenance = java.util.Objects.requireNonNull(dockMaintenance, "dockMaintenance");
        this.boundingBoxOverlayResolver = java.util.Objects.requireNonNull(
            boundingBoxOverlayResolver,
            "boundingBoxOverlayResolver"
        );
        this.appearanceCoordinator = java.util.Objects.requireNonNull(
            appearanceCoordinator,
            "appearanceCoordinator"
        );
        this.sceneTable = java.util.Objects.requireNonNull(sceneTable, "sceneTable");
        this.cubismLog = java.util.Objects.requireNonNull(cubismLog, "cubismLog");
        this.paletteFilterSink = java.util.Objects.requireNonNull(paletteFilterSink, "paletteFilterSink");
        this.paletteAppearanceCoordinator = java.util.Objects.requireNonNull(
            paletteAppearanceCoordinator,
            "paletteAppearanceCoordinator"
        );
        this.workspaceCoordinator = java.util.Objects.requireNonNull(
            workspaceCoordinator,
            "workspaceCoordinator"
        );
        // The layout coordinator is per-connection and legitimately absent before the first
        // connection; CorePluginContext falls back to WorkspaceLayoutService.unavailable().
        this.workspaceLayoutCoordinator = workspaceLayoutCoordinator;
        this.textureAtlasEditorUi = java.util.Objects.requireNonNull(
            textureAtlasEditorUi, "textureAtlasEditorUi");
        this.textureAtlasEditorSession = java.util.Objects.requireNonNull(
            textureAtlasEditorSession, "textureAtlasEditorSession");
        this.textureAtlasAlgorithms = java.util.Objects.requireNonNull(
            textureAtlasAlgorithms, "textureAtlasAlgorithms");
        this.textureAtlasAutoLayoutDispatch = java.util.Objects.requireNonNull(
            textureAtlasAutoLayoutDispatch, "textureAtlasAutoLayoutDispatch");
    }

    @Override
    public RuntimeHostAdapters adapters() {
        return adapters;
    }

    @Override
    public java.util.Optional<String> cubismEditorVersion() {
        return java.util.Objects.requireNonNull(
            cubismEditorVersion.get(), "cubismEditorVersion.get()"
        );
    }

    @Override
    public CubismModelAccess modelAccess() {
        return modelAccess;
    }

    @Override
    public HostSnapshotSource.HostSelection currentHostSelection() {
        return modelAccess instanceof DynamicCubismModelAccess dynamic
            ? dynamic.currentHostSelection()
            : HostSnapshotSource.HostSelection.empty();
    }

    @Override
    public CubismHistory history() {
        return history;
    }

    /** @return the snapshot source used only by model-appearance projections. */
    public HostSnapshotSource modelAppearanceSource() {
        return modelAppearanceSource;
    }

    @Override
    public dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntimeInfo() {
        return coreRuntimeInfo;
    }

    @Override
    public dev.turboism.adapter.cubism.command.EditorCommandAdapter editorCommands() {
        return editorCommands;
    }

    @Override
    public ParameterLifecycleCoordinator parameterLifecycle() {
        return parameterLifecycle;
    }

    @Override
    public PartLifecycleCoordinator partLifecycle() {
        return partLifecycle;
    }

    @Override
    public TextureAtlasLayoutCoordinator textureAtlasLayouts() {
        return textureAtlasLayouts;
    }

    @Override
    public dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator
        textureAtlasNativeInvocations() {
        return textureAtlasNativeInvocations;
    }

    @Override
    public EditorObjectLifecycleCoordinator editorObjectLifecycle() {
        return editorObjectLifecycle;
    }

    @Override
    public ProjectFileLifecycleCoordinator projectFileLifecycle() {
        return projectFileLifecycle;
    }

    @Override
    public EditorLifecycleCoordinator editorLifecycleEvents() {
        return editorLifecycleEvents;
    }

    @Override
    public PhysicsEditorCoordinator physicsEditorCoordinator() {
        return physicsEditorCoordinator;
    }

    @Override
    public RuntimeMeshMirrorAxisService meshMirrorAxisService() {
        return meshMirrorAxisService;
    }

    @Override
    public RuntimeMeshEditUiService meshEditUiService() {
        return meshEditUiService;
    }

    @Override
    public EditorUiHostLifecycle editorUiLifecycle() {
        return editorUiLifecycle;
    }

    @Override
    public EditorUiContributionAuthority editorUiContributions() {
        return editorUiContributions;
    }

    @Override
    public RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation() {
        return embeddedPanelActivation;
    }

    @Override
    public RuntimeEditorUiActionRouter editorUiActionRouter() {
        return editorUiActionRouter;
    }

    @Override
    public EditorUiPluginResourceRegistry editorUiPluginResources() {
        return editorUiPluginResources;
    }

    @Override
    public dev.turboism.ui.context.NativeObjectContextMenuBridge.Handler objectContextMenuHandler() {
        return objectContextMenuHandler;
    }

    @Override
    public dev.turboism.ui.context.NativeParameterPointContextMenuBridge.Handler parameterPointMenuHandler() {
        return parameterPointMenuHandler;
    }


    @Override
    public dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance() {
        return dockMaintenance;
    }

    @Override
    public java.util.Optional<dev.turboism.mapping.verification.VerifiedMemberResolver> boundingBoxOverlayResolver() {
        return boundingBoxOverlayResolver;
    }

    @Override
    public AppearanceCoordinator appearanceCoordinator() {
        return appearanceCoordinator;
    }

    @Override
    public dev.turboism.sdk.ui.table.SceneTableService sceneTable() {
        return sceneTable;
    }

    @Override
    public dev.turboism.sdk.runtime.CubismLogService cubismLog() {
        return cubismLog;
    }

    @Override
    public dev.turboism.ui.filter.PaletteFilterVisibilitySink paletteFilterSink() {
        return paletteFilterSink;
    }

    @Override
    public PaletteAppearanceCoordinator paletteAppearanceCoordinator() {
        return paletteAppearanceCoordinator;
    }

    @Override
    public dev.turboism.ui.workspace.WorkspaceCoordinator workspaceCoordinator() {
        return workspaceCoordinator;
    }

    @Override
    public dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator workspaceLayoutCoordinator() {
        return workspaceLayoutCoordinator;
    }

    /** @return the texture-atlas editor UI this access was composed with. */
    public dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi textureAtlasEditorUi() {
        return textureAtlasEditorUi;
    }

    /** @return the texture-atlas editor session this access was composed with. */
    public dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession textureAtlasEditorSession() {
        return textureAtlasEditorSession;
    }

    /** @return the registry of texture-atlas layout algorithms available to plugins. */
    public dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms() {
        return textureAtlasAlgorithms;
    }

    @Override
    public java.util.function.BooleanSupplier textureAtlasAutoLayoutDispatch() {
        return textureAtlasAutoLayoutDispatch;
    }
}
