package dev.turboism.adapter.cubism.mesh;

import java.util.List;
import java.util.Objects;

/** Non-throwing static ingress called only by the exact transformed mesh-editor lifecycle methods. */
public final class NativeMeshToolSessionBridge {
    private static final Object LOCK = new Object();
    private static Binding binding;
    private static NativeMeshToolSession current;
    private static boolean transitioning;

    private NativeMeshToolSessionBridge() {}

    /** Installs the single bootstrap-owned binding for an exact host generation. */
    public static void install(
            final Object owner,
            final long hostGeneration,
            final MeshToolSessionResolver resolver,
            final SessionListener listener) {
        Objects.requireNonNull(owner, "owner");
        if (hostGeneration < 0L) throw new IllegalArgumentException("hostGeneration must be non-negative");
        final Binding next = new Binding(
                owner,
                hostGeneration,
                Objects.requireNonNull(resolver, "resolver"),
                Objects.requireNonNull(listener, "listener"));
        synchronized (LOCK) {
            if (binding != null && binding.owner != owner) {
                throw new IllegalStateException("Mesh-tool session bridge is owned by another host generation.");
            }
            binding = next;
        }
    }

    /** Removes a binding only when the supplied owner still owns it. */
    public static void uninstall(final Object owner) {
        if (owner == null) return;
        final NativeMeshToolSession stale;
        final SessionListener listener;
        synchronized (LOCK) {
            if (binding == null || binding.owner != owner) return;
            listener = binding.listener;
            binding = null;
            stale = current;
            current = null;
            transitioning = false;
        }
        closeAndNotify(stale, listener);
    }

    /** Opens and publishes a verified session after native {@code startMode(List)} returns. */
    @SuppressWarnings("ReferenceEquality") // Binding identity is the generation lease, not record value equality.
    public static void afterStart(final Object mode, final List<?> startEntries) {
        final long started = System.nanoTime();
        String outcome = "skipped";
        Throwable failure = null;
        try {
            if (mode == null) {
                report("afterStart", outcome, started, null);
                return;
            }
            final Binding active;
            synchronized (LOCK) {
                if (binding == null || transitioning) {
                    outcome = binding == null ? "unbound" : "reentrant";
                    report("afterStart", outcome, started, null);
                    return;
                }
                transitioning = true;
                active = binding;
            }
            NativeMeshToolSession next = null;
            try {
                next = active.resolver
                        .open(mode, startEntries, active.hostGeneration)
                        .orElse(null);
                if (next == null) {
                    outcome = "rejected";
                    report("afterStart", outcome, started, null);
                    return;
                }
                final NativeMeshToolSession stale;
                synchronized (LOCK) {
                    if (binding != active) {
                        next.close();
                        outcome = "superseded";
                        report("afterStart", outcome, started, null);
                        return;
                    }
                    stale = current;
                    current = next;
                }
                closeAndNotify(stale, active.listener);
                try {
                    active.listener.opened(next);
                    outcome = "opened";
                } catch (Throwable openedFailure) {
                    dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(openedFailure);
                    synchronized (LOCK) {
                        if (current == next) current = null;
                    }
                    next.close();
                    outcome = "listener-failed";
                    failure = openedFailure;
                }
            } finally {
                synchronized (LOCK) {
                    transitioning = false;
                }
            }
        } catch (Throwable ignored) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
            outcome = "failed";
            failure = ignored;
            // Host lifecycle ingress must never throw into Cubism.
        }
        report("afterStart", outcome, started, failure);
    }

    /** Revokes the matching session before native {@code endMode()} executes. */
    public static void beforeEnd(final Object mode) {
        final long started = System.nanoTime();
        String outcome = "skipped";
        Throwable failure = null;
        try {
            if (mode == null) {
                report("beforeEnd", outcome, started, null);
                return;
            }
            final NativeMeshToolSession stale;
            final SessionListener listener;
            synchronized (LOCK) {
                if (binding == null
                        || transitioning
                        || current == null
                        || current.identity().mode() != mode) {
                    outcome = binding == null ? "unbound" : "no-match";
                    report("beforeEnd", outcome, started, null);
                    return;
                }
                transitioning = true;
                stale = current;
                current = null;
                listener = binding.listener;
            }
            try {
                closeAndNotify(stale, listener);
                outcome = "revoked";
            } catch (Throwable closeFailure) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(closeFailure);
                outcome = "close-failed";
                failure = closeFailure;
            } finally {
                synchronized (LOCK) {
                    transitioning = false;
                }
            }
        } catch (Throwable ignored) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
            outcome = "failed";
            failure = ignored;
            // Host lifecycle ingress must never throw into Cubism.
        }
        report("beforeEnd", outcome, started, failure);
    }

    /**
     * Reports one bounded host-lifecycle outcome.
     *
     * <p>The bridge deliberately never throws into Cubism, which previously also meant a rejected or
     * failed session open was completely invisible. Reporting the phase, the bounded outcome token,
     * and the elapsed time keeps those distinct failure modes diagnosable without logging host
     * objects or host stack frames.</p>
     */
    private static void report(
            final String phase, final String outcome, final long startedNanos, final Throwable failure) {
        final String detail = "mesh-tool lifecycle " + phase + " outcome=" + outcome + " elapsedMs="
                + ((System.nanoTime() - startedNanos) / 1_000_000L);
        // Success and no-op outcomes stay at DEBUG; anything meaning the session was not published is
        // reported at WARN, because a missing session is the difference between a working and a
        // silently dead custom tool and is otherwise invisible at the default log level.
        final boolean missedSession = "rejected".equals(outcome)
                || "unbound".equals(outcome)
                || "failed".equals(outcome)
                || "listener-failed".equals(outcome)
                || "close-failed".equals(outcome)
                || "superseded".equals(outcome);
        try {
            // Success and no-op outcomes are recorded at INFO: a mesh-edit session is user-driven and
            // rare, and an interactive session with no record cannot be distinguished from one where
            // nothing happened.
            if (failure == null && !missedSession) {
                dev.turboism.runtime.log.RuntimeDiagnostics.info("mesh-tool-session", detail);
            } else {
                dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                        "mesh-tool-session",
                        detail
                                + (failure == null
                                        ? ""
                                        : " failure=" + failure.getClass().getSimpleName()));
            }
        } catch (Throwable ignored) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
            // Diagnostics must never affect host lifecycle ingress.
        }
    }

    private static void closeAndNotify(final NativeMeshToolSession session, final SessionListener listener) {
        if (session == null) return;
        session.close();
        try {
            listener.closed(session);
        } catch (Throwable ignored) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
            // Listener failures are contained at the runtime-owned host boundary.
        }
    }

    static NativeMeshToolSession currentSessionForTests() {
        synchronized (LOCK) {
            return current;
        }
    }

    static void resetForTests() {
        final NativeMeshToolSession stale;
        synchronized (LOCK) {
            binding = null;
            stale = current;
            current = null;
            transitioning = false;
        }
        if (stale != null) stale.close();
    }

    /** Receives validated session publication and exact-session revocation. */
    public interface SessionListener {
        /** Publishes a new native mesh-session lease to its coordinator. */
        void opened(NativeMeshToolSession session);

        /** Revokes the matching session and its active custom tool. */
        void closed(NativeMeshToolSession session);
    }

    private record Binding(
            Object owner, long hostGeneration, MeshToolSessionResolver resolver, SessionListener listener) {}
}
