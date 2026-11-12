package dev.turboism.adapter.host;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.lifecycle.EditorLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ProjectFileLifecycleCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectFileOperationType;
import dev.turboism.ui.action.RuntimeEditorUiActionRouter;
import dev.turboism.ui.appearance.AppearanceCoordinator;
import dev.turboism.ui.appearance.DynamicAppearanceHostProvider;
import dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;
import dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;
import java.util.List;

/**
 * Composition root and owner of every coordinator one host session keeps across connections.
 *
 * <p>These coordinators outlive any single host connection: a plugin-contributed texture-atlas
 * algorithm, a registered UI action, or a palette surface must survive safe mode and host
 * replacement. They are therefore owned here, not by the connection binding, and they close in one
 * fixed order so a throwing coordinator cannot truncate the chain.</p>
 */
final class HostSessionCoordinators {

    private static final String RUNTIME_GRANT_OWNER = "dev.turboism.runtime";
    private static final String RUNTIME_GRANT_SCOPE = "runtime";
    private static final String RUNTIME_GRANT_REASON = "Runtime-owned texture-atlas automatic-layout dispatch.";

    private final DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
    private final DynamicCubismModelAccess dynamicModelAccess = new DynamicCubismModelAccess();
    private final HostSnapshotSource modelAppearanceSource =
            PluginScopedCubismModelAccess.appearanceSource(dynamic.view().projectWorkspace(), dynamicModelAccess);
    private final DynamicCoreRuntimeInfo dynamicCoreRuntime = new DynamicCoreRuntimeInfo();
    private final DynamicEditorCommandAdapter dynamicEditorCommands = new DynamicEditorCommandAdapter();
    private final ParameterLifecycleCoordinator parameterLifecycle = new ParameterLifecycleCoordinator();
    private final PartLifecycleCoordinator partLifecycle = new PartLifecycleCoordinator();
    private final TextureAtlasLayoutCoordinator textureAtlasLayouts = new TextureAtlasLayoutCoordinator();
    private final dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator
            textureAtlasNativeInvocations =
                    new dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator();
    private final dev.turboism.ui.panel.RuntimeHostViewPanelRegistry hostViewPanels =
            new dev.turboism.ui.panel.RuntimeHostViewPanelRegistry();
    private final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi textureAtlasEditorUi =
            new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi(hostViewPanels);
    private final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession textureAtlasEditorSession =
            new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession(
                    textureAtlasEditorUi::binding);
    private final dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry
            textureAtlasAlgorithms =
                    new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry();
    private final dev.turboism.adapter.cubism.textureatlas.TextureAtlasAutoLayoutSelection textureAtlasSelection;
    private final dev.turboism.adapter.cubism.textureatlas.TextureAtlasAutoLayoutDispatcher
            textureAtlasAutoLayoutDispatch;
    private final EditorObjectLifecycleCoordinator editorObjectLifecycle = new EditorObjectLifecycleCoordinator();
    /**
     * Observes edits the Cubism user interface performed, which never enter the Turboism facade.
     * The listener itself only signals this session; every host read and publication is posted to
     * the editor event thread, so nothing runs inside the host's undo admission. A native edit
     * start observed at the {@code beginEdit} hook is published on the same drain, so one native
     * action reports its start before its confirmed operation.
     */
    private final dev.turboism.adapter.cubism.editor.history.NativeEditIngressSession nativeEditIngress =
            new dev.turboism.adapter.cubism.editor.history.NativeEditIngressSession(
                    editorObjectLifecycle.semantic()::publishObserved,
                    editorObjectLifecycle.semantic()::publishObservedStart,
                    javax.swing.SwingUtilities::invokeLater);

    private final ProjectFileLifecycleCoordinator projectFileLifecycle = new ProjectFileLifecycleCoordinator();
    private final EditorLifecycleCoordinator editorLifecycleEvents = new EditorLifecycleCoordinator();
    private final dev.turboism.adapter.cubism.preview.RuntimePreviewSurfaceRegistry previewSurfaces =
            new dev.turboism.adapter.cubism.preview.RuntimePreviewSurfaceRegistry();
    private final PhysicsEditorCoordinator physicsEditorCoordinator = new PhysicsEditorCoordinator(previewSurfaces);
    private final dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService meshMirrorAxisService =
            new dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService();
    private final dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService meshEditUiService =
            new dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService();
    private final dev.turboism.adapter.cubism.mesh.MeshToolCoordinator meshToolCoordinator =
            new dev.turboism.adapter.cubism.mesh.MeshToolCoordinator();
    private final dev.turboism.adapter.cubism.modeling.ModelingToolCoordinator modelingToolCoordinator =
            new dev.turboism.adapter.cubism.modeling.ModelingToolCoordinator();
    private final RuntimeEditorUiHostLifecycle editorUiLifecycle = new RuntimeEditorUiHostLifecycle();
    private final EditorUiContributionAuthority editorUiContributions =
            new EditorUiContributionAuthority(editorUiLifecycle);
    private final RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation =
            new RuntimeEmbeddedPanelActivationCoordinator();
    private final dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance =
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator();
    private final RuntimeEditorUiActionRouter editorUiActionRouter = new RuntimeEditorUiActionRouter();
    private final EditorUiPluginResourceRegistry editorUiPluginResources = new EditorUiPluginResourceRegistry();
    private final DynamicAppearanceHostProvider dynamicAppearance = new DynamicAppearanceHostProvider();
    private final AppearanceCoordinator appearanceCoordinator = new AppearanceCoordinator(dynamicAppearance);
    private final dev.turboism.ui.table.SceneTableHostOperations sceneTableHost =
            new dev.turboism.ui.table.SceneTableHostOperations();
    private final dev.turboism.ui.table.RuntimeSceneTableService sceneTable = sceneTableHost.service();
    private final PaletteAppearanceCoordinator paletteAppearanceCoordinator = new PaletteAppearanceCoordinator();
    private final dev.turboism.ui.palette.PaletteSurfaceCoordinator paletteSurfaceCoordinator =
            new dev.turboism.ui.palette.PaletteSurfaceCoordinator(editorUiPluginResources);
    private final dev.turboism.sdk.runtime.CubismLogService cubismLog =
            new dev.turboism.adapter.cubism.service.log.CubismLogServiceHost();
    private final dev.turboism.ui.workspace.WorkspaceCoordinator workspaceCoordinator =
            new dev.turboism.ui.workspace.WorkspaceCoordinator();

    /**
     * @param textureAtlasSelectionPersistence persistent store for the runtime-owned texture-atlas
     *     automatic-layout selection; null keeps the selection in memory only
     */
    HostSessionCoordinators(
            final dev.turboism.adapter.cubism.textureatlas.TextureAtlasAutoLayoutSelection.Persistence
                    textureAtlasSelectionPersistence) {
        this.textureAtlasSelection = new dev.turboism.adapter.cubism.textureatlas.TextureAtlasAutoLayoutSelection(
                textureAtlasSelectionPersistence);
        this.textureAtlasAlgorithms.bindSelection(textureAtlasSelection);
        this.textureAtlasAutoLayoutDispatch =
                new dev.turboism.adapter.cubism.textureatlas.TextureAtlasAutoLayoutDispatcher(
                        textureAtlasAlgorithms, textureAtlasSelection, runtimeTextureAtlasLayouts());
        registerProjectContentCleanup();
    }

    DynamicRuntimeHostAdapters dynamic() {
        return dynamic;
    }

    DynamicCubismModelAccess dynamicModelAccess() {
        return dynamicModelAccess;
    }

    HostSnapshotSource modelAppearanceSource() {
        return modelAppearanceSource;
    }

    DynamicCoreRuntimeInfo dynamicCoreRuntime() {
        return dynamicCoreRuntime;
    }

    DynamicEditorCommandAdapter dynamicEditorCommands() {
        return dynamicEditorCommands;
    }

    ParameterLifecycleCoordinator parameterLifecycle() {
        return parameterLifecycle;
    }

    PartLifecycleCoordinator partLifecycle() {
        return partLifecycle;
    }

    TextureAtlasLayoutCoordinator textureAtlasLayouts() {
        return textureAtlasLayouts;
    }

    dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator textureAtlasNativeInvocations() {
        return textureAtlasNativeInvocations;
    }

    dev.turboism.ui.panel.RuntimeHostViewPanelRegistry hostViewPanels() {
        return hostViewPanels;
    }

    dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi textureAtlasEditorUi() {
        return textureAtlasEditorUi;
    }

    dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession textureAtlasEditorSession() {
        return textureAtlasEditorSession;
    }

    dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms() {
        return textureAtlasAlgorithms;
    }

    dev.turboism.adapter.cubism.textureatlas.TextureAtlasAutoLayoutSelection textureAtlasSelection() {
        return textureAtlasSelection;
    }

    java.util.function.BooleanSupplier textureAtlasAutoLayoutDispatch() {
        return textureAtlasAutoLayoutDispatch.callback();
    }

    EditorObjectLifecycleCoordinator editorObjectLifecycle() {
        return editorObjectLifecycle;
    }

    dev.turboism.adapter.cubism.editor.history.NativeEditIngressSession nativeEditIngress() {
        return nativeEditIngress;
    }

    ProjectFileLifecycleCoordinator projectFileLifecycle() {
        return projectFileLifecycle;
    }

    EditorLifecycleCoordinator editorLifecycleEvents() {
        return editorLifecycleEvents;
    }

    dev.turboism.adapter.cubism.preview.RuntimePreviewSurfaceRegistry previewSurfaces() {
        return previewSurfaces;
    }

    PhysicsEditorCoordinator physicsEditorCoordinator() {
        return physicsEditorCoordinator;
    }

    dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService meshMirrorAxisService() {
        return meshMirrorAxisService;
    }

    dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService meshEditUiService() {
        return meshEditUiService;
    }

    dev.turboism.adapter.cubism.mesh.MeshToolCoordinator meshToolCoordinator() {
        return meshToolCoordinator;
    }

    dev.turboism.adapter.cubism.modeling.ModelingToolCoordinator modelingToolCoordinator() {
        return modelingToolCoordinator;
    }

    RuntimeEditorUiHostLifecycle editorUiLifecycle() {
        return editorUiLifecycle;
    }

    EditorUiContributionAuthority editorUiContributions() {
        return editorUiContributions;
    }

    RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation() {
        return embeddedPanelActivation;
    }

    dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance() {
        return dockMaintenance;
    }

    RuntimeEditorUiActionRouter editorUiActionRouter() {
        return editorUiActionRouter;
    }

    EditorUiPluginResourceRegistry editorUiPluginResources() {
        return editorUiPluginResources;
    }

    DynamicAppearanceHostProvider dynamicAppearance() {
        return dynamicAppearance;
    }

    AppearanceCoordinator appearanceCoordinator() {
        return appearanceCoordinator;
    }

    dev.turboism.ui.table.SceneTableHostOperations sceneTableHost() {
        return sceneTableHost;
    }

    dev.turboism.ui.table.RuntimeSceneTableService sceneTable() {
        return sceneTable;
    }

    PaletteAppearanceCoordinator paletteAppearanceCoordinator() {
        return paletteAppearanceCoordinator;
    }

    dev.turboism.ui.palette.PaletteSurfaceCoordinator paletteSurfaceCoordinator() {
        return paletteSurfaceCoordinator;
    }

    dev.turboism.sdk.runtime.CubismLogService cubismLog() {
        return cubismLog;
    }

    dev.turboism.ui.workspace.WorkspaceCoordinator workspaceCoordinator() {
        return workspaceCoordinator;
    }

    /**
     * Drops every surface binding the previous connection installed while keeping the coordinators
     * alive. Each step is idempotent, so a teardown that failed midway can run again.
     *
     * @return the first failure of the pass, or a successful outcome
     */
    Outcome detachHostSurfaces() {
        sceneTableHost.disconnect();
        dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge.clearHostContext();
        textureAtlasEditorUi.deactivate();
        nativeEditIngress.deactivate();
        meshEditUiService.resetSession();
        meshMirrorAxisService.resetSession();
        modelingToolCoordinator.disconnect();
        meshToolCoordinator.endSession();
        return Outcome.success();
    }

    /**
     * Retires every coordinator binding the previous connection supplied, keeping the coordinators
     * themselves alive for the next connection.
     *
     * <p>The dynamic adapter publication is deactivated last, after every reader of it stopped
     * receiving new bindings.</p>
     *
     * @return the first failure of the pass, or a successful outcome
     */
    Outcome detachHostBindings() {
        paletteSurfaceCoordinator.clearParameterRowsResolver();
        paletteSurfaceCoordinator.clearNativeIconResolver();
        paletteAppearanceCoordinator.clearNativeIconResolver();
        paletteAppearanceCoordinator.clearParameterRowSource();
        paletteAppearanceCoordinator.clearParameterEditorSource();
        dynamicAppearance.deactivate();
        paletteAppearanceCoordinator.invalidate();
        dynamicModelAccess.deactivate();
        dynamicCoreRuntime.deactivate();
        textureAtlasLayouts.deactivate();
        textureAtlasNativeInvocations.deactivate();
        dynamicEditorCommands.deactivate();
        return Outcome.attempt(dynamic::deactivate);
    }

    /**
     * Closes every coordinator this session owns, collecting failures so the first throwing
     * coordinator cannot truncate the chain or skip the terminal commit.
     *
     * @param extraSteps session-supplied steps appended after the owned chain
     * @return the first failure of the pass, or a successful outcome
     */
    Outcome closeAll(final List<AutoCloseable> extraSteps) {
        Outcome outcome = Outcome.success();
        outcome = outcome.combine(Outcome.attempt(appearanceCoordinator::close));
        outcome = outcome.combine(Outcome.attempt(paletteAppearanceCoordinator::close));
        outcome = outcome.combine(Outcome.attempt(paletteSurfaceCoordinator::close));
        // Scene-table teardown runs exactly once, inside release().
        if (cubismLog instanceof dev.turboism.adapter.cubism.service.log.CubismLogServiceHost host) {
            outcome = outcome.combine(Outcome.attempt(host::close));
        }
        outcome = outcome.combine(Outcome.attempt(workspaceCoordinator::close));
        outcome = outcome.combine(Outcome.attempt(physicsEditorCoordinator::close));
        outcome = outcome.combine(Outcome.attempt(editorLifecycleEvents::close));
        outcome = outcome.combine(Outcome.attempt(projectFileLifecycle::close));
        outcome = outcome.combine(Outcome.attempt(editorObjectLifecycle::close));
        outcome = outcome.combine(Outcome.attempt(nativeEditIngress::close));
        outcome = outcome.combine(Outcome.attempt(partLifecycle::close));
        outcome = outcome.combine(Outcome.attempt(textureAtlasLayouts::close));
        outcome = outcome.combine(Outcome.attempt(textureAtlasNativeInvocations::close));
        outcome = outcome.combine(Outcome.attempt(textureAtlasEditorUi::close));
        outcome = outcome.combine(Outcome.attempt(hostViewPanels::close));
        outcome = outcome.combine(Outcome.attempt(previewSurfaces::close));
        outcome = outcome.combine(Outcome.attempt(parameterLifecycle::close));
        outcome = outcome.combine(Outcome.attempt(meshEditUiService::resetSession));
        outcome = outcome.combine(Outcome.attempt(meshMirrorAxisService::resetSession));
        outcome = outcome.combine(Outcome.attempt(modelingToolCoordinator::close));
        outcome = outcome.combine(Outcome.attempt(meshToolCoordinator::close));
        outcome = outcome.combine(Outcome.attempt(editorUiPluginResources::close));
        outcome = outcome.combine(Outcome.attempt(editorUiActionRouter::close));
        outcome = outcome.combine(Outcome.attempt(embeddedPanelActivation::close));
        outcome = outcome.combine(Outcome.attempt(editorUiContributions::close));
        outcome = outcome.combine(Outcome.attempt(editorUiLifecycle::close));
        for (final AutoCloseable step : extraSteps) {
            outcome = outcome.combine(Outcome.attempt(step));
        }
        return outcome;
    }

    /**
     * Runtime-privileged texture-atlas layout service for the native-entry dispatcher.
     * It shares this session's coordinator and native-invocation scope so dispatch takes
     * exactly the plugin-facing snapshot/apply path (validation, handled flag, Undo),
     * gated by a fixed runtime grant rather than any plugin's permissions.
     */
    private dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutService runtimeTextureAtlasLayouts() {
        final List<dev.turboism.sdk.permission.PluginPermission> grants = List.of(
                runtimeAtlasPermission(
                        dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutService.READ_PERMISSION),
                runtimeAtlasPermission(
                        dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutService.WRITE_PERMISSION));
        return new dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutService(
                textureAtlasLayouts,
                new dev.turboism.permissions.CubismPermissionGate(
                        RUNTIME_GRANT_OWNER, grants, ignored -> {}, java.time.Clock.systemUTC()),
                textureAtlasNativeInvocations);
    }

    private static dev.turboism.sdk.permission.PluginPermission runtimeAtlasPermission(final String id) {
        return new dev.turboism.sdk.permission.PluginPermission() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String scope() {
                return RUNTIME_GRANT_SCOPE;
            }

            @Override
            public String reason() {
                return RUNTIME_GRANT_REASON;
            }
        };
    }

    /**
     * Releases what a closed document still pins, and rebinds the native edit ingress once the
     * late document can expose its undo manager.
     */
    private void registerProjectContentCleanup() {
        projectFileLifecycle.registerCompletionListener(result -> {
            if (result.succeeded()
                    && result.request().kind() == ProjectContentKind.MODEL
                    && (result.request().operation() == ProjectFileOperationType.OPEN
                            || result.request().operation() == ProjectFileOperationType.CREATE)) {
                // Model-open completion is the existing lifecycle signal that the late document
                // may now expose its native undo manager. The session posts and coalesces the
                // actual resolver/ listener work so this synchronous host callback stays bounded.
                nativeEditIngress.retryBinding();
            }
            if (!result.succeeded() || result.request().operation() != ProjectFileOperationType.CLOSE) return;
            result.content().ifPresent(content -> {
                paletteAppearanceCoordinator.removeContent(content.contentId());
                if (content.kind() == ProjectContentKind.MODEL) {
                    dynamicModelAccess.releaseUnboundBorrowedModel();
                    // The closed document's atlas pages are the only instances the retained
                    // page outputs could still serve; keep them no longer.
                    dev.turboism.adapter.cubism.textureatlas.cache.AtlasCacheReuseDelegate.releaseRetainedOutputs();
                }
            });
        });
    }

    /**
     * First failure of one fault-tolerant cleanup pass.
     *
     * <p>A pass must attempt every stage even after one stage failed, so it cannot stop at the
     * first throw. Each later failure is attached as a suppressed exception of the first one, which
     * keeps the full cause chain observable while one value still answers whether the pass
     * succeeded.</p>
     *
     * @param failure the first failure of the pass, or null when every stage succeeded
     */
    record Outcome(Throwable failure) {

        /** @return the outcome of a pass in which every stage succeeded */
        static Outcome success() {
            return new Outcome(null);
        }

        /**
         * @param failure the failure to record; never null
         * @return the outcome of a pass with one failed stage
         */
        static Outcome failed(final Throwable failure) {
            return new Outcome(java.util.Objects.requireNonNull(failure, "failure"));
        }

        /**
         * Runs one cleanup stage and classifies its failure.
         *
         * @param step the stage to run; a fatal JVM condition propagates instead of being collected
         * @return the outcome of this stage
         */
        static Outcome attempt(final AutoCloseable step) {
            try {
                step.close();
                return success();
            } catch (Throwable throwable) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(throwable);
                return failed(throwable);
            }
        }

        /** @return true when no stage of this pass failed */
        boolean succeeded() {
            return failure == null;
        }

        /**
         * Merges another stage outcome into this one, so the first failure stays primary.
         *
         * @param other the outcome of a stage that ran after this one
         * @return the merged outcome
         */
        Outcome combine(final Outcome other) {
            if (succeeded()) {
                return other;
            }
            if (other.succeeded()) {
                return this;
            }
            if (failure != other.failure) {
                failure.addSuppressed(other.failure);
            }
            return this;
        }
    }
}
