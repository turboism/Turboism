package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.cubism.CubismEditorApiUnavailableException;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class McpOptionalTransactionTest {
    @Test
    void exactVersionAbsenceProducesTypedUnavailableWithoutRunningMutation() {
        final var failure = new CubismEditorApiUnavailableException(
                "dev.turboism.sdk.cubism.CubismFacade#authoringTransactions()",
                Optional.of("5.0.02"),
                List.of("5.2.03"));
        final var service = McpHttpServer.optionalAuthoringTransactions(facade(failure));
        assertSame(AuthoringTransactionService.unavailable(), service);
        final var result = service.execute(AuthoringTransactionOptions.of("legacy"), () -> {
            throw new AssertionError("unavailable backend ran mutation");
        });
        assertEquals("UNAVAILABLE", result.outcome().name());
    }

    @Test
    void permissionOrUnexpectedBackendFailureIsNotHidden() {
        for (RuntimeException failure :
                List.of(new SecurityException("permission denied"), new IllegalStateException("backend failed"))) {
            assertSame(
                    failure,
                    assertThrows(
                            failure.getClass(), () -> McpHttpServer.optionalAuthoringTransactions(facade(failure))));
        }
    }

    @Test
    void unadmittedHistoryCapabilityDegradesToUnavailableSentinel() {
        final var failure = new CubismEditorApiUnavailableException(
                "dev.turboism.sdk.cubism.CubismFacade#history()", Optional.empty(), List.of("5.2.00"));
        assertSame(CubismHistory.unavailable(), McpHttpServer.optionalHistory(facadeThrowing("history", failure)));
    }

    @Test
    void unexpectedHistoryFailureIsNotHidden() {
        final var failure = new IllegalStateException("backend failed");
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> McpHttpServer.optionalHistory(facadeThrowing("history", failure))));
    }

    static CubismFacade facade(RuntimeException failure) {
        return facadeThrowing("authoringTransactions", failure);
    }

    static CubismFacade facadeThrowing(final String gatedMethod, final RuntimeException failure) {
        return (CubismFacade) Proxy.newProxyInstance(
                CubismFacade.class.getClassLoader(), new Class<?>[] {CubismFacade.class}, (proxy, method, args) -> {
                    if (gatedMethod.equals(method.getName())) throw failure;
                    throw new AssertionError("unexpected facade call " + method);
                });
    }
}
