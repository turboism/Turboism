package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.plugin.Registration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Runtime owner of one exact custom mesh-tool activation per host session. */
public final class MeshToolCoordinator implements AutoCloseable {
    private static final Comparator<RegisteredTool> TOOL_ORDER = Comparator.comparingInt(
                    (RegisteredTool value) -> value.tool().order())
            .thenComparing(RegisteredTool::pluginId)
            .thenComparingLong(RegisteredTool::generation)
            .thenComparing(value -> value.tool().id());

    private final Object monitor = new Object();
    private final Object transitionGate = new Object();
    private final ArrayDeque<Runnable> deferredTransitions = new ArrayDeque<>();
    private Thread transitionOwner;
    private final Map<Key, StoredTool> tools = new HashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Session session;
    private long hostGeneration;
    private long sessionGeneration;
    private long activationGeneration;
    private long nextPluginGeneration;
    private Active active;
    private boolean closed;

    /** Allocates the next monotonic plugin generation. */
    public long allocateGeneration() {
        synchronized (monitor) {
            requireOpen();
            return ++nextPluginGeneration;
        }
    }

    /** Registers one tool for an exact plugin generation. */
    public Registration register(
            final String pluginId,
            final long pluginGeneration,
            final MeshTool tool,
            final PermissionChecker permissionChecker) {
        final Key key = new Key(
                requireText(pluginId, "pluginId"),
                requireGeneration(pluginGeneration),
                requireText(Objects.requireNonNull(tool, "tool").id(), "tool.id"));
        requireText(tool.label(), "tool.label");
        requireText(tool.iconResourcePath(), "tool.iconResourcePath");
        final StoredTool stored = new StoredTool(tool, Objects.requireNonNull(permissionChecker, "permissionChecker"));
        synchronized (transitionGate) {
            synchronized (monitor) {
                requireOpen();
                if (tools.putIfAbsent(key, stored) != null) {
                    throw new IllegalStateException("mesh tool is already registered for the exact plugin generation");
                }
            }
            notifyChanged();
        }
        final AtomicBoolean registrationClosed = new AtomicBoolean();
        return () -> {
            if (!registrationClosed.compareAndSet(false, true)) return;
            runTransition(() -> {
                final PendingDeactivate pending;
                synchronized (monitor) {
                    if (closed) return;
                    tools.remove(key, stored);
                    pending = active != null && active.key.equals(key) ? clearActiveLocked() : null;
                }
                dispatchDeactivate(pending);
                notifyChanged();
            });
        };
    }

    /** Removes every tool owned by an exact plugin generation. */
    public void removePlugin(final String pluginId, final long pluginGeneration) {
        final String id = requireText(pluginId, "pluginId");
        final long generation = requireGeneration(pluginGeneration);
        runTransition(() -> {
            final PendingDeactivate pending;
            synchronized (monitor) {
                if (closed) return;
                tools.keySet().removeIf(key -> key.pluginId.equals(id) && key.generation == generation);
                pending = active != null && active.key.pluginId.equals(id) && active.key.generation == generation
                        ? clearActiveLocked()
                        : null;
            }
            dispatchDeactivate(pending);
            notifyChanged();
        });
    }

    /**
     * Reports whether an exact native mesh-editor session is currently open, without exposing it.
     *
     * <p>Tool activation requires an open session, and the activation failure travels through the
     * host action ingress, which otherwise cannot tell “no mesh session” apart from “stale lease”.
     */
    public boolean hasActiveSession() {
        synchronized (monitor) {
            return session != null;
        }
    }
    /** Returns registered tools in deterministic toolbar order. */
    public List<RegisteredTool> snapshot() {
        synchronized (monitor) {
            return tools.entrySet().stream()
                    .map(entry -> new RegisteredTool(
                            entry.getKey().pluginId, entry.getKey().generation, entry.getValue().tool))
                    .sorted(TOOL_ORDER)
                    .toList();
        }
    }

    /** Replaces the current native mesh session and revokes active work. */
    public void beginSession(final Session nextSession) {
        final Session requested = Objects.requireNonNull(nextSession, "session");
        runTransition(() -> {
            final PendingDeactivate pending;
            synchronized (monitor) {
                requireOpen();
                pending = clearActiveLocked();
                session = requested;
                hostGeneration = requested.hostGeneration();
                sessionGeneration++;
            }
            dispatchDeactivate(pending);
            notifyChanged();
        });
    }

    /** Ends the current native mesh session and revokes its activation. */
    public void endSession() {
        runTransition(() -> {
            final PendingDeactivate pending;
            synchronized (monitor) {
                if (closed) return;
                pending = clearActiveLocked();
                session = null;
                sessionGeneration++;
            }
            dispatchDeactivate(pending);
            notifyChanged();
        });
    }

    /** Advances host generation and revokes state bound to the previous host. */
    public void replaceHostGeneration(final long generation) {
        if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
        runTransition(() -> {
            final PendingDeactivate pending;
            synchronized (monitor) {
                if (closed) return;
                pending = clearActiveLocked();
                hostGeneration = generation;
                if (session != null && session.hostGeneration() != generation) {
                    session = null;
                    sessionGeneration++;
                }
            }
            dispatchDeactivate(pending);
            notifyChanged();
        });
    }

    /** Activates the tool identified by plugin, generation, and tool id. */
    public void activate(final String pluginId, final long pluginGeneration, final String toolId) {
        final Key key = new Key(
                requireText(pluginId, "pluginId"), requireGeneration(pluginGeneration), requireText(toolId, "toolId"));
        runTransition(() -> activateNow(key));
    }

    /** Deactivates the tool only when the full identity is current. */
    public void deactivate(final String pluginId, final long pluginGeneration, final String toolId) {
        final Key key = new Key(
                requireText(pluginId, "pluginId"), requireGeneration(pluginGeneration), requireText(toolId, "toolId"));
        runTransition(() -> {
            final PendingDeactivate pending;
            synchronized (monitor) {
                pending = active != null && active.key.equals(key) ? clearActiveLocked() : null;
            }
            dispatchDeactivate(pending);
            if (pending != null) notifyChanged();
        });
    }

    /** Revokes a custom activation before a native toolbar action proceeds. */
    public void nativeToolActivated() {
        runTransition(() -> {
            final PendingDeactivate pending;
            synchronized (monitor) {
                pending = clearActiveLocked();
            }
            dispatchDeactivate(pending);
            if (pending != null) notifyChanged();
        });
    }

    /** Routes Escape through coordinator-owned deactivation. */
    public void requestEscape() {
        nativeToolActivated();
    }

    /** Returns the currently active registered tool, if any. */
    public Optional<ActiveTool> activeTool() {
        synchronized (monitor) {
            return active == null
                    ? Optional.empty()
                    : Optional.of(new ActiveTool(active.key.pluginId, active.key.generation, active.stored.tool));
        }
    }

    /** Returns the exact current activation lease, if any. */
    public Optional<ActivationLease> activeLease() {
        synchronized (monitor) {
            return active == null ? Optional.empty() : Optional.of(active.lease);
        }
    }

    /** Registers a listener for serialized coordinator state changes. */
    public Registration onStateChanged(final Runnable listener) {
        final Runnable value = Objects.requireNonNull(listener, "listener");
        synchronized (monitor) {
            requireOpen();
        }
        listeners.add(value);
        return () -> listeners.remove(value);
    }

    <T> T inTransition(final Supplier<T> action) {
        synchronized (transitionGate) {
            return Objects.requireNonNull(action, "action").get();
        }
    }

    boolean isCurrent(final ActivationLease lease) {
        synchronized (monitor) {
            return !closed
                    && active != null
                    && active.lease == lease
                    && session == lease.session
                    && hostGeneration == lease.hostGeneration
                    && sessionGeneration == lease.sessionGeneration
                    && activationGeneration == lease.activationGeneration
                    && lease.session.revalidate();
        }
    }

    private void activateNow(final Key key) {
        final PendingDeactivate previous;
        final Active next;
        synchronized (monitor) {
            requireOpen();
            final StoredTool stored = tools.get(key);
            if (stored == null) throw new IllegalArgumentException("no registered mesh tool for the exact identity");
            if (active != null && active.key.equals(key)) return;
            final Session current = session;
            if (current == null || current.hostGeneration() != hostGeneration || !current.revalidate()) {
                throw new IllegalStateException("no current exact mesh-tool session");
            }
            previous = clearActiveLocked();
            final long activation = ++activationGeneration;
            final ActivationLease lease = new ActivationLease(
                    this,
                    key.pluginId,
                    key.generation,
                    key.toolId,
                    hostGeneration,
                    sessionGeneration,
                    activation,
                    current);
            final RuntimeMeshToolContext context = new RuntimeMeshToolContext(
                    new SessionBoundMeshEditor(lease, stored.permissionChecker),
                    lease,
                    () -> deactivate(key.pluginId, key.generation, key.toolId),
                    stored.tool,
                    stored.permissionChecker);
            next = new Active(key, stored, context, lease);
            active = next;
        }
        dispatchDeactivate(previous);
        try {
            next.stored.tool.activate(next.context);
            synchronized (monitor) {
                if (active == next) next.activated = true;
            }
        } catch (RuntimeException | Error failure) {
            synchronized (monitor) {
                if (active == next) clearActiveLocked();
            }
            next.context.close();
            notifyChanged();
            throw failure;
        }
        notifyChanged();
    }

    private void runTransition(final Runnable action) {
        Objects.requireNonNull(action, "action");
        synchronized (transitionGate) {
            if (transitionOwner == Thread.currentThread()) {
                deferredTransitions.addLast(action);
                return;
            }
            transitionOwner = Thread.currentThread();
            Throwable first = null;
            try {
                try {
                    action.run();
                } catch (Throwable failure) {
                    dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
                    first = failure;
                }
                Runnable deferred;
                while ((deferred = deferredTransitions.pollFirst()) != null) {
                    try {
                        deferred.run();
                    } catch (Throwable failure) {
                        dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
                        if (first == null) first = failure;
                        else first.addSuppressed(failure);
                    }
                }
            } finally {
                transitionOwner = null;
                deferredTransitions.clear();
            }
            if (first instanceof RuntimeException runtime) throw runtime;
            if (first instanceof Error error) throw error;
            if (first != null) throw new IllegalStateException("mesh-tool transition failed", first);
        }
    }

    private PendingDeactivate clearActiveLocked() {
        final Active previous = active;
        if (previous == null) return null;
        active = null;
        activationGeneration++;
        return new PendingDeactivate(previous.stored.tool, previous.context, previous.activated);
    }

    private static void dispatchDeactivate(final PendingDeactivate pending) {
        if (pending == null) return;
        try {
            pending.context.close();
        } finally {
            if (pending.activated) pending.tool.deactivate();
        }
    }

    private void notifyChanged() {
        for (Runnable listener : List.copyOf(listeners)) {
            try {
                listener.run();
            } catch (Throwable ignored) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
            }
        }
    }

    @Override
    public void close() {
        runTransition(() -> {
            final PendingDeactivate pending;
            synchronized (monitor) {
                if (closed) return;
                closed = true;
                pending = clearActiveLocked();
                tools.clear();
                session = null;
                sessionGeneration++;
            }
            try {
                dispatchDeactivate(pending);
            } finally {
                listeners.clear();
            }
        });
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("mesh tool coordinator is closed");
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static long requireGeneration(final long value) {
        if (value < 0) throw new IllegalArgumentException("generation must not be negative");
        return value;
    }

    /** Host-independent exact mesh-session seam used by activation-bound editor handles. */
    public interface Session {
        /** Returns the host UI generation that owns this session. */
        long hostGeneration();

        /** Verifies all captured native owners are still current. */
        boolean revalidate();

        /** Returns the SDK ArtMesh associated with this native session. */
        Drawable drawable();

        /** Reads an immutable native vertex-selection snapshot. */
        VertexSelection selection();

        /** Commits only native vertex selection, without authoring or Undo operations. */
        void select(VertexSelection selection, SelectionMode mode);
    }

    /** Immutable registered-tool view. */
    public record RegisteredTool(String pluginId, long generation, MeshTool tool) {}
    /** Immutable active-tool view. */
    public record ActiveTool(String pluginId, long generation, MeshTool tool) {}

    /** Exact activation identity; every operation revalidates all captured owners. */
    public static final class ActivationLease {
        private final MeshToolCoordinator owner;
        private final String pluginId;
        private final long pluginGeneration;
        private final String toolId;
        private final long hostGeneration;
        private final long sessionGeneration;
        private final long activationGeneration;
        private final Session session;

        private ActivationLease(
                MeshToolCoordinator owner,
                String pluginId,
                long pluginGeneration,
                String toolId,
                long hostGeneration,
                long sessionGeneration,
                long activationGeneration,
                Session session) {
            this.owner = owner;
            this.pluginId = pluginId;
            this.pluginGeneration = pluginGeneration;
            this.toolId = toolId;
            this.hostGeneration = hostGeneration;
            this.sessionGeneration = sessionGeneration;
            this.activationGeneration = activationGeneration;
            this.session = session;
        }

        /** Returns the owning plugin id. */
        public String pluginId() {
            return pluginId;
        }
        /** Returns the owning plugin generation. */
        public long pluginGeneration() {
            return pluginGeneration;
        }
        /** Returns the activated tool id. */
        public String toolId() {
            return toolId;
        }
        /** Returns the captured host generation. */
        public long hostGeneration() {
            return hostGeneration;
        }
        /** Returns the captured native-session generation. */
        public long sessionGeneration() {
            return sessionGeneration;
        }
        /** Returns the captured activation generation. */
        public long activationGeneration() {
            return activationGeneration;
        }
        /** Revalidates every captured owner against current coordinator state. */
        public boolean revalidate() {
            return owner.isCurrent(this);
        }
        /** Fails when this lease is no longer the exact current activation. */
        public void requireCurrent() {
            if (!revalidate()) throw new IllegalStateException("mesh-tool activation lease is stale");
        }

        Session session() {
            requireCurrent();
            return session;
        }
    }

    private record Key(String pluginId, long generation, String toolId) {}

    private record StoredTool(MeshTool tool, PermissionChecker permissionChecker) {}

    private record PendingDeactivate(MeshTool tool, RuntimeMeshToolContext context, boolean activated) {}

    private static final class Active {
        final Key key;
        final StoredTool stored;
        final RuntimeMeshToolContext context;
        final ActivationLease lease;
        boolean activated;

        Active(Key key, StoredTool stored, RuntimeMeshToolContext context, ActivationLease lease) {
            this.key = key;
            this.stored = stored;
            this.context = context;
            this.lease = lease;
        }
    }
}
