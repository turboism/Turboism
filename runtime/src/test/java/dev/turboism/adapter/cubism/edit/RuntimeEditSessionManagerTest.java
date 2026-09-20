package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.sdk.cubism.edit.CancelSource;
import dev.turboism.sdk.cubism.edit.EditCancelledException;
import dev.turboism.sdk.cubism.edit.EditSession;
import dev.turboism.sdk.cubism.edit.EditSessionCloseOutcome;
import dev.turboism.sdk.cubism.edit.EditSessionCloseResult;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditSessionListener;
import dev.turboism.sdk.cubism.edit.EditSessionOptions;
import dev.turboism.sdk.cubism.edit.EditSessionState;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeEditSessionManagerTest {

    private static final DocumentId DOCUMENT = new DocumentId("document-1");

    @Test
    void admitsOneSessionAndEngagesTheUiLock() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        assertTrue(session.isOpen());
        assertEquals(EditSessionState.OPEN, session.state());
        assertEquals(DOCUMENT, session.document());
        assertEquals(
            List.of("history", "beginEdit:ExternalAPI.EditBegin"),
            fixture.host.calls
        );
        assertTrue(fixture.lock.engaged);
        assertTrue(fixture.manager.sessionActive());
        assertEquals(
            List.of(
                "edit-session.open"
            ),
            fixture.host.dispatchLabels
        );
    }

    @Test
    void refusesASecondSessionWhileOneIsActive() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.open();

        assertThrows(
            EditUnavailableException.class,
            () -> fixture.manager.open(fixture.host.binding, DOCUMENT, EditSessionOptions.defaults())
        );
        assertEquals(1, fixture.host.beginCount);
    }

    @Test
    void releasesTheSessionGateAfterACloseSoANewSessionCanOpen() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession first = fixture.open();
        first.close();

        assertFalse(fixture.manager.sessionActive());
        final EditSession second = fixture.open();
        assertTrue(second.isOpen());
    }

    @Test
    void refusesOpenWhileAnAuthoringTransactionHoldsTheScopeGate() {
        final Fixture fixture = new Fixture();
        fixture.editScopeGate.set(true);

        assertThrows(
            EditUnavailableException.class,
            () -> fixture.manager.open(fixture.host.binding, DOCUMENT, EditSessionOptions.defaults())
        );
        assertEquals(0, fixture.host.beginCount);
    }

    @Test
    void refusesOpenWhenTheBindingIsStale() {
        final Fixture fixture = new Fixture();
        fixture.host.current = false;

        assertThrows(
            EditUnavailableException.class,
            () -> fixture.manager.open(fixture.host.binding, DOCUMENT, EditSessionOptions.defaults())
        );
        assertEquals(0, fixture.host.beginCount);
        assertFalse(fixture.editScopeGate.get());
    }

    @Test
    void refusesOpenWhenTheHostDoesNotAdmitSessions() {
        final Fixture fixture = new Fixture();
        fixture.host.admitted = false;

        assertThrows(
            EditUnavailableException.class,
            () -> fixture.manager.open(fixture.host.binding, DOCUMENT, EditSessionOptions.defaults())
        );
        assertEquals(0, fixture.host.beginCount);
        assertFalse(fixture.editScopeGate.get());
    }

    @Test
    void refusesOpenWhenHistoryIsUnavailable() {
        final Fixture fixture = new Fixture();
        fixture.host.history = HistorySnapshot.unavailable();

        assertThrows(
            EditUnavailableException.class,
            () -> fixture.manager.open(fixture.host.binding, DOCUMENT, EditSessionOptions.defaults())
        );
        assertEquals(0, fixture.host.beginCount);
        assertFalse(fixture.editScopeGate.get());
    }

    @Test
    void abortsTheNativeEditWhenTheUiLockCannotEngage() {
        final Fixture fixture = new Fixture();
        fixture.failLock = true;

        assertThrows(
            EditUnavailableException.class,
            () -> fixture.manager.open(fixture.host.binding, DOCUMENT, EditSessionOptions.defaults())
        );
        assertEquals(1, fixture.host.beginCount);
        assertEquals(List.of(Boolean.TRUE), fixture.host.endEditCancelFlags);
        assertFalse(fixture.editScopeGate.get());
    }

    @Test
    void pluginCancelRunsTheCompensatingRecoveryByDefault() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        final EditSessionCloseResult result = session.cancel();

        assertEquals(EditSessionCloseOutcome.CANCELLED, result.outcome());
        assertEquals(EditSessionState.CANCELLED, session.state());
        assertEquals(Optional.of(CancelSource.PLUGIN), session.cancelledBy());
        // Compensating path: abort the native edit, verify history, refresh — no revert call.
        assertEquals(List.of(Boolean.TRUE), fixture.host.endEditCancelFlags);
        assertEquals(0, fixture.host.revertCount);
        assertEquals(1, fixture.host.refreshCount);
        assertFalse(fixture.lock.engaged);
        assertFalse(fixture.editScopeGate.get());
    }

    @Test
    void alwaysCompensatingSelectorNeverCallsRevert() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.host.revertVerified = true;
        final RuntimeEditSessionManager alwaysCompensating = new RuntimeEditSessionManager(
            fixture.host,
            fixture.editScopeGate,
            fixture.lockFactory,
            EditSessionRecoveries.ALWAYS_COMPENSATING
        );
        final EditSession session = alwaysCompensating.open(
            fixture.host.binding, DOCUMENT, EditSessionOptions.defaults());

        session.cancel();

        assertEquals(List.of(Boolean.TRUE), fixture.host.endEditCancelFlags);
        assertEquals(0, fixture.host.revertCount);
    }

    @Test
    void reportsAFailedCloseWhenRecoveryCannotVerifyTheRestore() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();
        fixture.host.historyChangedOnRecovery = true;

        final EditSessionCloseResult result = session.cancel();

        assertEquals(EditSessionCloseOutcome.FAILED, result.outcome());
        assertTrue(result.diagnosticId().isPresent());
        assertEquals(EditSessionState.CANCELLED, session.state());
        assertFalse(fixture.editScopeGate.get());
    }

    @Test
    void closeCommitsTheEditAndReleasesTheUiLock() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        final EditSessionCloseResult result = session.close();

        assertEquals(EditSessionCloseOutcome.COMMITTED, result.outcome());
        assertEquals(EditSessionState.CLOSED, session.state());
        assertEquals(List.of(Boolean.FALSE), fixture.host.endEditCancelFlags);
        assertEquals(1, fixture.host.refreshCount);
        assertFalse(fixture.lock.engaged);
        assertFalse(fixture.editScopeGate.get());
    }

    @Test
    void closeFailureAbortsTheEditAndReportsFailure() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();
        fixture.host.failNextEndEdit = true;

        final EditSessionCloseResult result = session.close();

        assertEquals(EditSessionCloseOutcome.FAILED, result.outcome());
        // The commit attempt (false) followed by the abort attempt (true).
        assertEquals(List.of(Boolean.FALSE, Boolean.TRUE), fixture.host.endEditCancelFlags);
        assertEquals(EditSessionState.CLOSED, session.state());
        assertFalse(fixture.lock.engaged);
        assertFalse(fixture.editScopeGate.get());
    }

    @Test
    void workAfterCancelFailsAsCancelled() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();
        session.cancel();

        final EditCancelledException cancelled = assertThrows(
            EditCancelledException.class,
            () -> session.log("late")
        );
        assertEquals(CancelSource.PLUGIN, cancelled.source());
        assertThrows(EditCancelledException.class, () -> session.progress(0.5));
        assertThrows(EditCancelledException.class, session::cancel);
        assertThrows(EditCancelledException.class, session::close);
        assertThrows(
            EditCancelledException.class,
            () -> session.parameterKeys().addParameterKey(null)
        );
    }

    @Test
    void workAfterCloseFailsAsUnavailable() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();
        session.close();

        assertThrows(EditUnavailableException.class, () -> session.log("late"));
        assertThrows(EditUnavailableException.class, session::cancel);
        assertThrows(EditUnavailableException.class, session::close);
    }

    @Test
    void cancelButtonOnTheDialogCancelsAsUserAndNotifiesTheListener() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final AtomicReference<CancelSource> notified = new AtomicReference<>();
        final EditSessionListener listener = (session, source) -> notified.set(source);
        final EditSession session = fixture.manager.open(
            fixture.host.binding,
            DOCUMENT,
            EditSessionOptions.defaults().withUndoCancelListener(listener)
        );

        fixture.lock.context.cancelRequest().run();

        assertEquals(EditSessionState.CANCELLED, session.state());
        assertEquals(Optional.of(CancelSource.USER), session.cancelledBy());
        assertEquals(CancelSource.USER, notified.get());
        assertEquals(1, fixture.host.endEditCancelFlags.size());
    }

    @Test
    void pluginCancelDoesNotFireTheUndoCancelListener() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final AtomicReference<CancelSource> notified = new AtomicReference<>();
        final EditSession session = fixture.manager.open(
            fixture.host.binding,
            DOCUMENT,
            EditSessionOptions.defaults().withUndoCancelListener(
                (cancelled, source) -> notified.set(source))
        );

        session.cancel();

        assertEquals(Optional.of(CancelSource.PLUGIN), session.cancelledBy());
        assertNull(notified.get());
    }

    @Test
    void forceCancelForCancelsOnlyTheOwningPluginsSession() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        fixture.manager.forceCancelFor("plugin.other", CancelSource.HOST);
        assertTrue(session.isOpen());

        fixture.manager.forceCancelFor("plugin.test", CancelSource.HOST);
        assertEquals(EditSessionState.CANCELLED, session.state());
        assertEquals(Optional.of(CancelSource.HOST), session.cancelledBy());
        assertFalse(fixture.editScopeGate.get());
    }

    @Test
    void staleBindingForceCancelsAndThenReportsCancelled() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();
        fixture.host.current = false;

        final EditCancelledException cancelled = assertThrows(
            EditCancelledException.class,
            () -> session.log("work")
        );
        assertEquals(CancelSource.HOST, cancelled.source());
        assertEquals(EditSessionState.CANCELLED, session.state());
        assertFalse(fixture.editScopeGate.get());
    }

    @Test
    void staleSessionCancelFromSdkStillReportsCancelled() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();
        fixture.host.current = false;

        final EditCancelledException cancelled = assertThrows(
            EditCancelledException.class,
            session::cancel
        );
        assertEquals(CancelSource.HOST, cancelled.source());
        assertEquals(EditSessionState.CANCELLED, session.state());
    }

    @Test
    void logAndProgressReachTheUiLock() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        session.log("hello");
        session.progress(0.4);

        assertEquals(List.of("hello"), fixture.lock.logs);
        assertEquals(List.of(0.4), fixture.lock.progressValues);
    }

    @Test
    void openSessionReportsUnavailableOperationFamilies() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.open();

        // Phase 2: families are gated but still unavailable — a typed failure, never NPE.
        assertThrows(
            EditUnavailableException.class,
            () -> session.selection().selectedObjects()
        );
        assertNotNull(session.parameterStructure());
        assertNotNull(session.selection());
        assertNotNull(session.partObjects());
        assertNotNull(session.deformers());
    }

    @Test
    void transactionIsRejectedWhileASessionHoldsTheScopeGate() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditorAuthoringTransactionCoordinator coordinator =
            new EditorAuthoringTransactionCoordinator(
                new FakeCoordinatorHost(fixture.host.binding),
                fixture.editScopeGate
            );
        fixture.open();

        final var result = coordinator.execute(
            fixture.host.binding,
            dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions.of("t"),
            () -> null
        );

        assertEquals(
            dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome.REJECTED_SCOPE,
            result.outcome()
        );
    }

    @Test
    void sessionIsRejectedWhileATransactionHoldsTheScopeGate() {
        final Fixture fixture = new Fixture();
        final EditorAuthoringTransactionCoordinator coordinator =
            new EditorAuthoringTransactionCoordinator(
                new FakeCoordinatorHost(fixture.host.binding),
                fixture.editScopeGate
            );

        coordinator.execute(
            fixture.host.binding,
            dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions.of("t"),
            () -> {
                assertThrows(
                    EditUnavailableException.class,
                    () -> fixture.manager.open(
                        fixture.host.binding, DOCUMENT, EditSessionOptions.defaults())
                );
                assertEquals(0, fixture.host.beginCount);
                return null;
            }
        );
    }

    @Test
    void allHostWorkRunsInsideDispatch() throws EditSessionException {
        final Fixture fixture = new Fixture();
        fixture.host.failOutsideDispatch = true;

        final EditSession session = fixture.open();
        session.log("x");
        session.cancel();

        assertEquals(EditSessionState.CANCELLED, session.state());
    }

    private static final class Fixture {
        final AtomicBoolean editScopeGate = new AtomicBoolean();
        final FakeEditSessionHost host = new FakeEditSessionHost();
        final RecordingUiLock lock = new RecordingUiLock();
        final EditSessionUiLockFactory lockFactory = context -> {
            lock.context = context;
            return lock;
        };
        boolean failLock;
        final RuntimeEditSessionManager manager = new RuntimeEditSessionManager(
            host,
            editScopeGate,
            context -> {
                if (failLock) {
                    throw new IllegalStateException("no dialogs");
                }
                return lockFactory.create(context);
            },
            EditSessionRecoveries.ALWAYS_COMPENSATING
        );

        EditSession open() throws EditSessionException {
            return manager.open(host.binding, DOCUMENT, EditSessionOptions.defaults());
        }
    }

    /** Recording UI lock; the cancel request is reachable through {@code context}. */
    private static final class RecordingUiLock implements EditSessionUiLock {
        EditSessionUiLockContext context;
        boolean engaged;
        final List<String> logs = new ArrayList<>();
        final List<Double> progressValues = new ArrayList<>();

        @Override
        public void engage(final boolean silent) {
            engaged = true;
        }

        @Override
        public void log(final String message) {
            logs.add(message);
        }

        @Override
        public void progress(final double value) {
            progressValues.add(value);
        }

        @Override
        public void disengage() {
            engaged = false;
        }
    }

    /**
     * Fake session host: every native member records its invocation and asserts it was reached
     * through {@link #dispatch}, standing in for host-thread confinement.
     */
    private static final class FakeEditSessionHost implements EditorEditSessionHost {
        final EditorAuthoringTransactionCoordinator.Binding binding =
            new EditorAuthoringTransactionCoordinator.Binding(
                "plugin.test",
                "document-1",
                1,
                "model-1",
                1,
                Thread.currentThread()
            );
        final List<String> calls = new ArrayList<>();
        final List<String> dispatchLabels = new ArrayList<>();
        final List<Boolean> endEditCancelFlags = new ArrayList<>();
        final Object editToken = new Object();
        HistorySnapshot history = new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE, 1, 7, 0, List.of(), false, false);
        boolean current = true;
        boolean admitted = true;
        boolean revertVerified;
        boolean failOutsideDispatch;
        boolean failRevert;
        boolean failNextEndEdit;
        boolean historyChangedOnRecovery;
        int beginCount;
        int revertCount;
        int refreshCount;
        private int dispatchDepth;

        @Override
        public Optional<EditorAuthoringTransactionCoordinator.Binding> currentBinding(
            final String pluginId
        ) {
            return Optional.of(binding);
        }

        @Override
        public boolean isCurrent(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return current;
        }

        @Override
        public boolean admits(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return admitted;
        }

        @Override
        public HistorySnapshot history(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            onHost("history");
            if (historyChangedOnRecovery) {
                return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE, 1, 9, 0, List.of(), false, false);
            }
            return history;
        }

        @Override
        public Object beginEdit(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final String label
        ) {
            onHost("beginEdit:" + label);
            beginCount++;
            return editToken;
        }

        @Override
        public void endEdit(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit,
            final boolean cancel
        ) {
            onHost("endEdit:" + cancel);
            endEditCancelFlags.add(cancel);
            if (failNextEndEdit) {
                failNextEndEdit = false;
                throw new IllegalStateException("endEdit failed");
            }
        }

        @Override
        public boolean undoRevertVerified(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return revertVerified;
        }

        @Override
        public void revert(final EditorAuthoringTransactionCoordinator.Binding expected) {
            onHost("revert");
            revertCount++;
            if (failRevert) {
                throw new IllegalStateException("revert failed");
            }
        }

        @Override
        public Optional<Object> mainWindow(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return Optional.of(new Object());
        }

        @Override
        public void refreshAfterSession(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            onHost("refresh");
            refreshCount++;
        }

        @Override
        public <T> T dispatch(final String label, final HostTask<T> task)
            throws EditSessionException {
            dispatchLabels.add(label);
            dispatchDepth++;
            try {
                return task.run();
            } finally {
                dispatchDepth--;
            }
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code + ":fake";
        }

        private void onHost(final String call) {
            if (failOutsideDispatch && dispatchDepth == 0) {
                throw new IllegalStateException("host member reached outside dispatch: " + call);
            }
            calls.add(call);
        }
    }

    /** Minimal coordinator host so mutual-exclusion tests exercise the real coordinator. */
    private static final class FakeCoordinatorHost
        implements EditorAuthoringTransactionCoordinator.Host {

        private final EditorAuthoringTransactionCoordinator.Binding binding;
        private final Object edit = new Object();

        FakeCoordinatorHost(final EditorAuthoringTransactionCoordinator.Binding binding) {
            this.binding = binding;
        }

        @Override
        public boolean isCurrent(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return binding.documentIdentity().equals(expected.documentIdentity())
                && binding.documentGeneration() == expected.documentGeneration()
                && binding.modelIdentity().equals(expected.modelIdentity())
                && binding.modelGeneration() == expected.modelGeneration();
        }

        @Override
        public HistorySnapshot history(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return new HistorySnapshot(
                HistorySnapshot.Availability.AVAILABLE, 1, 1, 0, List.of(), false, false);
        }

        @Override
        public Object beginEdit(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final String label
        ) {
            return edit;
        }

        @Override
        public void endEdit(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit,
            final boolean abort
        ) {
        }

        @Override
        public void refresh(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final java.util.Set<dev.turboism.adapter.cubism.editor.transaction
                .EditorRefreshRequirement> requirements
        ) {
        }

        @Override
        public Optional<String> committedHistoryEntryId(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final HistorySnapshot before,
            final HistorySnapshot after,
            final String transactionId,
            final String label
        ) {
            return Optional.empty();
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code + ":fake";
        }
    }
}
