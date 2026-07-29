package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.ui.action.RuntimeEditorUiActionRouter;
import dev.turboism.ui.appearance.AppearanceCoordinator;
import dev.turboism.ui.appearance.UnavailableAppearanceHostProvider;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.EditorUiHostFailure;
import dev.turboism.ui.host.EditorUiHostLifecycle;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;
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
 */
public final class HostSession implements RuntimeHostAdapterAccess, AutoCloseable {

    private static final String CLEANUP_FAILURE_MESSAGE = "Host session cleanup failed safely.";
    private final HostInstanceSource source;
    private final HostAdapterConnector connector;
    private final DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
    private final DynamicCubismModelAccess dynamicModelAccess = new DynamicCubismModelAccess();
    private final ParameterLifecycleCoordinator parameterLifecycle =
        new ParameterLifecycleCoordinator();
    private final PartLifecycleCoordinator partLifecycle =
        new PartLifecycleCoordinator();
    private final EditorObjectLifecycleCoordinator editorObjectLifecycle =
        new EditorObjectLifecycleCoordinator();
    private final RuntimeEditorUiHostLifecycle editorUiLifecycle =
        new RuntimeEditorUiHostLifecycle();
    private final EditorUiContributionAuthority editorUiContributions =
        new EditorUiContributionAuthority(editorUiLifecycle);
    private final RuntimeEditorUiActionRouter editorUiActionRouter =
        new RuntimeEditorUiActionRouter();
    private final EditorUiPluginResourceRegistry editorUiPluginResources =
        new EditorUiPluginResourceRegistry();
    private final AppearanceCoordinator appearanceCoordinator =
        new AppearanceCoordinator(new UnavailableAppearanceHostProvider(), new EventBus() {
            @Override
            public <T extends TurboismEvent> dev.turboism.sdk.plugin.Registration subscribe(
                final Class<T> type,
                final java.util.function.Consumer<T> listener
            ) {
                return () -> { };
            }

            @Override
            public <T extends TurboismEvent> void publish(final T event) {
            }
        });
    private final Object lifecycleMonitor = new Object();

    private State state = State.SAFE_MODE;
    private ConnectionKey activeConnectionKey;
    private HostAdapterConnection activeConnection;
    private HostAdapterConnection pendingConnectionCleanup;
    private EditorUiProviderInstaller.Installation activeEditorUiProviders;
    private EditorUiProviderInstaller.Installation pendingEditorUiProviderCleanup;
    private Optional<HostSessionFailure> lastFailure = Optional.empty();
    private boolean transitionInProgress;
    private Thread transitionOwner;
    private boolean closeRequested;

    public HostSession(final HostInstanceSource source) {
        this.source = Objects.requireNonNull(source, "source");
        this.connector = new VerifiedHostAdapterConnector(
            new dev.turboism.adapter.VerifiedRuntimeHostAdaptersFactory()::create,
            slice -> new dev.turboism.mapping.verification.VerifiedEditorModelResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            dev.turboism.adapter.cubism.editor.EditorBackedCubismModelAccess::new,
            slice -> new dev.turboism.mapping.verification.VerifiedMainToolbarResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            editorUiPluginResources,
            editorUiActionRouter
        );
        dynamic.onOutermostAdapterCallComplete(this::completeDeferredClose);
    }

    HostSession(
        final HostInstanceSource source,
        final HostAdapterConnector connector
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.connector = Objects.requireNonNull(connector, "connector");
        dynamic.onOutermostAdapterCallComplete(this::completeDeferredClose);
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
                    : failAfterCleanup(
                        HostSessionFailure.Code.SOURCE_FAILED,
                        "Host instance source failed safely."
                    );
            }

            if (closeRequested()) {
                return finishRequestedClose(null);
            }
            if (available.isEmpty()) {
                return enterSafeMode();
            }

            final HostInstanceDescriptor descriptor = available.orElseThrow();
            final ConnectionKey connectionKey;
            try {
                connectionKey = ConnectionKey.from(descriptor);
            } catch (RuntimeException exception) {
                return failAfterCleanup(
                    HostSessionFailure.Code.CONNECTION_FAILED,
                    "Host adapter connection failed safely."
                );
            }

            if (closeRequested()) {
                return finishRequestedClose(null);
            }
            if (isCurrentConnection(connectionKey)) {
                return state();
            }
            editorUiLifecycle.replacing();
            final CleanupOutcome replacementCleanup = cleanupOwnedResources();
            if (!replacementCleanup.succeeded()) {
                return finishCleanupFailure(replacementCleanup, false);
            }
            if (closeRequested()) {
                return finishRequestedClose(null);
            }

            final long editorUiGeneration = editorUiLifecycle.connecting().generation();
            HostAdapterConnection candidate = null;
            RuntimeHostAdapters candidateAdapters;
            try {
                candidate = Objects.requireNonNull(connector.connect(descriptor), "connector.connect()");
                candidateAdapters = Objects.requireNonNull(
                    candidate.adapters(),
                    "connection.adapters()"
                );
            } catch (Throwable throwable) {
                final CleanupOutcome candidateCleanup = closeCandidate(candidate);
                if (!candidateCleanup.succeeded()) {
                    return finishCleanupFailure(candidateCleanup, false);
                }
                if (throwable instanceof Error error) {
                    commitFailure(
                        HostSessionFailure.Code.CONNECTION_FAILED,
                        "Host adapter connection failed safely."
                    );
                    throw error;
                }
                return closeRequested()
                    ? finishRequestedClose(null)
                    : commitFailure(
                        HostSessionFailure.Code.CONNECTION_FAILED,
                        "Host adapter connection failed safely."
                    );
            }

            // Lifecycle cleanup and state commits intentionally remain outside the connection
            // failure boundary so their failures cannot be reclassified or retried as connect errors.
            if (closeRequested()) {
                return finishRequestedClose(candidate);
            }
            dynamic.connect(candidateAdapters);
            dynamicModelAccess.connect(candidate.modelAccess());
            editorUiLifecycle.connected(editorUiGeneration);
            activeConnection = candidate;
            final EditorUiProviderInstaller.Installation candidateEditorUiProviders;
            try {
                candidateEditorUiProviders = EditorUiProviderInstaller.install(
                    editorUiGeneration,
                    editorUiContributions,
                    candidate.editorUiProviders(editorUiGeneration)
                );
            } catch (Throwable throwable) {
                final CleanupOutcome candidateCleanup = cleanupOwnedResources();
                if (!candidateCleanup.succeeded()) {
                    return finishCleanupFailure(candidateCleanup, false);
                }
                if (throwable instanceof Error error) {
                    commitFailure(
                        HostSessionFailure.Code.CONNECTION_FAILED,
                        "Host adapter connection failed safely."
                    );
                    throw error;
                }
                return commitFailure(
                    HostSessionFailure.Code.CONNECTION_FAILED,
                    "Host adapter connection failed safely."
                );
            }
            activeConnectionKey = connectionKey;
            activeEditorUiProviders = candidateEditorUiProviders;
            editorUiLifecycle.ready(
                editorUiGeneration,
                candidateEditorUiProviders.readyFamilies()
            );
            if (closeRequested()) {
                return finishRequestedClose(null);
            }
            return commit(State.ACTIVE, Optional.empty());
        } finally {
            endTransition();
        }
    }

    public State state() {
        synchronized (lifecycleMonitor) {
            return state;
        }
    }

    public Optional<HostSessionFailure> lastFailure() {
        synchronized (lifecycleMonitor) {
            return lastFailure;
        }
    }

    @Override
    public RuntimeHostAdapters adapters() {
        return dynamic.view();
    }

    @Override
    public dev.turboism.sdk.cubism.model.CubismModelAccess modelAccess() {
        return dynamicModelAccess;
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
    public EditorObjectLifecycleCoordinator editorObjectLifecycle() {
        return editorObjectLifecycle;
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
    public RuntimeEditorUiActionRouter editorUiActionRouter() {
        return editorUiActionRouter;
    }

    @Override
    public EditorUiPluginResourceRegistry editorUiPluginResources() {
        return editorUiPluginResources;
    }

    @Override
    public AppearanceCoordinator appearanceCoordinator() {
        return appearanceCoordinator;
    }

    public dev.turboism.mapping.verification.VerifiedMemberResolver editorModelResolver() {
        synchronized (lifecycleMonitor) {
            if (activeConnection == null) {
                throw new IllegalStateException(
                    "No verified active Editor model resolver is available."
                );
            }
            return activeConnection.editorModelResolver();
        }
    }

    /** Returns a non-closeable trusted composition view while lifecycle ownership stays elsewhere. */
    public RuntimeHostAdapterAccess adapterAccess() {
        return new SessionRuntimeHostAdapterAccess(
            dynamic.view(),
            dynamicModelAccess,
            parameterLifecycle,
            partLifecycle,
            editorObjectLifecycle,
            editorUiLifecycle,
            editorUiContributions,
            editorUiActionRouter,
            editorUiPluginResources,
            appearanceCoordinator
        );
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
            final CleanupOutcome cleanup = cleanupOwnedResources();
            if (!cleanup.succeeded()) {
                finishCleanupFailure(cleanup, true);
                return;
            }
            appearanceCoordinator.close();
            editorObjectLifecycle.close();
            partLifecycle.close();
            parameterLifecycle.close();
            editorUiPluginResources.close();
            editorUiActionRouter.close();
            editorUiContributions.close();
            editorUiLifecycle.close();
            commit(State.CLOSED, Optional.empty());
        } finally {
            endTransition();
        }
    }

    private State enterSafeMode() {
        editorUiLifecycle.replacing();
        final CleanupOutcome cleanup = cleanupOwnedResources();
        if (!cleanup.succeeded()) {
            return finishCleanupFailure(cleanup, false);
        }
        if (closeRequested()) {
            return finishRequestedClose(null);
        }
        editorUiLifecycle.absent();
        return commit(State.SAFE_MODE, Optional.empty());
    }

    private State failAfterCleanup(
        final HostSessionFailure.Code failureCode,
        final String failureMessage
    ) {
        final CleanupOutcome cleanup = cleanupOwnedResources();
        if (!cleanup.succeeded()) {
            return finishCleanupFailure(cleanup, false);
        }
        return commitFailure(failureCode, failureMessage);
    }

    private State finishRequestedClose(final HostAdapterConnection candidate) {
        final CleanupOutcome candidateCleanup = closeCandidate(candidate);
        if (!candidateCleanup.succeeded()) {
            return finishCleanupFailure(candidateCleanup, false);
        }
        final CleanupOutcome ownedCleanup = cleanupOwnedResources();
        return ownedCleanup.succeeded()
            ? commit(State.CLOSED, Optional.empty())
            : finishCleanupFailure(ownedCleanup, false);
    }

    /** Registration cleanup must succeed before its owning connection can be closed. */
    private CleanupOutcome cleanupOwnedResources() {
        activeConnectionKey = null;
        dynamicModelAccess.deactivate();
        try {
            dynamic.deactivate();
        } catch (Throwable throwable) {
            return CleanupOutcome.failed(throwable);
        }

        CleanupOutcome outcome = CleanupOutcome.success();
        if (activeEditorUiProviders != null) {
            try {
                activeEditorUiProviders.close();
                activeEditorUiProviders = null;
            } catch (Throwable throwable) {
                pendingEditorUiProviderCleanup = activeEditorUiProviders;
                activeEditorUiProviders = null;
                outcome = outcome.combine(CleanupOutcome.failed(throwable));
            }
        }
        if (pendingEditorUiProviderCleanup != null) {
            try {
                pendingEditorUiProviderCleanup.close();
                pendingEditorUiProviderCleanup = null;
            } catch (Throwable throwable) {
                outcome = outcome.combine(CleanupOutcome.failed(throwable));
            }
        }
        if (activeConnection != null && pendingEditorUiProviderCleanup == null) {
            try {
                activeConnection.close();
                activeConnection = null;
            } catch (Throwable throwable) {
                outcome = outcome.combine(CleanupOutcome.failed(throwable));
            }
        }
        if (pendingConnectionCleanup != null && pendingEditorUiProviderCleanup == null) {
            try {
                pendingConnectionCleanup.close();
                pendingConnectionCleanup = null;
            } catch (Throwable throwable) {
                outcome = outcome.combine(CleanupOutcome.failed(throwable));
            }
        }
        return outcome;
    }

    private CleanupOutcome closeCandidate(final HostAdapterConnection candidate) {
        if (candidate == null) {
            return CleanupOutcome.success();
        }
        try {
            candidate.close();
            return CleanupOutcome.success();
        } catch (Throwable throwable) {
            pendingConnectionCleanup = candidate;
            return CleanupOutcome.failed(throwable);
        }
    }

    private State finishCleanupFailure(
        final CleanupOutcome cleanup,
        final boolean throwSanitizedNonError
    ) {
        final State failed = commitFailure(
            HostSessionFailure.Code.CLEANUP_FAILED,
            CLEANUP_FAILURE_MESSAGE
        );
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

    private boolean isCurrentConnection(final ConnectionKey connectionKey) {
        synchronized (lifecycleMonitor) {
            return state == State.ACTIVE
                && connectionKey.matches(activeConnectionKey)
                && pendingConnectionCleanup == null
                && pendingEditorUiProviderCleanup == null;
        }
    }

    private State commitFailure(final HostSessionFailure.Code code, final String message) {
        try {
            editorUiLifecycle.failed(EditorUiHostFailure.host(
                code == HostSessionFailure.Code.CLEANUP_FAILED
                    ? EditorUiHostFailure.Code.CLEANUP_FAILED
                    : EditorUiHostFailure.Code.REPLACEMENT_FAILED,
                "Editor UI host connection failed safely."
            ));
        } catch (IllegalStateException ignored) {
        }
        return commit(State.FAILED, Optional.of(new HostSessionFailure(code, message)));
    }

    private State commit(final State newState, final Optional<HostSessionFailure> failure) {
        synchronized (lifecycleMonitor) {
            state = newState;
            lastFailure = failure;
            return state;
        }
    }

    private boolean closeRequested() {
        synchronized (lifecycleMonitor) {
            return closeRequested;
        }
    }

    boolean closeRequestedForTest() {
        return closeRequested();
    }

    private boolean beginTransition(final boolean closing) {
        if (dynamic.isInAdapterCallOnCurrentThread()) {
            if (closing) {
                synchronized (lifecycleMonitor) {
                    closeRequested = true;
                }
            }
            return false;
        }

        synchronized (lifecycleMonitor) {
            if (closing) {
                closeRequested = true;
            }
            if (state == State.CLOSED || (!closing && closeRequested)) {
                return false;
            }
            if (transitionInProgress && transitionOwner == Thread.currentThread()) {
                return false;
            }
            if (!closing && transitionInProgress && closeRequested) {
                return false;
            }

            boolean interrupted = false;
            while (transitionInProgress) {
                if (!closing && closeRequested) {
                    return false;
                }
                try {
                    lifecycleMonitor.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (state == State.CLOSED || (!closing && closeRequested)) {
                return false;
            }
            transitionInProgress = true;
            transitionOwner = Thread.currentThread();
            return true;
        }
    }

    private void completeDeferredClose() {
        synchronized (lifecycleMonitor) {
            if (!closeRequested || state == State.CLOSED) {
                return;
            }
        }
        close();
    }

    private void endTransition() {
        synchronized (lifecycleMonitor) {
            transitionInProgress = false;
            transitionOwner = null;
            lifecycleMonitor.notifyAll();
        }
    }

    private record ConnectionKey(
        String sessionId,
        SliceKey projectWorkspace,
        java.util.Optional<SliceKey> clipMask,
        java.util.Optional<SliceKey> editorModel,
        java.util.Optional<SliceKey> mainToolbar
    ) {
        private static ConnectionKey from(final HostInstanceDescriptor descriptor) {
            final HostVerificationEvidence evidence = descriptor.verificationEvidence();
            return new ConnectionKey(
                descriptor.sessionId(),
                SliceKey.from(evidence.projectWorkspace()),
                evidence.clipMask().map(SliceKey::from),
                evidence.editorModel().map(SliceKey::from),
                evidence.mainToolbar().map(SliceKey::from)
            );
        }

        private static String normalizePath(final java.nio.file.Path path) {
            final java.nio.file.Path normalized = Objects.requireNonNull(
                path.toAbsolutePath().normalize(),
                "normalized path"
            );
            return Objects.requireNonNull(normalized.toString(), "normalized path text");
        }

        private boolean matches(final ConnectionKey other) {
            return other != null
                && sessionId.equals(other.sessionId)
                && projectWorkspace.matches(other.projectWorkspace)
                && optionalSliceMatches(clipMask, other.clipMask)
                && optionalSliceMatches(editorModel, other.editorModel)
                && optionalSliceMatches(mainToolbar, other.mainToolbar);
        }

        private static boolean optionalSliceMatches(
            final java.util.Optional<SliceKey> left,
            final java.util.Optional<SliceKey> right
        ) {
            return left.isEmpty() ? right.isEmpty()
                : right.isPresent() && left.orElseThrow().matches(right.orElseThrow());
        }
    }

    private record SliceKey(
        String reviewedRecord,
        String verifiedArtifact,
        ClassLoader hostClassLoader
    ) {
        private static SliceKey from(final HostVerificationEvidence.Slice slice) {
            return new SliceKey(
                ConnectionKey.normalizePath(slice.reviewedRecord()),
                ConnectionKey.normalizePath(slice.verifiedArtifact()),
                slice.hostClassLoader()
            );
        }

        private boolean matches(final SliceKey other) {
            return other != null
                && reviewedRecord.equals(other.reviewedRecord)
                && verifiedArtifact.equals(other.verifiedArtifact)
                && hostClassLoader == other.hostClassLoader;
        }
    }

    private record CleanupOutcome(Throwable failure) {
        private static CleanupOutcome success() {
            return new CleanupOutcome(null);
        }

        private static CleanupOutcome failed(final Throwable failure) {
            return new CleanupOutcome(Objects.requireNonNull(failure, "failure"));
        }

        private boolean succeeded() {
            return failure == null;
        }

        private CleanupOutcome combine(final CleanupOutcome other) {
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

    public enum State {
        SAFE_MODE,
        ACTIVE,
        FAILED,
        CLOSED
    }
}
