package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.sdk.cubism.edit.CancelSource;
import dev.turboism.sdk.cubism.edit.EditCancelledException;
import dev.turboism.sdk.cubism.edit.EditSession;
import dev.turboism.sdk.cubism.edit.EditSessionCloseResult;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditSessionListener;
import dev.turboism.sdk.cubism.edit.EditSessionOptions;
import dev.turboism.sdk.cubism.edit.EditSessionState;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.DocumentId;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime-owned manager for external-application edit sessions (spec 046, T2) — the port of the
 * official session-manager semantics:
 *
 * <ul>
 *   <li><b>single-session gate:</b> at most one admitted session exists per host; a second
 *   {@link #open} is refused while one is active,</li>
 *   <li><b>mutual exclusion with authoring transactions:</b> a session is refused while the
 *   {@link EditorAuthoringTransactionCoordinator}'s ambient scope is active, and the
 *   coordinator refuses roots while {@link #sessionActive()} holds,</li>
 *   <li><b>host-thread confinement:</b> admission, cancellation, commit, and cleanup all run on
 *   the host UI thread inside {@link EditorEditSessionHost#dispatch} with bounded waiting,</li>
 *   <li><b>UI lock:</b> the official window-disable + invisible modal interceptor + status
 *   dialog mechanism is engaged for the session's duration,</li>
 *   <li><b>cancellation:</b> user, plugin, or host cancellation transitions the session to
 *   {@code CANCELLED}, runs the selected {@link EditSessionRecovery}, disengages the UI lock,
 *   and delivers the {@code NotifyUndoCancel}-equivalent listener event.</li>
 * </ul>
 */
public final class RuntimeEditSessionManager {

    /** Official session label recorded on the native edit token. */
    public static final String SESSION_LABEL = "ExternalAPI.EditBegin";

    private static final String CODE_ADMISSION = "cubism.edit.session-admission";
    private static final String CODE_CONFLICT = "cubism.edit.scope-conflict";
    private static final String CODE_UI_LOCK = "cubism.edit.ui-lock";
    private static final String CODE_CLOSE_FAILED = "cubism.edit.close-failed";
    private static final String CODE_CANCEL_FAILED = "cubism.edit.cancel-failed";

    private final EditorEditSessionHost host;
    private final AtomicBoolean editScopeGate;
    private final EditSessionUiLockFactory uiLockFactory;
    private final EditSessionRecovery.Selector recoverySelector;
    private final Object lock = new Object();

    private volatile RuntimeEditSession active;

    /**
     * Creates a manager over one session host sharing the editing-scope gate with the authoring
     * transaction coordinator.
     *
     * @param host native session boundary
     * @param editScopeGate the editing-scope gate shared with {@link
     *     EditorAuthoringTransactionCoordinator}: the manager holds it for a session's whole
     *     lifetime, the coordinator holds it per root transaction, so sessions and transactions
     *     stay mutually exclusive in both directions — and atomically across threads
     * @param uiLockFactory builds the session's UI lock (fake in tests; no real dialogs)
     * @param recoverySelector chooses the cancellation recovery strategy per session
     */
    public RuntimeEditSessionManager(
        final EditorEditSessionHost host,
        final AtomicBoolean editScopeGate,
        final EditSessionUiLockFactory uiLockFactory,
        final EditSessionRecovery.Selector recoverySelector
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.editScopeGate = Objects.requireNonNull(editScopeGate, "editScopeGate");
        this.uiLockFactory = Objects.requireNonNull(uiLockFactory, "uiLockFactory");
        this.recoverySelector = Objects.requireNonNull(recoverySelector, "recoverySelector");
    }

    /**
     * Creates a production manager over one session host with the Swing UI lock and the
     * capability-driven recovery policy: verified {@code CUndoManager.revert()} when the
     * capability row binds, compensating recovery otherwise (the default on every currently
     * supported host).
     */
    public RuntimeEditSessionManager(
        final EditorEditSessionHost host,
        final AtomicBoolean editScopeGate
    ) {
        this(
            host,
            editScopeGate,
            new SwingEditSessionUiLockFactory(),
            EditSessionRecoveries.PREFER_REVERT_WHEN_VERIFIED
        );
    }

    /**
     * Reports whether an admitted session is currently open — the signal the authoring
     * coordinator consults to reject new roots. Safe to call from any thread.
     */
    public boolean sessionActive() {
        final RuntimeEditSession session = active;
        return session != null && session.isOpen();
    }

    /**
     * Admits a session on the host UI thread.
     *
     * @throws EditUnavailableException when a session is already active, an ambient authoring
     *     transaction is live, the binding is stale or not admitted, or the UI lock cannot engage
     */
    public EditSession open(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final DocumentId document,
        final EditSessionOptions options
    ) throws EditSessionException {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(options, "options");
        return host.dispatch("edit-session.open", () -> openOnHostThread(binding, document, options));
    }

    /**
     * Cancels one admitted session: transitions it to {@code CANCELLED} before recovery so in
     * parallel operations already fail as cancelled, runs the selected recovery, disengages the
     * UI lock, and delivers the undo-cancel listener event for non-plugin sources.
     */
    EditSessionCloseResult cancel(final RuntimeEditSession session, final CancelSource source)
        throws EditSessionException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(source, "source");
        return host.dispatch("edit-session.cancel", () -> {
            final RuntimeEditSession current;
            synchronized (lock) {
                current = active;
                if (session.state() != EditSessionState.OPEN || current != session) {
                    throw sessionUnavailable(session);
                }
                session.markCancelled(source);
                active = null;
            }
            EditSessionCloseResult result;
            try {
                final EditSessionRecovery recovery = Objects.requireNonNull(
                    recoverySelector.select(host, session.binding()),
                    "recovery selector result"
                );
                recovery.recover(host, new EditSessionRecoveryRequest(
                    session.binding(), session.editToken(), session.historyBefore()));
                result = EditSessionCloseResult.cancelled(source);
            } catch (EditSessionException | RuntimeException failure) {
                result = EditSessionCloseResult.failed(
                    host.diagnosticId(CODE_CANCEL_FAILED, failure));
            }
            disengage(session);
            notifyUndoCancelled(session, source);
            editScopeGate.set(false);
            return result;
        });
    }

    /**
     * Commits one admitted session: the native edit is ended for commit and the post-session
     * refresh runs before the UI lock releases.
     */
    EditSessionCloseResult close(final RuntimeEditSession session) throws EditSessionException {
        Objects.requireNonNull(session, "session");
        return host.dispatch("edit-session.close", () -> {
            final RuntimeEditSession current;
            synchronized (lock) {
                current = active;
                if (session.state() != EditSessionState.OPEN || current != session) {
                    throw sessionUnavailable(session);
                }
                session.markClosed();
                active = null;
            }
            try {
                host.endEdit(session.binding(), session.editToken(), false);
            } catch (RuntimeException failure) {
                try {
                    host.endEdit(session.binding(), session.editToken(), true);
                } catch (RuntimeException abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
                disengage(session);
                editScopeGate.set(false);
                return EditSessionCloseResult.failed(
                    host.diagnosticId(CODE_CLOSE_FAILED, failure));
            }
            try {
                host.refreshAfterSession(session.binding());
            } catch (RuntimeException ignored) {
                // post-session refresh is best-effort; the commit already landed
            }
            disengage(session);
            editScopeGate.set(false);
            return EditSessionCloseResult.committed();
        });
    }

    /**
     * Forcibly cancels the active session when it belongs to {@code pluginId} — the plugin
     * disable / project close cleanup path. No-op when no session is active or the active
     * session is owned by another plugin.
     */
    public void forceCancelFor(final String pluginId, final CancelSource source)
        throws EditSessionException {
        final String owner = Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(source, "source");
        host.dispatch("edit-session.force-cancel", () -> {
            final RuntimeEditSession session = active;
            if (session == null || !session.binding().pluginId().equals(owner)) {
                return null;
            }
            return cancel(session, source);
        });
    }

    /**
     * Forcibly cancels one specific session — used by staleness detection inside the operation
     * gate so a switched/closed document tears the session down instead of admitting work.
     */
    void forceCancel(final RuntimeEditSession session, final CancelSource source)
        throws EditSessionException {
        host.dispatch("edit-session.force-cancel", () -> {
            synchronized (lock) {
                if (session.state() != EditSessionState.OPEN || active != session) {
                    return null;
                }
            }
            return cancel(session, source);
        });
    }

    /** Reports whether the session's binding went stale (document/model switched or closed). */
    boolean isStale(final RuntimeEditSession session) {
        try {
            return !host.isCurrent(session.binding());
        } catch (RuntimeException failure) {
            return true;
        }
    }

    void log(final RuntimeEditSession session, final String message)
        throws EditSessionException {
        host.dispatch("edit-session.log", () -> {
            requireActive(session);
            session.uiLock().log(message);
            return null;
        });
    }

    void progress(final RuntimeEditSession session, final double value)
        throws EditSessionException {
        host.dispatch("edit-session.progress", () -> {
            requireActive(session);
            session.uiLock().progress(value);
            return null;
        });
    }

    private RuntimeEditSession openOnHostThread(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final DocumentId document,
        final EditSessionOptions options
    ) throws EditSessionException {
        final AtomicReference<RuntimeEditSession> pending = new AtomicReference<>();
        synchronized (lock) {
            // One atomic admission covers both exclusion directions: a held gate means either an
            // active session or a live authoring transaction root.
            if (!editScopeGate.compareAndSet(false, true)) {
                throw new EditUnavailableException(
                    CODE_CONFLICT,
                    "Another editing scope (edit session or authoring transaction) is active"
                );
            }
            try {
                if (!host.isCurrent(binding)) {
                    throw new EditUnavailableException(
                        CODE_ADMISSION,
                        "The requested document is not the active modeling document"
                    );
                }
                if (!host.admits(binding)) {
                    throw new EditUnavailableException(
                        CODE_ADMISSION,
                        "The connected Cubism editor does not admit external edit sessions"
                    );
                }
                final HistorySnapshot before = host.history(binding);
                if (before.availability() != HistorySnapshot.Availability.AVAILABLE) {
                    throw new EditUnavailableException(
                        CODE_ADMISSION,
                        "The native Undo history is unavailable; the session cannot be admitted"
                    );
                }
                final Object editToken = host.beginEdit(binding, SESSION_LABEL);
                final EditSessionUiLock uiLock;
                try {
                    uiLock = Objects.requireNonNull(
                        uiLockFactory.create(new EditSessionUiLockContext(
                            host.mainWindow(binding),
                            () -> {
                                final RuntimeEditSession session = pending.get();
                                if (session != null) {
                                    try {
                                        cancel(session, CancelSource.USER);
                                    } catch (EditSessionException ignored) {
                                        // the session is already in a terminal state
                                    }
                                }
                            }
                        )),
                        "ui lock"
                    );
                    uiLock.engage(options.silent());
                } catch (RuntimeException failure) {
                    try {
                        host.endEdit(binding, editToken, true);
                    } catch (RuntimeException abortFailure) {
                        failure.addSuppressed(abortFailure);
                    }
                    throw new EditUnavailableException(
                        CODE_UI_LOCK,
                        "The edit session UI lock could not engage: " + failure.getMessage()
                    );
                }
                final RuntimeEditSession session = new RuntimeEditSession(
                    this, binding, editToken, document, options, uiLock, before);
                pending.set(session);
                active = session;
                return session;
            } catch (EditSessionException | RuntimeException | Error failure) {
                editScopeGate.set(false);
                throw failure;
            }
        }
    }

    private void requireActive(final RuntimeEditSession session) throws EditSessionException {
        synchronized (lock) {
            if (session.state() == EditSessionState.CANCELLED) {
                throw new EditCancelledException(
                    Objects.requireNonNullElse(session.cancelledBy().orElse(null),
                        CancelSource.HOST));
            }
            if (session.state() != EditSessionState.OPEN || active != session) {
                throw new EditUnavailableException("EditSession operation");
            }
        }
    }

    private EditSessionException sessionUnavailable(final RuntimeEditSession session) {
        if (session.state() == EditSessionState.CANCELLED) {
            return new EditCancelledException(
                session.cancelledBy().orElse(CancelSource.HOST));
        }
        return new EditUnavailableException("EditSession operation");
    }

    private void disengage(final RuntimeEditSession session) {
        try {
            session.uiLock().disengage();
        } catch (RuntimeException ignored) {
            // releasing the lock is best-effort; it must never mask the close outcome
        }
    }

    private void notifyUndoCancelled(final RuntimeEditSession session, final CancelSource source) {
        if (source == CancelSource.PLUGIN) {
            return;
        }
        final Optional<EditSessionListener> listener = session.options().undoCancelListener();
        if (listener.isEmpty()) {
            return;
        }
        try {
            listener.orElseThrow().onUndoCancelled(session, source);
        } catch (RuntimeException ignored) {
            // a listener failure must never mask the cancellation outcome
        }
    }
}
