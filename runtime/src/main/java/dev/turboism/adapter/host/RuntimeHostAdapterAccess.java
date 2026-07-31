package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.ui.action.RuntimeEditorUiActionRouter;
import dev.turboism.ui.appearance.AppearanceCoordinator;
import dev.turboism.ui.appearance.control.ControlAppearanceCoordinator;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.EditorUiHostLifecycle;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;
import dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator;

/** Unforgeable runtime composition handle for a verified, fail-closed host session. */
public sealed interface RuntimeHostAdapterAccess permits HostSession, SessionRuntimeHostAdapterAccess {

    RuntimeHostAdapters adapters();

    CubismModelAccess modelAccess();

    ParameterLifecycleCoordinator parameterLifecycle();

    PartLifecycleCoordinator partLifecycle();

    TextureAtlasLayoutCoordinator textureAtlasLayouts();
    dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator textureAtlasNativeInvocations();
    EditorObjectLifecycleCoordinator editorObjectLifecycle();

    PhysicsEditorCoordinator physicsEditorCoordinator();

    EditorUiHostLifecycle editorUiLifecycle();

    EditorUiContributionAuthority editorUiContributions();

    RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation();

    RuntimeEditorUiActionRouter editorUiActionRouter();

    EditorUiPluginResourceRegistry editorUiPluginResources();

    dev.turboism.ui.context.NativeObjectContextMenuBridge.Handler objectContextMenuHandler();

    dev.turboism.ui.context.NativeParameterPointContextMenuBridge.Handler parameterPointMenuHandler();

    dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance();

    java.util.Optional<dev.turboism.mapping.verification.VerifiedMemberResolver> boundingBoxOverlayResolver();

    AppearanceCoordinator appearanceCoordinator();

    dev.turboism.sdk.ui.table.SceneTableService sceneTable();

    ControlAppearanceCoordinator controlAppearanceCoordinator();
}

/** Non-closeable adapter view used when lifecycle ownership remains with bootstrap ingress. */
final class SessionRuntimeHostAdapterAccess implements RuntimeHostAdapterAccess {

    private final RuntimeHostAdapters adapters;
    private final CubismModelAccess modelAccess;
    private final ParameterLifecycleCoordinator parameterLifecycle;
    private final PartLifecycleCoordinator partLifecycle;
    private final TextureAtlasLayoutCoordinator textureAtlasLayouts;
    private final dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator textureAtlasNativeInvocations;
    private final EditorObjectLifecycleCoordinator editorObjectLifecycle;
    private final PhysicsEditorCoordinator physicsEditorCoordinator;
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
    private final ControlAppearanceCoordinator controlAppearanceCoordinator;

    SessionRuntimeHostAdapterAccess(
        final RuntimeHostAdapters adapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final TextureAtlasLayoutCoordinator textureAtlasLayouts,
        final dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator textureAtlasNativeInvocations,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final PhysicsEditorCoordinator physicsEditorCoordinator,
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
        final ControlAppearanceCoordinator controlAppearanceCoordinator
    ) {
        this.adapters = java.util.Objects.requireNonNull(adapters, "adapters");
        this.modelAccess = java.util.Objects.requireNonNull(modelAccess, "modelAccess");
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
        this.physicsEditorCoordinator = java.util.Objects.requireNonNull(
            physicsEditorCoordinator,
            "physicsEditorCoordinator"
        );
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
        this.controlAppearanceCoordinator = java.util.Objects.requireNonNull(
            controlAppearanceCoordinator,
            "controlAppearanceCoordinator"
        );
    }

    @Override
    public RuntimeHostAdapters adapters() {
        return adapters;
    }

    @Override
    public CubismModelAccess modelAccess() {
        return modelAccess;
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
    public PhysicsEditorCoordinator physicsEditorCoordinator() {
        return physicsEditorCoordinator;
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
    public ControlAppearanceCoordinator controlAppearanceCoordinator() {
        return controlAppearanceCoordinator;
    }
}
