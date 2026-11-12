package dev.turboism.adapter.host;

import dev.turboism.adapter.HostVerificationEvidence;
import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.filter.PaletteFilterContributionProvider;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.provider.EditorUiProviderInstaller;
import dev.turboism.ui.toolbar.PaletteToolbarContributionProvider;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Binds one verified host connection into the session and tears it back out.
 *
 * <p>A connection owns the adapters and every resource allocated for one exact host identity. The
 * binding keeps the published connection, the Editor UI generation it was installed under, and the
 * two cleanup debts a failed teardown can leave behind. A pending debt blocks the next publish, so
 * a replacement never runs on top of a half-closed predecessor.</p>
 *
 * <p>Attaching, installing Editor UI providers, and committing the published state are separate
 * steps on purpose: a provider install can fail after the adapters are bound, and that failure must
 * roll back through {@link #release()} instead of leaving a partially published bundle.</p>
 */
final class HostConnectionBinding {

    private static final String HOST_SESSION_DIAGNOSTIC = "host-session";
    private static final String ADAPTER_CONNECT_THREW = "Host adapter connect threw";
    private static final String EDITOR_UI_INSTALL_THREW = "Editor UI provider install threw";
    private static final String PRESENTATION_REFRESH_FAILED = "Host presentation refresh failed safely";
    private static final String NATIVE_EDIT_INGRESS_UNBINDABLE = "Native edit ingress could not bind: ";
    private static final String FAILURE_SEPARATOR = ": ";

    private final HostSessionCoordinators coordinators;
    private final Object monitor;

    private HostAdapterConnection active;
    private HostConnectionKey activeKey;
    private Set<String> admittedCapabilities = Set.of();
    private EditorUiProviderInstaller.Installation activeProviders;
    private EditorUiProviderInstaller.Installation pendingProviderCleanup;
    private HostAdapterConnection pendingConnectionCleanup;
    private dev.turboism.ui.context.NativeObjectContextMenuBridge.Handler objectContextMenuHandler;
    private dev.turboism.ui.context.NativeParameterPointContextMenuBridge.Handler parameterPointMenuHandler;
    private dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator workspaceLayoutCoordinator;

    /**
     * @param coordinators the session-owned coordinators every connection binds into
     * @param monitor the session lifecycle monitor guarding the published fields
     */
    HostConnectionBinding(final HostSessionCoordinators coordinators, final Object monitor) {
        this.coordinators = Objects.requireNonNull(coordinators, "coordinators");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    /**
     * @param key the identity a refresh produced
     * @return true when the same connection is already published and no cleanup debt is pending
     */
    boolean isCurrent(final HostConnectionKey key) {
        synchronized (monitor) {
            return active != null
                    && key.matches(activeKey)
                    && pendingConnectionCleanup == null
                    && pendingProviderCleanup == null;
        }
    }

    /**
     * Binds the candidate's adapters, surfaces, and native observation hooks.
     *
     * <p>Nothing becomes observable to callers here; the published fields change only in
     * {@link #commit}. Every step is idempotent so a rollback can run over a partial attach.</p>
     *
     * @param candidate the connected candidate
     * @param adapters the candidate's adapter bundle
     * @param sceneEvidence the project-workspace slice the Scene bridge reads through
     * @param editorUiGeneration the Editor UI generation the candidate installs under
     */
    void attach(
            final HostAdapterConnection candidate,
            final RuntimeHostAdapters adapters,
            final HostVerificationEvidence.Slice sceneEvidence,
            final long editorUiGeneration) {
        coordinators.sceneTableHost().connect(sceneEvidence.verifiedArtifact(), sceneEvidence.hostClassLoader());
        coordinators.dynamic().connect(adapters);
        coordinators.paletteSurfaceCoordinator().bindNativeIconResolver(candidate::resolveUiIcon);
        coordinators.paletteAppearanceCoordinator().bindNativeIconResolver(candidate::resolveUiIcon);
        coordinators.dynamicModelAccess().connect(candidate.modelAccess());
        coordinators.dynamicCoreRuntime().connect(candidate.coreRuntimeInfo());
        coordinators.dynamicAppearance().connect(candidate.appearanceProvider());
        coordinators.paletteAppearanceCoordinator().replaceHostGeneration(editorUiGeneration);
        bindTextureAtlasEditorSession(editorUiGeneration, candidate);
        bindNativeEditIngress(editorUiGeneration, candidate);
        candidate.textureAtlasLayoutProvider().ifPresent(coordinators.textureAtlasLayouts()::connect);
        coordinators.dynamicEditorCommands().connect(candidate.editorCommands());
        coordinators.editorUiLifecycle().connected(editorUiGeneration);
        bindParameterSurfaces(candidate);
    }

    /**
     * Installs the Editor UI providers of the candidate plus the palette providers the session owns.
     *
     * @param candidate the attached candidate
     * @param editorUiGeneration the generation the providers are admitted for
     * @return the installation, which the session must close before the connection closes
     * @throws RuntimeException when a provider rejects its admission; the caller rolls back
     */
    EditorUiProviderInstaller.Installation installEditorUiProviders(
            final HostAdapterConnection candidate, final long editorUiGeneration) {
        try {
            return EditorUiProviderInstaller.install(
                    editorUiGeneration,
                    coordinators.editorUiContributions(),
                    contributionProviders(candidate, editorUiGeneration));
        } catch (RuntimeException | Error throwable) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    HOST_SESSION_DIAGNOSTIC, EDITOR_UI_INSTALL_THREW, throwable);
            throw throwable;
        }
    }

    /**
     * Publishes an attached and installed candidate as the session's current connection.
     *
     * @param descriptor the host instance the candidate was connected for
     * @param candidate the attached candidate
     * @param key the identity of the candidate
     * @param providers the Editor UI installation the candidate owns
     * @param editorUiGeneration the generation the candidate was installed under
     */
    void commit(
            final HostInstanceDescriptor descriptor,
            final HostAdapterConnection candidate,
            final HostConnectionKey key,
            final EditorUiProviderInstaller.Installation providers,
            final long editorUiGeneration) {
        synchronized (monitor) {
            active = candidate;
            activeKey = key;
            admittedCapabilities = admittedCapabilities(descriptor.verificationEvidence());
            activeProviders = providers;
            workspaceLayoutCoordinator = candidate.workspaceLayoutCoordinator();
            objectContextMenuHandler = candidate.objectContextMenuHandler(editorUiGeneration);
            parameterPointMenuHandler = candidate.parameterPointMenuHandler(editorUiGeneration);
        }
        coordinators.editorUiLifecycle().ready(editorUiGeneration, providers.readyFamilies());
        if (candidate.workspaceProvider() != null) {
            coordinators.workspaceCoordinator().connect(candidate.workspaceProvider());
        }
    }

    /**
     * Releases the published connection and every resource its install allocated.
     *
     * <p>Registration cleanup must succeed before its owning connection can be closed, so a failed
     * registration close keeps the connection as a pending debt for the next pass.</p>
     *
     * @return the first failure of the pass, or a successful outcome
     */
    HostSessionCoordinators.Outcome release() {
        final HostSessionCoordinators.Outcome detached = coordinators.detachHostSurfaces();
        final HostAdapterConnection connection;
        final dev.turboism.ui.workspace.WorkspaceHostProvider workspaceProvider;
        synchronized (monitor) {
            activeKey = null;
            connection = active;
            workspaceProvider = connection == null ? null : connection.workspaceProvider();
            workspaceLayoutCoordinator = null;
        }
        if (workspaceProvider != null) {
            coordinators.workspaceCoordinator().disconnect(workspaceProvider);
        }

        HostSessionCoordinators.Outcome outcome = detached.combine(coordinators.detachHostBindings());
        final EditorUiProviderInstaller.Installation ownedProviders;
        final EditorUiProviderInstaller.Installation pendingProviders;
        synchronized (monitor) {
            ownedProviders = activeProviders;
            activeProviders = null;
            pendingProviders = pendingProviderCleanup;
        }
        if (ownedProviders != null) {
            try {
                ownedProviders.close();
            } catch (Throwable throwable) {
                FatalErrors.rethrowIfFatal(throwable);
                synchronized (monitor) {
                    pendingProviderCleanup = ownedProviders;
                }
                outcome = outcome.combine(HostSessionCoordinators.Outcome.failed(throwable));
            }
        }
        if (pendingProviders != null) {
            try {
                pendingProviders.close();
                synchronized (monitor) {
                    pendingProviderCleanup = null;
                }
            } catch (Throwable throwable) {
                FatalErrors.rethrowIfFatal(throwable);
                outcome = outcome.combine(HostSessionCoordinators.Outcome.failed(throwable));
            }
        }

        final boolean providersSettled;
        final HostAdapterConnection pendingConnection;
        synchronized (monitor) {
            providersSettled = pendingProviderCleanup == null;
            pendingConnection = pendingConnectionCleanup;
        }
        if (connection != null && providersSettled) {
            try {
                connection.close();
                synchronized (monitor) {
                    active = null;
                    admittedCapabilities = Set.of();
                    objectContextMenuHandler = null;
                    parameterPointMenuHandler = null;
                }
            } catch (Throwable throwable) {
                FatalErrors.rethrowIfFatal(throwable);
                outcome = outcome.combine(HostSessionCoordinators.Outcome.failed(throwable));
            }
        }
        if (pendingConnection != null && providersSettled) {
            try {
                pendingConnection.close();
                synchronized (monitor) {
                    pendingConnectionCleanup = null;
                }
            } catch (Throwable throwable) {
                FatalErrors.rethrowIfFatal(throwable);
                outcome = outcome.combine(HostSessionCoordinators.Outcome.failed(throwable));
            }
        }
        return outcome;
    }

    /**
     * Connects the descriptor through the session's connector.
     *
     * <p>Every failure is returned instead of thrown, so the caller owns the cleanup and state
     * commit ordering. A fatal JVM condition still propagates.</p>
     *
     * @param connector the session's connector
     * @param descriptor the host instance to connect
     * @return the connected candidate, or the failure that ended the attempt
     */
    ConnectAttempt connect(final HostAdapterConnector connector, final HostInstanceDescriptor descriptor) {
        HostAdapterConnection candidate = null;
        try {
            candidate = Objects.requireNonNull(connector.connect(descriptor), "connector.connect()");
            return new ConnectAttempt(
                    candidate, Objects.requireNonNull(candidate.adapters(), "connection.adapters()"), null);
        } catch (Throwable throwable) {
            FatalErrors.rethrowIfFatal(throwable);
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    HOST_SESSION_DIAGNOSTIC, ADAPTER_CONNECT_THREW, throwable);
            return new ConnectAttempt(candidate, null, throwable);
        }
    }

    /**
     * Closes a candidate that never became the published connection.
     *
     * @param candidate the candidate to close; null is a successful pass
     * @return the first failure of the pass, or a successful outcome
     */
    HostSessionCoordinators.Outcome closeCandidate(final HostAdapterConnection candidate) {
        if (candidate == null) {
            return HostSessionCoordinators.Outcome.success();
        }
        try {
            candidate.close();
            return HostSessionCoordinators.Outcome.success();
        } catch (Throwable throwable) {
            FatalErrors.rethrowIfFatal(throwable);
            synchronized (monitor) {
                pendingConnectionCleanup = candidate;
            }
            return HostSessionCoordinators.Outcome.failed(throwable);
        }
    }

    /**
     * Re-applies presentation-only state to an already published connection.
     *
     * <p>A presentation refresh is optional and must never destabilize an active host session, so
     * its failure is reported and contained.</p>
     */
    void refreshPresentation() {
        final HostAdapterConnection connection;
        synchronized (monitor) {
            connection = active;
        }
        if (connection == null) {
            return;
        }
        try {
            // The connection owns the optional resource thread boundary. The session refresh
            // remains bootstrap-owned, but direct callers must not be able to break host health.
            connection.refreshPresentation();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    HOST_SESSION_DIAGNOSTIC, PRESENTATION_REFRESH_FAILED, failure);
        }
    }

    /**
     * Reconnects a Scene bridge that failed while the main host connection stayed active.
     *
     * <p>Unsupported artifacts stay closed and already-connected bridges are left alone, so a
     * transient palette-not-ready failure heals on the next refresh without re-hashing the
     * artifact.</p>
     *
     * @param evidence the project-workspace slice the bridge reads through
     */
    void reconnectSceneBridgeIfNeeded(final HostVerificationEvidence.Slice evidence) {
        final dev.turboism.ui.table.SceneTableHostOperations sceneTableHost = coordinators.sceneTableHost();
        final dev.turboism.ui.table.SceneTableHostOperations.State sceneState = sceneTableHost.state();
        if (sceneState == dev.turboism.ui.table.SceneTableHostOperations.State.DISCONNECTED
                || sceneState == dev.turboism.ui.table.SceneTableHostOperations.State.FAILED) {
            sceneTableHost.connect(evidence.verifiedArtifact(), evidence.hostClassLoader());
        }
    }

    /**
     * @return the verified Editor model resolver of the published connection
     * @throws IllegalStateException when no connection is published
     */
    VerifiedMemberResolver editorModelResolver() {
        synchronized (monitor) {
            if (active == null) {
                throw new IllegalStateException("No verified active Editor model resolver is available.");
            }
            return active.editorModelResolver();
        }
    }

    /** @return the verified Editor model resolver, or empty when none is published or resolvable */
    Optional<VerifiedMemberResolver> optionalEditorModelResolver() {
        synchronized (monitor) {
            if (active == null) return Optional.empty();
            try {
                return Optional.of(active.editorModelResolver());
            } catch (IllegalStateException unavailable) {
                return Optional.empty();
            }
        }
    }

    /**
     * @return the texture-atlas data-model capture of the published connection
     * @throws IllegalStateException when no connection is published or it cannot supply a capture
     */
    dev.turboism.adapter.cubism.textureatlas.TextureAtlasDataModelCapture textureAtlasDataModelCapture() {
        synchronized (monitor) {
            if (active == null) {
                throw new IllegalStateException("No verified active texture-atlas capture is available.");
            }
            return active.textureAtlasDataModelCapture();
        }
    }

    /** @return the bounding-box overlay resolver, or empty when none is published or resolvable */
    Optional<VerifiedMemberResolver> boundingBoxOverlayResolver() {
        synchronized (monitor) {
            if (active == null) return Optional.empty();
            try {
                return Optional.of(active.boundingBoxOverlayResolver());
            } catch (IllegalStateException unavailable) {
                return Optional.empty();
            }
        }
    }

    /** @return the admitted capabilities minus the ones the live Editor model cannot serve */
    Set<String> admittedCubismCapabilities() {
        final Set<String> disabled = optionalEditorModelResolver()
                .map(VerifiedMemberResolver::unavailableCapabilities)
                .orElse(Set.of());
        final Set<String> admitted;
        synchronized (monitor) {
            admitted = admittedCapabilities;
        }
        if (disabled.isEmpty()) return admitted;
        final Set<String> available = new java.util.HashSet<>(admitted);
        available.removeAll(disabled);
        return Set.copyOf(available);
    }

    dev.turboism.ui.context.NativeObjectContextMenuBridge.Handler objectContextMenuHandler() {
        synchronized (monitor) {
            return objectContextMenuHandler;
        }
    }

    dev.turboism.ui.context.NativeParameterPointContextMenuBridge.Handler parameterPointMenuHandler() {
        synchronized (monitor) {
            return parameterPointMenuHandler;
        }
    }

    dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator workspaceLayoutCoordinator() {
        synchronized (monitor) {
            return workspaceLayoutCoordinator;
        }
    }

    /**
     * Binds the native edit ingress for this connection.
     *
     * <p>A resolver that does not admit the listener selectors, or a document that has no history
     * manager yet, leaves the ingress inactive. That is deliberately not a connection failure: the
     * session must still connect, and the ingress is an additive observation surface.</p>
     */
    private void bindNativeEditIngress(final long generation, final HostAdapterConnection connection) {
        try {
            coordinators.nativeEditIngress().bind(generation, connection.editorModelResolver());
        } catch (RuntimeException unavailable) {
            // A connection without a usable resolver is a transient state, not a terminal one.
            coordinators.nativeEditIngress().deactivate();
            dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                    HOST_SESSION_DIAGNOSTIC,
                    NATIVE_EDIT_INGRESS_UNBINDABLE
                            + unavailable.getClass().getName()
                            + (unavailable.getMessage() == null ? "" : FAILURE_SEPARATOR + unavailable.getMessage()));
        }
    }

    private void bindTextureAtlasEditorSession(final long generation, final HostAdapterConnection connection) {
        try {
            coordinators.textureAtlasEditorUi().bind(generation, connection.editorModelResolver());
        } catch (IllegalStateException unavailable) {
            coordinators.textureAtlasEditorUi().deactivate();
        }
    }

    private void bindParameterSurfaces(final HostAdapterConnection candidate) {
        try {
            coordinators.paletteSurfaceCoordinator().bindParameterRowsResolver(candidate.editorModelResolver());
            coordinators
                    .paletteAppearanceCoordinator()
                    .bindParameterEditorSource(new dev.turboism.ui.appearance.control.VerifiedParameterEditingSource(
                            candidate.editorModelResolver()));
            coordinators
                    .paletteAppearanceCoordinator()
                    .bindParameterRowSource(new dev.turboism.ui.appearance.control.VerifiedParameterRowSource(
                            candidate.editorModelResolver()));
        } catch (IllegalStateException unavailable) {
            coordinators.paletteSurfaceCoordinator().clearParameterRowsResolver();
            coordinators.paletteAppearanceCoordinator().clearParameterRowSource();
            coordinators.paletteAppearanceCoordinator().clearParameterEditorSource();
        }
    }

    /**
     * Adds the palette toolbar and filter providers the session itself supplies.
     *
     * <p>The palette evidence admission of the connected host authorizes both providers, so the
     * session never invents an admission of its own.</p>
     */
    private List<EditorUiContributionProvider> contributionProviders(
            final HostAdapterConnection candidate, final long editorUiGeneration) {
        final List<EditorUiContributionProvider> providers =
                new ArrayList<>(candidate.editorUiProviders(editorUiGeneration));
        final Optional<EditorUiContributionProvider> paletteEvidenceProvider = providers.stream()
                .filter(provider -> provider.family() == EditorUiFamily.PALETTE_TOOLBAR)
                .findFirst();
        if (paletteEvidenceProvider.isEmpty()) {
            return providers;
        }
        final EditorUiProviderAdmission toolbarAdmission =
                paletteEvidenceProvider.orElseThrow().admission();
        providers.removeIf(provider -> provider.family() == EditorUiFamily.PALETTE_TOOLBAR
                || provider.family() == EditorUiFamily.PALETTE_FILTER);
        providers.add(new PaletteToolbarContributionProvider(
                toolbarAdmission, coordinators.paletteSurfaceCoordinator(), coordinators.editorUiActionRouter()));
        providers.add(new PaletteFilterContributionProvider(
                EditorUiProviderAdmission.admitted(
                        EditorUiFamily.PALETTE_FILTER,
                        editorUiGeneration,
                        toolbarAdmission.verificationEvidence().orElseThrow()),
                coordinators.paletteSurfaceCoordinator()));
        return providers;
    }

    /**
     * Outcome of one connect attempt: either a candidate with its adapter bundle, or the failure
     * that ended the attempt.
     *
     * @param candidate the connected candidate, or null when the attempt failed
     * @param adapters the candidate's adapter bundle, or null when the attempt failed
     * @param failure the failure that ended the attempt, or null when it succeeded
     */
    record ConnectAttempt(HostAdapterConnection candidate, RuntimeHostAdapters adapters, Throwable failure) {

        /** @return true when the attempt produced a usable candidate */
        boolean succeeded() {
            return failure == null;
        }
    }

    /**
     * The capability-id union admitted for a connection: every evidence slice's contract set,
     * falling back to the record's declared capabilities for contractless (legacy exact) slices.
     * Emptiness fails API availability closed.
     */
    private static Set<String> admittedCapabilities(final HostVerificationEvidence evidence) {
        final Set<String> capabilities = new LinkedHashSet<>();
        Stream.of(
                        Optional.of(evidence.projectWorkspace()),
                        evidence.clipMask(),
                        evidence.editorModel(),
                        evidence.coreRuntime(),
                        evidence.mainToolbar(),
                        evidence.embeddedPanel(),
                        evidence.topMenu(),
                        evidence.boundingBoxOverlayButton(),
                        evidence.workspaceControl(),
                        evidence.statusBar(),
                        evidence.autoBackup())
                .forEach(slice -> slice.ifPresent(s -> capabilities.addAll(s.capabilities())));
        return Set.copyOf(capabilities);
    }
}
