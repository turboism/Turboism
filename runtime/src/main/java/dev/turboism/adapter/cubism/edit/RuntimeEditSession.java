package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.sdk.cubism.edit.CancelSource;
import dev.turboism.sdk.cubism.edit.DeformerOps;
import dev.turboism.sdk.cubism.edit.EditCancelledException;
import dev.turboism.sdk.cubism.edit.EditSession;
import dev.turboism.sdk.cubism.edit.EditSessionCloseResult;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditSessionOpenResult;
import dev.turboism.sdk.cubism.edit.EditSessionOptions;
import dev.turboism.sdk.cubism.edit.EditSessionState;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.edit.ParameterKeyOps;
import dev.turboism.sdk.cubism.edit.ParameterStructureOps;
import dev.turboism.sdk.cubism.edit.PartObjectOps;
import dev.turboism.sdk.cubism.edit.SelectionOps;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.DocumentId;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One admitted edit session, owned by {@link RuntimeEditSessionManager}.
 *
 * <p>Lifecycle follows the official session machine: the session is admitted in {@code OPEN},
 * and reaches exactly one terminal state — {@code CANCELLED} (model restored, no history entry)
 * or {@code CLOSED} (model edits committed). Every admitting member re-validates the state and
 * the binding's staleness; a stale binding forces a host-side cancellation so a document switch
 * or close can never leave edits dangling.</p>
 *
 * <p>The five operation families are routed through {@link SessionOpsGate}: Phase 2 delegates
 * are the fail-closed {@code unavailable()} families (operation routing is Phase 3), so an open
 * session reports typed unavailability while a cancelled one always reports cancellation.</p>
 */
final class RuntimeEditSession implements EditSession {

    private final RuntimeEditSessionManager manager;
    private final EditorAuthoringTransactionCoordinator.Binding binding;
    private final Object editToken;
    private final DocumentId document;
    private final EditSessionOptions options;
    private final EditSessionOpenResult openResult;
    private final EditSessionUiLock uiLock;
    private final HistorySnapshot historyBefore;

    private final AtomicReference<EditSessionState> state =
        new AtomicReference<>(EditSessionState.OPEN);
    private volatile CancelSource cancelSource;

    private final ParameterKeyOps parameterKeys =
        SessionOpsGate.bind(ParameterKeyOps.class, ParameterKeyOps.unavailable(), this);
    private final ParameterStructureOps parameterStructure =
        SessionOpsGate.bind(ParameterStructureOps.class, ParameterStructureOps.unavailable(), this);
    private final SelectionOps selection =
        SessionOpsGate.bind(SelectionOps.class, SelectionOps.unavailable(), this);
    private final PartObjectOps partObjects =
        SessionOpsGate.bind(PartObjectOps.class, PartObjectOps.unavailable(), this);
    private final DeformerOps deformers =
        SessionOpsGate.bind(DeformerOps.class, DeformerOps.unavailable(), this);

    RuntimeEditSession(
        final RuntimeEditSessionManager manager,
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final Object editToken,
        final DocumentId document,
        final EditSessionOptions options,
        final EditSessionUiLock uiLock,
        final HistorySnapshot historyBefore
    ) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.editToken = Objects.requireNonNull(editToken, "editToken");
        this.document = Objects.requireNonNull(document, "document");
        this.options = Objects.requireNonNull(options, "options");
        this.uiLock = Objects.requireNonNull(uiLock, "uiLock");
        this.historyBefore = Objects.requireNonNull(historyBefore, "historyBefore");
        this.openResult = new EditSessionOpenResult(document, options);
    }

    @Override
    public DocumentId document() {
        return document;
    }

    @Override
    public EditSessionState state() {
        return state.get();
    }

    @Override
    public EditSessionOpenResult openResult() {
        return openResult;
    }

    @Override
    public Optional<CancelSource> cancelledBy() {
        return Optional.ofNullable(cancelSource);
    }

    @Override
    public boolean isOpen() {
        return state.get() == EditSessionState.OPEN;
    }

    @Override
    public void log(final String message) throws EditSessionException {
        Objects.requireNonNull(message, "message");
        requireAdmitting();
        manager.log(this, message);
    }

    @Override
    public void progress(final double value) throws EditSessionException {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("progress must be within [0.0, 1.0]");
        }
        requireAdmitting();
        manager.progress(this, value);
    }

    @Override
    public EditSessionCloseResult cancel() throws EditSessionException {
        requireAdmitting();
        return manager.cancel(this, CancelSource.PLUGIN);
    }

    @Override
    public EditSessionCloseResult close() throws EditSessionException {
        requireAdmitting();
        return manager.close(this);
    }

    @Override
    public ParameterKeyOps parameterKeys() {
        return parameterKeys;
    }

    @Override
    public ParameterStructureOps parameterStructure() {
        return parameterStructure;
    }

    @Override
    public SelectionOps selection() {
        return selection;
    }

    @Override
    public PartObjectOps partObjects() {
        return partObjects;
    }

    @Override
    public DeformerOps deformers() {
        return deformers;
    }

    /**
     * The fail-closed admission check every mutating member and every gated operation runs:
     * {@code OPEN} sessions re-validate binding staleness (a stale binding is force-cancelled by
     * the host), {@code CANCELLED} sessions raise {@link EditCancelledException} carrying the
     * recorded source, and {@code CLOSED} sessions raise {@link EditUnavailableException}.
     */
    void requireAdmitting() throws EditSessionException {
        if (state.get() == EditSessionState.OPEN) {
            if (manager.isStale(this)) {
                manager.forceCancel(this, CancelSource.HOST);
            } else {
                return;
            }
        }
        if (state.get() == EditSessionState.CANCELLED) {
            throw new EditCancelledException(
                Objects.requireNonNullElse(cancelSource, CancelSource.HOST));
        }
        throw new EditUnavailableException("EditSession operation");
    }

    EditorAuthoringTransactionCoordinator.Binding binding() {
        return binding;
    }

    Object editToken() {
        return editToken;
    }

    EditSessionUiLock uiLock() {
        return uiLock;
    }

    HistorySnapshot historyBefore() {
        return historyBefore;
    }

    EditSessionOptions options() {
        return options;
    }

    void markCancelled(final CancelSource source) {
        cancelSource = Objects.requireNonNull(source, "source");
        state.set(EditSessionState.CANCELLED);
    }

    void markClosed() {
        state.set(EditSessionState.CLOSED);
    }
}
