package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.sdk.cubism.edit.EditSession;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditSessionOptions;
import dev.turboism.sdk.cubism.edit.EditSessionState;
import dev.turboism.sdk.cubism.edit.EditSessionService;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeEditSessionServiceTest {

    private static final DocumentId DOCUMENT = new DocumentId("document-1");
    private static final PluginContext CONTEXT = (PluginContext)
        java.lang.reflect.Proxy.newProxyInstance(
            RuntimeEditSessionServiceTest.class.getClassLoader(),
            new Class<?>[]{PluginContext.class},
            (proxy, method, args) -> null);

    @Test
    void isEditApprovedDelegatesToHostAdmission() throws EditSessionException {
        final Fixture fixture = new Fixture();
        assertTrue(fixture.service.isEditApproved(CONTEXT));

        fixture.host.admitted = false;
        assertFalse(fixture.service.isEditApproved(CONTEXT));
    }

    @Test
    void isEditApprovedFailsClosedWhenNoBindingExists() {
        final Fixture fixture = new Fixture();
        fixture.host.binding = Optional.empty();

        assertThrows(
            EditUnavailableException.class,
            () -> fixture.service.isEditApproved(CONTEXT)
        );
    }

    @Test
    void openRejectsADocumentThatIsNotActive() {
        final Fixture fixture = new Fixture();

        assertThrows(
            EditUnavailableException.class,
            () -> fixture.service.open(
                CONTEXT, new DocumentId("document-2"), EditSessionOptions.defaults())
        );
    }

    @Test
    void openFailsClosedWhenNoDocumentIsActive() {
        final Fixture fixture = new Fixture();
        fixture.activeDocument = Optional.empty();

        assertThrows(
            EditUnavailableException.class,
            () -> fixture.service.open(CONTEXT, DOCUMENT, EditSessionOptions.defaults())
        );
    }

    @Test
    void openValidatesArgumentsBeforeTouchingTheHost() {
        final Fixture fixture = new Fixture();

        assertThrows(
            NullPointerException.class,
            () -> fixture.service.open(null, DOCUMENT, EditSessionOptions.defaults())
        );
        assertThrows(
            NullPointerException.class,
            () -> fixture.service.open(CONTEXT, null, EditSessionOptions.defaults())
        );
        assertThrows(
            NullPointerException.class,
            () -> fixture.service.open(CONTEXT, DOCUMENT, null)
        );
    }

    @Test
    void openAdmitsTheActiveDocument() throws EditSessionException {
        final Fixture fixture = new Fixture();

        final EditSession session = fixture.service.open(
            CONTEXT, DOCUMENT, EditSessionOptions.defaults());

        assertTrue(session.isOpen());
        assertEquals(DOCUMENT, session.document());
        assertEquals("plugin.test", fixture.host.binding().pluginId());
    }

    @Test
    void shutdownForceCancelsThePluginsSession() throws EditSessionException {
        final Fixture fixture = new Fixture();
        final EditSession session = fixture.service.open(
            CONTEXT, DOCUMENT, EditSessionOptions.defaults());

        fixture.service.shutdown();

        assertEquals(EditSessionState.CANCELLED, session.state());
    }

    @Test
    void shutdownIsANoOpWithoutAnActiveSession() {
        final Fixture fixture = new Fixture();
        fixture.service.shutdown();
    }

    private static final class Fixture {
        final AtomicBoolean gate = new AtomicBoolean();
        final StubHost host = new StubHost();
        Optional<DocumentId> activeDocument = Optional.of(DOCUMENT);
        final RuntimeEditSessionManager manager = new RuntimeEditSessionManager(
            host,
            gate,
            context -> new EditSessionUiLock() {
                @Override public void engage(final boolean silent) { }
                @Override public void log(final String message) { }
                @Override public void progress(final double value) { }
                @Override public void disengage() { }
            },
            EditSessionRecoveries.ALWAYS_COMPENSATING
        );
        final RuntimeEditSessionService service = new RuntimeEditSessionService(
            manager, host, "plugin.test", () -> activeDocument);
    }

    private static final class StubHost implements EditorEditSessionHost {
        Optional<EditorAuthoringTransactionCoordinator.Binding> binding = Optional.of(
            new EditorAuthoringTransactionCoordinator.Binding(
                "plugin.test", "document-1", 1, "model-1", 1, Thread.currentThread()));
        boolean admitted = true;
        private final HistorySnapshot history = new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE, 1, 1, 0, java.util.List.of(), false, false);

        EditorAuthoringTransactionCoordinator.Binding binding() {
            return binding.orElseThrow();
        }

        @Override
        public Optional<EditorAuthoringTransactionCoordinator.Binding> currentBinding(
            final String pluginId
        ) {
            return binding;
        }

        @Override
        public boolean isCurrent(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return true;
        }

        @Override
        public boolean admits(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return admitted;
        }

        @Override
        public HistorySnapshot history(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return history;
        }

        @Override
        public Object beginEdit(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final String label
        ) {
            return new Object();
        }

        @Override
        public void endEdit(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit,
            final boolean cancel
        ) {
        }

        @Override
        public void undoEditGroup(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit
        ) {
        }

        @Override
        public boolean undoRevertVerified(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return false;
        }

        @Override
        public void revert(final EditorAuthoringTransactionCoordinator.Binding expected) {
        }

        @Override
        public Optional<Object> mainWindow(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return Optional.empty();
        }

        @Override
        public void refreshAfterSession(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
        }

        @Override
        public <T> T dispatch(final String label, final HostTask<T> task)
            throws EditSessionException {
            return task.run();
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code;
        }
    }
}
