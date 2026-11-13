package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.lifecycle.EditorLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ProjectFileLifecycleCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.ui.action.RuntimeEditorUiActionRouter;
import dev.turboism.ui.appearance.AppearanceCoordinator;
import dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.EditorUiHostFailure;
import dev.turboism.ui.host.EditorUiHostLifecycle;
import dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator;
import dev.turboism.ui.provider.EditorUiProviderInstaller;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;
import java.util.Objects;
import java.util.Optional;

/**
 * Fail-closed host-session lifecycle with a stable dynamic adapter view.
 *
 * <p>A connection is reusable only while the session ID and every verification-evidence slice
 * retain the same normalized reviewed-record path, normalized verified-artifact path, and defining
 * host classloader identity. Adding or removing an optional slice is also a material change. A
 * material change deactivates and closes the complete old adapter bundle before connecting the
 * replacement; replacement failure leaves the dynamic view in safe mode rather than publishing a
 * partially refreshed bundle.</p>
 *
 * <p>This class is the session coordinator only. {@link HostSessionCoordinators} owns every
 * coordinator that outlives a connection, {@link HostConnectionBinding} owns the published
 * connection and its cleanup debts, {@link HostConnectionKey} answers whether a descriptor still
 * describes the published connection, and {@link HostSessionLifecycleGate} owns admission and the
 * committed lifecycle state.</p>
 */
public final class HostSession implements RuntimeHostAdapterAccess, AutoCloseable {

    private static final String CLEANUP_FAILURE_MESSAGE = "Host session cleanup failed safely.";
    private static final String CONNECTION_FAILURE_MESSAGE = "Host adapter connection failed safely.";
    private static final String SOURCE_FAILURE_MESSAGE = "Host instance source failed safely.";
    private static final String CONNECTION_KEY_FAILURE_DIAGNOSTIC = "Host connection key derivation failed";
    private static final String HOST_SESSION_DIAGNOSTIC = "host-session";
    private static final String EDITOR_UI_FAILURE_MESSAGE = "Editor UI host connection failed safely.";

    private final HostInstanceSource source;
    private final HostAdapterConnector connector;
    private final Object lifecycleMonitor = new Object();
    private final HostSessionLifecycleGate gate = new HostSessionLifecycleGate();
    private final HostSessionCoordinators coordinators;
    private final HostConnectionBinding binding;
    private final dev.turboism.sdk.cubism.history.CubismHistory history;

    public HostSession(final HostInstanceSource source) {
        this(source, dev.turboism.i18n.CubismHostLocale::resolve);
    }

    public HostSession(final HostInstanceSource source, final java.util.Locale effectiveLocale) {
        this(source, fixedLocale(effectiveLocale));
    }

    /**
     * Fixed-locale production session with a persistent store for the runtime-owned
     * texture-atlas automatic-layout selection; a null persistence keeps selection in memory.
     */
    public HostSession(
            final HostInstanceSource source,
            final java.util.Locale effectiveLocale,
            final dev.turboism.adapter.cubism.textureatlas.TextureAtlasAutoLayoutSelection.Persistence
                    textureAtlasSelectionPersistence) {
        this(source, fixedLocale(effectiveLocale), textureAtlasSelectionPersistence);
    }

    /**
     * Production composition: receives the runtime's shared effective-locale source so
     * host-attached UI text resolves the locale that is effective at render time — the
     * host-verified re-resolution supersedes the provisional startup value in place.
     */
    public HostSession(
            final HostInstanceSource source, final java.util.function.Supplier<java.util.Locale> effectiveLocale) {
        this(source, effectiveLocale, null);
    }

    /**
     * Production session with a persistent store for the runtime-owned texture-atlas
     * automatic-layout selection; a null persistence keeps selection in memory.
     */
    public HostSession(
            final HostInstanceSource source,
            final java.util.function.Supplier<java.util.Locale> effectiveLocale,
            final dev.turboism.adapter.cubism.textureatlas.TextureAtlasAutoLayoutSelection.Persistence
                    textureAtlasSelectionPersistence) {
        this(source, createVerifiedConnectorFactory(effectiveLocale), textureAtlasSelectionPersistence);
    }

    HostSession(final HostInstanceSource source, final HostAdapterConnector connector) {
        this(source, ignored -> Objects.requireNonNull(connector, "connector"), null);
    }

    private HostSession(
            final HostInstanceSource source,
            final java.util.function.Function<HostSessionCoordinators, HostAdapterConnector> connectorFactory,
            final dev.turboism.adapter.cubism.textureatlas.TextureAtlasAutoLayoutSelection.Persistence
                    textureAtlasSelectionPersistence) {
        this.source = Objects.requireNonNull(source, "source");
        this.coordinators = new HostSessionCoordinators(textureAtlasSelectionPersistence);
        this.connector = Objects.requireNonNull(
                Objects.requireNonNull(connectorFactory, "connectorFactory").apply(this.coordinators), "connector");
        this.binding = new HostConnectionBinding(coordinators, lifecycleMonitor);
        this.history = new dev.turboism.adapter.cubism.editor.history.EditorHistorySnapshotProvider(
                binding::optionalEditorModelResolver,
                () -> coordinators.editorUiLifecycle().snapshot().generation());
        coordinators.dynamic().onOutermostAdapterCallComplete(this::completeDeferredClose);
    }

    private static java.util.function.Function<HostSessionCoordinators, HostAdapterConnector>
            createVerifiedConnectorFactory(final java.util.function.Supplier<java.util.Locale> effectiveLocale) {
        final java.util.function.Supplier<java.util.Locale> checkedLocale =
                Objects.requireNonNull(effectiveLocale, "effectiveLocale");
        return sessionCoordinators -> new VerifiedHostAdapterConnector(
                new dev.turboism.adapter.VerifiedRuntimeHostAdaptersFactory(
                        checkedLocale, dev.turboism.kernel.RecentPreviewDiagnostics::emit)::create,
                slice -> VerifiedHostAdapterConnector.resolverForSlice(
                        slice, dev.turboism.mapping.verification.SliceRegistry.EDITOR_MODEL.resolverFactory()),
                VerifiedHostAdapterConnector::productionEditorAccess,
                slice -> VerifiedHostAdapterConnector.resolverForSlice(
                        slice, dev.turboism.mapping.verification.SliceRegistry.MAIN_TOOLBAR.resolverFactory()),
                slice -> VerifiedHostAdapterConnector.resolverForSlice(
                        slice, dev.turboism.mapping.verification.SliceRegistry.EMBEDDED_PANEL.resolverFactory()),
                slice -> VerifiedHostAdapterConnector.resolverForSlice(
                        slice,
                        dev.turboism.mapping.verification.SliceRegistry.BOUNDING_BOX_OVERLAY_BUTTON.resolverFactory()),
                sessionCoordinators.editorUiPluginResources(),
                sessionCoordinators.editorUiActionRouter(),
                sessionCoordinators.embeddedPanelActivation(),
                slice -> VerifiedHostAdapterConnector.resolverForSlice(
                        slice, dev.turboism.mapping.verification.SliceRegistry.TOP_MENU.resolverFactory()),
                sessionCoordinators.dockMaintenance(),
                VerifiedHostAdapterConnector.productionAppearanceProviderFactory(),
                slice -> VerifiedHostAdapterConnector.resolverForSlice(
                        slice, dev.turboism.mapping.verification.SliceRegistry.WORKSPACE_CONTROL.resolverFactory()),
                VerifiedHostAdapterConnector.productionCoreBackendFactory(),
                checkedLocale,
                sessionCoordinators.meshToolCoordinator(),
                sessionCoordinators.modelingToolCoordinator());
    }

    private static java.util.function.Supplier<java.util.Locale> fixedLocale(final java.util.Locale effectiveLocale) {
        final java.util.Locale required = Objects.requireNonNull(effectiveLocale, "effectiveLocale");
        return () -> required;
    }

    /**
     * Refreshes the session from a bootstrap-owned thread. External source, connector, adapter,
     * registration, and connection callbacks always run outside the session lifecycle monitor.
     */
    public State refresh() {
        if (!beginTransition(false)) {
            return state();
        }
        try {
            final Optional<HostInstanceDescriptor> available;
            try {
                available = Objects.requireNonNull(source.current(), "source.current()");
            } catch (RuntimeException exception) {
                return closeRequested()
                        ? finishRequestedClose(null)
                        : failAfterCleanup(HostSessionFailure.Code.SOURCE_FAILED, SOURCE_FAILURE_MESSAGE);
            }

            if (closeRequested()) {
                return finishRequestedClose(null);
            }
            if (available.isEmpty()) {
                return enterSafeMode();
            }

            final HostInstanceDescriptor descriptor = available.orElseThrow();
            connectHostScopedSurfaces(descriptor);
            final HostConnectionKey connectionKey;
            try {
                connectionKey = HostConnectionKey.from(descriptor);
            } catch (RuntimeException exception) {
                dev.turboism.runtime.log.RuntimeDiagnostics.error(
                        HOST_SESSION_DIAGNOSTIC, CONNECTION_KEY_FAILURE_DIAGNOSTIC, exception);
                return failAfterCleanup(HostSessionFailure.Code.CONNECTION_FAILED, CONNECTION_FAILURE_MESSAGE);
            }

            if (closeRequested()) {
                return finishRequestedClose(null);
            }
            if (binding.isCurrent(connectionKey)) {
                binding.reconnectSceneBridgeIfNeeded(
                        descriptor.verificationEvidence().projectWorkspace());
                binding.refreshPresentation();
                return state();
            }
            return replaceConnection(descriptor, connectionKey);
        } finally {
            gate.endTransition();
        }
    }

    /**
     * @return the session's current lifecycle state, read under the lifecycle monitor so it is
     *     never observed mid-transition
     */
    public State state() {
        return gate.state();
    }

    /**
     * @return the failure that pushed this session into safe mode, failed it, or was recorded
     *     during cleanup; empty when no failure has been recorded yet
     */
    public Optional<HostSessionFailure> lastFailure() {
        return gate.lastFailure();
    }

    @Override
    public RuntimeHostAdapters adapters() {
        return coordinators.dynamic().view();
    }

    @Override
    public java.util.Optional<String> cubismEditorVersion() {
        return binding.optionalEditorModelResolver()
                .map(dev.turboism.mapping.verification.VerifiedMemberResolver::cubismVersion);
    }

    @Override
    public java.util.Set<String> admittedCubismCapabilities() {
        return binding.admittedCubismCapabilities();
    }

    @Override
    public java.util.Optional<String> admittedCubismGeneration() {
        return binding.optionalEditorModelResolver()
                .map(dev.turboism.mapping.verification.VerifiedMemberResolver::admittedCubismVersion);
    }

    @Override
    public dev.turboism.sdk.cubism.model.CubismModelAccess modelAccess() {
        return coordinators.dynamicModelAccess();
    }

    @Override
    public dev.turboism.adapter.cubism.HostSnapshotSource.HostSelection currentHostSelection() {
        return coordinators.dynamicModelAccess().currentHostSelection();
    }

    @Override
    public dev.turboism.sdk.cubism.history.CubismHistory history() {
        return history;
    }

    /**
     * @return the narrow snapshot source used only by model-appearance projections; it is created
     *     once with the session and stays valid across host connect and disconnect
     */
    public HostSnapshotSource modelAppearanceSource() {
        return coordinators.modelAppearanceSource();
    }

    @Override
    public dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntimeInfo() {
        return coordinators.dynamicCoreRuntime();
    }

    @Override
    public dev.turboism.adapter.cubism.command.EditorCommandAdapter editorCommands() {
        return coordinators.dynamicEditorCommands();
    }

    @Override
    public ParameterLifecycleCoordinator parameterLifecycle() {
        return coordinators.parameterLifecycle();
    }

    @Override
    public PartLifecycleCoordinator partLifecycle() {
        return coordinators.partLifecycle();
    }

    @Override
    public TextureAtlasLayoutCoordinator textureAtlasLayouts() {
        return coordinators.textureAtlasLayouts();
    }

    @Override
    public dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator
            textureAtlasNativeInvocations() {
        return coordinators.textureAtlasNativeInvocations();
    }

    @Override
    public EditorObjectLifecycleCoordinator editorObjectLifecycle() {
        return coordinators.editorObjectLifecycle();
    }

    @Override
    public ProjectFileLifecycleCoordinator projectFileLifecycle() {
        return coordinators.projectFileLifecycle();
    }

    @Override
    public EditorLifecycleCoordinator editorLifecycleEvents() {
        return coordinators.editorLifecycleEvents();
    }

    @Override
    public PhysicsEditorCoordinator physicsEditorCoordinator() {
        return coordinators.physicsEditorCoordinator();
    }

    @Override
    public dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService meshMirrorAxisService() {
        return coordinators.meshMirrorAxisService();
    }

    @Override
    public dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService meshEditUiService() {
        return coordinators.meshEditUiService();
    }

    @Override
    public EditorUiHostLifecycle editorUiLifecycle() {
        return coordinators.editorUiLifecycle();
    }

    @Override
    public EditorUiContributionAuthority editorUiContributions() {
        return coordinators.editorUiContributions();
    }

    /** Returns the host-owned ordinary modeling tool coordinator. */
    @Override
    public dev.turboism.adapter.cubism.modeling.ModelingToolCoordinator modelingToolCoordinator() {
        return coordinators.modelingToolCoordinator();
    }

    /** Returns this host session's owner of temporary mesh-tool activations. */
    public dev.turboism.adapter.cubism.mesh.MeshToolCoordinator meshToolCoordinator() {
        return coordinators.meshToolCoordinator();
    }

    @Override
    public RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation() {
        return coordinators.embeddedPanelActivation();
    }

    @Override
    public RuntimeEditorUiActionRouter editorUiActionRouter() {
        return coordinators.editorUiActionRouter();
    }

    @Override
    public EditorUiPluginResourceRegistry editorUiPluginResources() {
        return coordinators.editorUiPluginResources();
    }

    @Override
    public dev.turboism.ui.context.NativeObjectContextMenuBridge.Handler objectContextMenuHandler() {
        return binding.objectContextMenuHandler();
    }

    /**
     * @return the handler installed for the native parameter-point context menu by the currently
     *     connected Editor UI, or null while no connection has supplied one (it is cleared again
     *     when the connection is torn down)
     */
    public dev.turboism.ui.context.NativeParameterPointContextMenuBridge.Handler parameterPointMenuHandler() {
        return binding.parameterPointMenuHandler();
    }

    @Override
    public dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance() {
        return coordinators.dockMaintenance();
    }

    @Override
    public AppearanceCoordinator appearanceCoordinator() {
        return coordinators.appearanceCoordinator();
    }

    @Override
    public dev.turboism.sdk.ui.table.SceneTableService sceneTable() {
        return coordinators.sceneTable();
    }

    @Override
    public dev.turboism.ui.filter.PaletteFilterVisibilitySink paletteFilterSink() {
        return coordinators.paletteSurfaceCoordinator();
    }

    @Override
    public dev.turboism.sdk.runtime.CubismLogService cubismLog() {
        return coordinators.cubismLog();
    }

    @Override
    public PaletteAppearanceCoordinator paletteAppearanceCoordinator() {
        return coordinators.paletteAppearanceCoordinator();
    }

    @Override
    public dev.turboism.ui.workspace.WorkspaceCoordinator workspaceCoordinator() {
        return coordinators.workspaceCoordinator();
    }

    @Override
    public dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator workspaceLayoutCoordinator() {
        return binding.workspaceLayoutCoordinator();
    }

    /**
     * @return the registry of texture-atlas layout algorithms owned by this session; it exists
     *     independently of any host connection, so plugin-contributed algorithms survive safe mode
     */
    public dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry
            textureAtlasAlgorithms() {
        return coordinators.textureAtlasAlgorithms();
    }

    /** @return the stable texture-atlas editor UI owned by this host session. */
    public dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi textureAtlasEditorUi() {
        return coordinators.textureAtlasEditorUi();
    }

    /** @return the host-session-owned host view panel registry every UI anchor renders through. */
    public dev.turboism.ui.panel.RuntimeHostViewPanelRegistry hostViewPanels() {
        return coordinators.hostViewPanels();
    }

    /** @return the host-session-owned preview surface registry every preview capability reads through. */
    public dev.turboism.adapter.cubism.preview.RuntimePreviewSurfaceRegistry previewSurfaces() {
        return coordinators.previewSurfaces();
    }

    /** @return the stable texture-atlas editor session resolved against the active connection. */
    public dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession textureAtlasEditorSession() {
        return coordinators.textureAtlasEditorSession();
    }

    /**
     * @return the runtime-owned native automatic-layout dispatch callback; the verified
     *     host hook wraps it in the native-invocation scope, and a {@code false} result
     *     defers to the host's own packing
     */
    public java.util.function.BooleanSupplier textureAtlasAutoLayoutDispatch() {
        return coordinators.textureAtlasAutoLayoutDispatch();
    }

    /** @return the runtime-owned texture-atlas automatic-layout selection state. */
    public dev.turboism.adapter.cubism.textureatlas.TextureAtlasAutoLayoutSelection textureAtlasSelection() {
        return coordinators.textureAtlasSelection();
    }

    /**
     * @return the verified member resolver for the Editor model of the active connection
     * @throws IllegalStateException if no host connection is active, so no verified resolver exists
     */
    public dev.turboism.mapping.verification.VerifiedMemberResolver editorModelResolver() {
        return binding.editorModelResolver();
    }

    /**
     * @return the texture-atlas data-model capture of the active connection
     * @throws IllegalStateException when no verified connection is active or the active
     *     connection cannot supply a texture-atlas capture
     */
    public dev.turboism.adapter.cubism.textureatlas.TextureAtlasDataModelCapture textureAtlasDataModelCapture() {
        return binding.textureAtlasDataModelCapture();
    }

    /**
     * @return the verified member resolver backing bounding-box overlays, or empty when no
     *     connection is active or the active connection cannot supply one; unlike
     *     {@link #editorModelResolver()} this never throws
     */
    public java.util.Optional<dev.turboism.mapping.verification.VerifiedMemberResolver> boundingBoxOverlayResolver() {
        return binding.boundingBoxOverlayResolver();
    }

    /** Returns a non-closeable trusted composition view while lifecycle ownership stays elsewhere. */
    public RuntimeHostAdapterAccess adapterAccess() {
        return new SessionRuntimeHostAdapterAccess(
                coordinators.dynamic().view(),
                this::cubismEditorVersion,
                this::admittedCubismCapabilities,
                this::admittedCubismGeneration,
                coordinators.dynamicModelAccess(),
                history,
                modelAppearanceSource(),
                coordinators.dynamicCoreRuntime(),
                coordinators.dynamicEditorCommands(),
                parameterLifecycle(),
                partLifecycle(),
                textureAtlasLayouts(),
                textureAtlasNativeInvocations(),
                editorObjectLifecycle(),
                projectFileLifecycle(),
                editorLifecycleEvents(),
                physicsEditorCoordinator(),
                meshMirrorAxisService(),
                meshEditUiService(),
                meshToolCoordinator(),
                modelingToolCoordinator(),
                editorUiLifecycle(),
                editorUiContributions(),
                embeddedPanelActivation(),
                editorUiActionRouter(),
                editorUiPluginResources(),
                objectContextMenuHandler(),
                parameterPointMenuHandler(),
                dockMaintenance(),
                boundingBoxOverlayResolver(),
                appearanceCoordinator(),
                sceneTable(),
                cubismLog(),
                paletteFilterSink(),
                paletteAppearanceCoordinator(),
                workspaceCoordinator(),
                workspaceLayoutCoordinator(),
                textureAtlasEditorUi(),
                hostViewPanels(),
                previewSurfaces(),
                textureAtlasEditorSession(),
                textureAtlasAlgorithms(),
                textureAtlasAutoLayoutDispatch());
    }

    /**
     * Completes all owned cleanup before committing CLOSED. A sanitized unchecked exception tells
     * the owner that cleanup remains pending and a subsequent close must retry.
     */
    @Override
    public void close() {
        if (!beginTransition(true)) {
            return;
        }
        try {
            final HostSessionCoordinators.Outcome cleanup = cleanupOwnedResources();
            if (!cleanup.succeeded()) {
                finishCleanupFailure(cleanup, true);
                return;
            }
            final HostSessionCoordinators.Outcome coordinatorCleanup =
                    coordinators.closeAll(coordinatorCleanupStepsForTest);
            if (!coordinatorCleanup.succeeded()) {
                finishCleanupFailure(coordinatorCleanup, true);
                return;
            }
            commit(State.CLOSED, Optional.empty());
        } finally {
            gate.endTransition();
        }
    }

    /** Test-only coordinator steps appended to the close chain; empty in production. */
    volatile java.util.List<AutoCloseable> coordinatorCleanupStepsForTest = java.util.List.of();

    /**
     * Binds the host-scoped surfaces that read the host classloader directly rather than through a
     * connection, so they are ready before any connection is attempted.
     */
    private void connectHostScopedSurfaces(final HostInstanceDescriptor descriptor) {
        final ClassLoader hostClassLoader =
                descriptor.verificationEvidence().projectWorkspace().hostClassLoader();
        if (coordinators.cubismLog() instanceof dev.turboism.adapter.cubism.service.log.CubismLogServiceHost host) {
            host.connect(hostClassLoader);
        }
        final dev.turboism.ui.palette.PaletteSurfaceCoordinator paletteSurface =
                coordinators.paletteSurfaceCoordinator();
        paletteSurface.bindSceneFilterSink(coordinators.sceneTableHost());
        paletteSurface.bindCubismLogService(coordinators.cubismLog());
        paletteSurface.bindParameterRows(coordinators.paletteAppearanceCoordinator());
        paletteSurface.connect(hostClassLoader);
    }

    /**
     * Replaces the published connection with the one the descriptor describes.
     *
     * <p>The old bundle closes completely before the replacement connects, so a failed replacement
     * leaves the session in safe mode instead of publishing a partially refreshed bundle.</p>
     */
    private State replaceConnection(final HostInstanceDescriptor descriptor, final HostConnectionKey connectionKey) {
        coordinators.editorUiLifecycle().replacing();
        final HostSessionCoordinators.Outcome replacementCleanup = cleanupOwnedResources();
        if (!replacementCleanup.succeeded()) {
            return finishCleanupFailure(replacementCleanup, false);
        }
        if (closeRequested()) {
            return finishRequestedClose(null);
        }

        final long editorUiGeneration =
                coordinators.editorUiLifecycle().connecting().generation();
        final HostConnectionBinding.ConnectAttempt attempt = binding.connect(connector, descriptor);
        if (!attempt.succeeded()) {
            final HostSessionCoordinators.Outcome candidateCleanup = binding.closeCandidate(attempt.candidate());
            if (!candidateCleanup.succeeded()) {
                return finishCleanupFailure(candidateCleanup, false);
            }
            if (attempt.failure() instanceof Error error) {
                commitFailure(HostSessionFailure.Code.CONNECTION_FAILED, CONNECTION_FAILURE_MESSAGE);
                throw error;
            }
            return closeRequested()
                    ? finishRequestedClose(null)
                    : commitFailure(HostSessionFailure.Code.CONNECTION_FAILED, CONNECTION_FAILURE_MESSAGE);
        }

        // Lifecycle cleanup and state commits intentionally remain outside the connection
        // failure boundary so their failures cannot be reclassified or retried as connect errors.
        if (closeRequested()) {
            return finishRequestedClose(attempt.candidate());
        }
        binding.attach(
                attempt.candidate(),
                attempt.adapters(),
                descriptor.verificationEvidence().projectWorkspace(),
                editorUiGeneration);
        final EditorUiProviderInstaller.Installation candidateProviders;
        try {
            candidateProviders = binding.installEditorUiProviders(attempt.candidate(), editorUiGeneration);
        } catch (Throwable throwable) {
            FatalErrors.rethrowIfFatal(throwable);
            final HostSessionCoordinators.Outcome candidateCleanup = cleanupOwnedResources();
            if (!candidateCleanup.succeeded()) {
                return finishCleanupFailure(candidateCleanup, false);
            }
            if (throwable instanceof Error error) {
                commitFailure(HostSessionFailure.Code.CONNECTION_FAILED, CONNECTION_FAILURE_MESSAGE);
                throw error;
            }
            return commitFailure(HostSessionFailure.Code.CONNECTION_FAILED, CONNECTION_FAILURE_MESSAGE);
        }
        binding.commit(descriptor, attempt.candidate(), connectionKey, candidateProviders, editorUiGeneration);
        if (closeRequested()) {
            return finishRequestedClose(null);
        }
        return commit(State.ACTIVE, Optional.empty());
    }

    private State enterSafeMode() {
        coordinators.editorUiLifecycle().replacing();
        final HostSessionCoordinators.Outcome cleanup = cleanupOwnedResources();
        if (!cleanup.succeeded()) {
            return finishCleanupFailure(cleanup, false);
        }
        if (closeRequested()) {
            return finishRequestedClose(null);
        }
        coordinators.editorUiLifecycle().absent();
        return commit(State.SAFE_MODE, Optional.empty());
    }

    private State failAfterCleanup(final HostSessionFailure.Code failureCode, final String failureMessage) {
        final HostSessionCoordinators.Outcome cleanup = cleanupOwnedResources();
        if (!cleanup.succeeded()) {
            return finishCleanupFailure(cleanup, false);
        }
        return commitFailure(failureCode, failureMessage);
    }

    private State finishRequestedClose(final HostAdapterConnection candidate) {
        final HostSessionCoordinators.Outcome candidateCleanup = binding.closeCandidate(candidate);
        if (!candidateCleanup.succeeded()) {
            return finishCleanupFailure(candidateCleanup, false);
        }
        final HostSessionCoordinators.Outcome ownedCleanup = cleanupOwnedResources();
        return ownedCleanup.succeeded()
                ? commit(State.CLOSED, Optional.empty())
                : finishCleanupFailure(ownedCleanup, false);
    }

    /** Registration cleanup must succeed before its owning connection can be closed. */
    private HostSessionCoordinators.Outcome cleanupOwnedResources() {
        return binding.release();
    }

    private State finishCleanupFailure(
            final HostSessionCoordinators.Outcome cleanup, final boolean throwSanitizedNonError) {
        final State failed = commitFailure(HostSessionFailure.Code.CLEANUP_FAILED, CLEANUP_FAILURE_MESSAGE);
        final Throwable failure = cleanup.failure();
        if (failure instanceof Error error) {
            throw error;
        }
        if (throwSanitizedNonError) {
            final HostSessionLifecycleException lifecycleFailure =
                    new HostSessionLifecycleException(CLEANUP_FAILURE_MESSAGE);
            if (failure != null) {
                lifecycleFailure.addSuppressed(failure);
            }
            throw lifecycleFailure;
        }
        return failed;
    }

    private State commitFailure(final HostSessionFailure.Code code, final String message) {
        try {
            coordinators
                    .editorUiLifecycle()
                    .failed(EditorUiHostFailure.host(
                            code == HostSessionFailure.Code.CLEANUP_FAILED
                                    ? EditorUiHostFailure.Code.CLEANUP_FAILED
                                    : EditorUiHostFailure.Code.REPLACEMENT_FAILED,
                            EDITOR_UI_FAILURE_MESSAGE));
        } catch (IllegalStateException ignored) {
        }
        return commit(State.FAILED, Optional.of(new HostSessionFailure(code, message)));
    }

    private State commit(final State newState, final Optional<HostSessionFailure> failure) {
        return gate.commit(newState, failure);
    }

    private boolean closeRequested() {
        return gate.closeRequested();
    }

    boolean closeRequestedForTest() {
        return closeRequested();
    }

    boolean acceptingCallsForTest() {
        return coordinators.dynamic().acceptingCallsForTest();
    }

    private boolean beginTransition(final boolean closing) {
        return gate.beginTransition(closing, coordinators.dynamic().isInAdapterCallOnCurrentThread());
    }

    private void completeDeferredClose() {
        if (!gate.closeStillPending()) {
            return;
        }
        close();
    }

    /** Lifecycle state of a host session. */
    public enum State {
        /** No host is attached; the session answers through safe-mode surfaces. */
        SAFE_MODE,
        /** A verified host connection is active. */
        ACTIVE,
        /** The session left its healthy path; the last failure is retained for diagnostics. */
        FAILED,
        /** The session is closed and must not serve further calls. */
        CLOSED
    }
}
