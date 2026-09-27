package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EditSessionServiceContractTest {

    private static final String[] SUPPORTED = {"5.2.03", "5.3.02", "5.3.03"};

    private final PluginContext context = (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
        PluginContext.class.getClassLoader(),
        new Class<?>[]{PluginContext.class},
        (proxy, method, args) -> null
    );

    @Test
    void cubismFacadeExposesTheEditSessionService() throws Exception {
        final Method method = CubismFacade.class.getMethod("edit");

        assertEquals(EditSessionService.class, method.getReturnType());
        assertTrue(method.isDefault());
        assertArrayEquals(SUPPORTED, method.getAnnotation(CubismEditor.class).value());
    }

    @Test
    void serviceAndSessionDeclareExactSupportedEditorVersions() {
        assertArrayEquals(
            SUPPORTED, EditSessionService.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(
            SUPPORTED, EditSession.class.getAnnotation(CubismEditor.class).value());
    }

    @Test
    void unavailableServiceFailsClosedOnEveryEntryPoint() throws EditSessionException {
        final EditSessionService service = EditSessionService.unavailable();

        assertThrows(NullPointerException.class,
            () -> service.open(null, new DocumentId("doc"), EditSessionOptions.defaults()));
        assertThrows(NullPointerException.class,
            () -> service.open(context, null, EditSessionOptions.defaults()));
        assertThrows(NullPointerException.class,
            () -> service.open(context, new DocumentId("doc"), null));
        assertThrows(NullPointerException.class, () -> service.isEditApproved(null));

        final EditUnavailableException approval = assertThrows(EditUnavailableException.class,
            () -> service.isEditApproved(context));
        assertEquals(EditUnavailableException.CODE, approval.code());

        final EditSession session = service.open(
            context, new DocumentId("doc"), EditSessionOptions.defaults());
        assertFalse(session.isOpen());
        assertSame(EditSession.unavailable(), session);
    }

    @Test
    void unavailableSessionFailsClosedOnEveryMember() {
        final EditSession session = EditSession.unavailable();

        assertFalse(session.isOpen());
        assertUnavailable(() -> session.document());
        assertUnavailable(() -> session.state());
        assertUnavailable(() -> session.openResult());
        assertUnavailable(() -> session.cancelledBy());
        assertThrows(NullPointerException.class, () -> session.log(null));
        assertUnavailable(() -> session.log("line"));
        assertUnavailable(() -> session.progress(0.5));
        assertUnavailable(() -> session.cancel());
        assertUnavailable(() -> session.close());
    }

    @Test
    void unavailableSessionHandsOutFailClosedOperationFamilies() {
        final EditSession session = EditSession.unavailable();

        assertSame(ParameterKeyOps.unavailable(), session.parameterKeys());
        assertSame(ParameterStructureOps.unavailable(), session.parameterStructure());
        assertSame(SelectionOps.unavailable(), session.selection());
        assertSame(PartObjectOps.unavailable(), session.partObjects());
        assertSame(DeformerOps.unavailable(), session.deformers());
    }

    @Test
    void editExceptionsAreTypedCubismFailures() {
        assertTrue(EditSessionException.class.getSuperclass() == CubismServiceException.class
            || CubismServiceException.class.isAssignableFrom(EditSessionException.class));
        assertThrows(EditSessionException.class, () -> {
            throw new EditSessionException("cubism.edit.test", "failure");
        });

        final EditCancelledException cancelled = new EditCancelledException(CancelSource.USER);
        assertEquals(EditCancelledException.CODE, cancelled.code());
        assertEquals(CancelSource.USER, cancelled.source());

        final EditUnavailableException unavailable = new EditUnavailableException("Some.op");
        assertEquals(EditUnavailableException.CODE, unavailable.code());
        assertTrue(unavailable.getMessage().contains("Some.op"));
    }

    @Test
    void closeResultInvariantsMatchTerminalOutcomes() {
        final EditSessionCloseResult committed = EditSessionCloseResult.committed();
        assertEquals(EditSessionCloseOutcome.COMMITTED, committed.outcome());
        assertTrue(committed.cancelSource().isEmpty());
        assertTrue(committed.diagnosticId().isEmpty());

        final EditSessionCloseResult cancelled = EditSessionCloseResult.cancelled(CancelSource.HOST);
        assertEquals(EditSessionCloseOutcome.CANCELLED, cancelled.outcome());
        assertEquals(Optional.of(CancelSource.HOST), cancelled.cancelSource());

        final EditSessionCloseResult failed = EditSessionCloseResult.failed("cubism.edit.close.failed");
        assertEquals(EditSessionCloseOutcome.FAILED, failed.outcome());
        assertEquals(Optional.of("cubism.edit.close.failed"), failed.diagnosticId());

        assertThrows(IllegalArgumentException.class, () -> new EditSessionCloseResult(
            EditSessionCloseOutcome.COMMITTED, Optional.of(CancelSource.USER), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new EditSessionCloseResult(
            EditSessionCloseOutcome.CANCELLED, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new EditSessionCloseResult(
            EditSessionCloseOutcome.FAILED, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new EditSessionCloseResult(
            EditSessionCloseOutcome.FAILED, Optional.empty(), Optional.of("  ")));
    }

    @Test
    void optionsCarrySilentFlagAndUndoCancelListener() {
        final EditSessionOptions defaults = EditSessionOptions.defaults();
        assertFalse(defaults.silent());
        assertTrue(defaults.undoCancelListener().isEmpty());

        assertTrue(EditSessionOptions.silentDialog().silent());

        final AtomicBoolean invoked = new AtomicBoolean();
        final EditSessionListener listener = (session, source) -> invoked.set(true);
        final EditSessionOptions withListener = defaults.withUndoCancelListener(listener);
        assertEquals(Optional.of(listener), withListener.undoCancelListener());
        assertThrows(NullPointerException.class, () -> defaults.withUndoCancelListener(null));
        assertThrows(NullPointerException.class, () -> new EditSessionOptions(false, null));
    }

    @Test
    void openResultCarriesAdmissionEvidence() {
        final DocumentId document = new DocumentId("doc-1");
        final EditSessionOptions options = EditSessionOptions.defaults();
        final EditSessionOpenResult result = new EditSessionOpenResult(document, options);

        assertEquals(document, result.document());
        assertEquals(options, result.options());
        assertThrows(NullPointerException.class, () -> new EditSessionOpenResult(null, options));
        assertThrows(NullPointerException.class, () -> new EditSessionOpenResult(document, null));
    }

    private static void assertUnavailable(final ThrowingCall call) {
        final EditUnavailableException failure = assertThrows(
            EditUnavailableException.class, call::run);
        assertEquals(EditUnavailableException.CODE, failure.code());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws EditSessionException;
    }
}
