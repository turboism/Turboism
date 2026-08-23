package dev.turboism.bootstrap;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.host.HostInstanceDescriptor;
import dev.turboism.adapter.host.HostInstanceSource;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.adapter.host.HostSessionFailure;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;

import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Runtime-owned ingress for descriptors discovered by the future agent/bootstrap path.
 * Publishing performs verified artifact I/O and must run off editor-critical callbacks.
 */
public final class HostRuntimeIngress implements AutoCloseable {

    private final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    private final HostSession session;

    /** Production path: always uses the pinned verified project/workspace connector. */
    public HostRuntimeIngress() {
        this(HostSession::new);
    }

    /** Production composition with the locale fixed for this runtime startup. */
    public HostRuntimeIngress(final Locale effectiveLocale) {
        this(source -> new HostSession(source, Objects.requireNonNull(effectiveLocale, "effectiveLocale")));
    }

    HostRuntimeIngress(final Function<HostInstanceSource, HostSession> sessionFactory) {
        session = Objects.requireNonNull(sessionFactory, "sessionFactory")
            .apply(() -> closeRequested.get()
                ? Optional.empty()
                : Optional.ofNullable(current.get()));
    }

    /**
     * Publishes a discovered host descriptor and refreshes the session against it.
     *
     * <p>Performs verified artifact I/O, so it must not run on an Editor-critical callback. The
     * descriptor is retracted again whenever the session fails, closes, or a close is requested
     * concurrently, so a failed publish never leaves a half-admitted host visible.</p>
     *
     * @param descriptor the discovered host instance to admit
     * @return the session state after the refresh, or the terminal state when already closed
     * @throws NullPointerException when {@code descriptor} is null
     */
    public HostSession.State publish(final HostInstanceDescriptor descriptor) {
        if (closeRequested.get() || session.state() == HostSession.State.CLOSED) {
            current.set(null);
            return session.state();
        }
        final HostInstanceDescriptor published = Objects.requireNonNull(descriptor, "descriptor");
        current.set(published);
        if (closeRequested.get()) {
            current.compareAndSet(published, null);
            return session.state();
        }
        final HostSession.State refreshed = session.refresh();
        if (refreshed == HostSession.State.FAILED
            || refreshed == HostSession.State.CLOSED
            || closeRequested.get()) {
            current.compareAndSet(published, null);
        }
        return refreshed;
    }

    /**
     * Retracts the published descriptor and refreshes the session without one.
     *
     * @return the session state after the refresh, or the terminal state when already closed
     */
    public HostSession.State clear() {
        current.set(null);
        if (closeRequested.get() || session.state() == HostSession.State.CLOSED) {
            return session.state();
        }
        return session.refresh();
    }

    /**
     * Returns the current session state.
     *
     * @return the session lifecycle state
     */
    public HostSession.State state() {
        return session.state();
    }

    /**
     * Returns the failure that put the session into its current state, when there is one.
     *
     * @return the last session failure, or empty when the session never failed
     */
    public Optional<HostSessionFailure> lastFailure() {
        return session.lastFailure();
    }

    /**
     * Returns the adapters composed for the admitted host.
     *
     * @return the runtime host adapters for the current session
     */
    public RuntimeHostAdapters adapters() {
        return session.adapters();
    }

    /**
     * Returns the unified model access backed by the admitted host.
     *
     * @return model access for the current session, unavailable when no host is admitted
     */
    public dev.turboism.sdk.cubism.model.CubismModelAccess modelAccess() {
        return session.modelAccess();
    }

    /**
     * Returns the verified member resolver for the admitted Editor-model slice.
     *
     * @return the Editor-model resolver, or null when that slice is not admitted
     */
    public dev.turboism.mapping.verification.VerifiedMemberResolver editorModelResolver() {
        return session.editorModelResolver();
    }

    /**
     * Returns the texture-atlas data-model capture for the admitted host.
     *
     * @return the capture bound to the current session
     */
    public dev.turboism.adapter.cubism.textureatlas.TextureAtlasDataModelCapture
        textureAtlasDataModelCapture() {
        return session.textureAtlasDataModelCapture();
    }

    /**
     * Trusted non-closeable view for plugin-context composition.
     *
     * @return adapter access that plugin contexts may hold without owning the session lifecycle
     */
    public RuntimeHostAdapterAccess adapterAccess() {
        return session.adapterAccess();
    }

    @Override
    public void close() {
        closeRequested.set(true);
        current.set(null);
        session.close();
        current.set(null);
    }

    boolean hasCurrentDescriptorForTest() {
        return current.get() != null;
    }

    boolean isCloseRequestedForTest() {
        return closeRequested.get();
    }
}
