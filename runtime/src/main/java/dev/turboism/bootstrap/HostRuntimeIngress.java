package dev.turboism.bootstrap;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.host.HostInstanceDescriptor;
import dev.turboism.adapter.host.HostInstanceSource;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.adapter.host.HostSessionFailure;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;

import java.util.Objects;
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

    HostRuntimeIngress(final Function<HostInstanceSource, HostSession> sessionFactory) {
        session = Objects.requireNonNull(sessionFactory, "sessionFactory")
            .apply(() -> closeRequested.get()
                ? Optional.empty()
                : Optional.ofNullable(current.get()));
    }

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

    public HostSession.State clear() {
        current.set(null);
        if (closeRequested.get() || session.state() == HostSession.State.CLOSED) {
            return session.state();
        }
        return session.refresh();
    }

    public HostSession.State state() {
        return session.state();
    }

    public Optional<HostSessionFailure> lastFailure() {
        return session.lastFailure();
    }

    public RuntimeHostAdapters adapters() {
        return session.adapters();
    }

    public dev.turboism.sdk.cubism.model.CubismModelAccess modelAccess() {
        return session.modelAccess();
    }

    public dev.turboism.mapping.verification.VerifiedMemberResolver editorModelResolver() {
        return session.editorModelResolver();
    }

    /** Trusted non-closeable view for plugin-context composition. */
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
