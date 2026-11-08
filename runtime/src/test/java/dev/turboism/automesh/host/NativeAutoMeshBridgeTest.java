package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.plugin.Registration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Contract of the loader-neutral guard bridge: fail-open answers, diagnostics and re-entrancy.
 *
 * <p>The property keys are frozen literals because transformed host bytecode embeds them. A rename
 * would silently disable the guard, so the exact strings are asserted here.</p>
 */
class NativeAutoMeshBridgeTest {

    private Registration bridge;

    @AfterEach
    void closeBridge() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }

    @Test
    void callbackKeysAreTheFrozenPropertyLiterals() {
        assertEquals(
                "turboism.automesh.guard.modeling-mode",
                NativeAutoMeshBridge.callbackKey(AutoMeshFunnel.MODELING_MODE));
        assertEquals(
                "turboism.automesh.guard.mesh-editor-mode",
                NativeAutoMeshBridge.callbackKey(AutoMeshFunnel.MESH_EDITOR_MODE));
        assertEquals("turboism.automesh.guard.", NativeAutoMeshBridge.PROPERTY_PREFIX);
    }

    @Test
    void handledConfirmationAnswersTrue() {
        bridge = NativeAutoMeshBridge.install((funnel, arguments) -> Boolean.TRUE, message -> {});
        assertEquals(
                Boolean.TRUE, invokeCallback(AutoMeshFunnel.MODELING_MODE, argumentsOf(AutoMeshFunnel.MODELING_MODE)));
    }

    @Test
    void unhandledAndNullAnswersAreNotHandled() {
        final AtomicInteger calls = new AtomicInteger();
        bridge = NativeAutoMeshBridge.install(
                (funnel, arguments) -> {
                    calls.incrementAndGet();
                    return null;
                },
                message -> {});
        assertEquals(
                Boolean.FALSE,
                invokeCallback(AutoMeshFunnel.MESH_EDITOR_MODE, argumentsOf(AutoMeshFunnel.MESH_EDITOR_MODE)));
        assertEquals(1, calls.get(), "a null answer must still have reached the policy");
    }

    @Test
    void uninstalledBridgeAnswersNotHandled() {
        bridge = NativeAutoMeshBridge.install((funnel, arguments) -> Boolean.TRUE, message -> {});
        bridge.close();
        bridge = null;
        assertNull(
                System.getProperties().get(NativeAutoMeshBridge.callbackKey(AutoMeshFunnel.MODELING_MODE)),
                "closing the bridge must remove every callback property, so the native algorithm runs");
        assertNull(
                System.getProperties().get(NativeAutoMeshBridge.callbackKey(AutoMeshFunnel.MESH_EDITOR_MODE)),
                "closing the bridge must remove the mesh-editor callback too");
    }

    @Test
    void throwingPolicyAnswersNotHandledAndReportsOneDiagnostic() {
        final List<String> diagnostics = new ArrayList<>();
        bridge = NativeAutoMeshBridge.install(
                (funnel, arguments) -> {
                    throw new IllegalStateException("algorithm blew up");
                },
                diagnostics::add);

        assertEquals(
                Boolean.FALSE,
                invokeCallback(AutoMeshFunnel.MODELING_MODE, argumentsOf(AutoMeshFunnel.MODELING_MODE)),
                "a plugin failure must never block the native algorithm");
        assertEquals(1, diagnostics.size(), "the failure must be reported once");
        assertTrue(diagnostics.get(0).contains("algorithm blew up"), diagnostics.get(0));
    }

    @Test
    void throwingDiagnosticSinkDoesNotChangeTheAnswer() {
        bridge = NativeAutoMeshBridge.install(
                (funnel, arguments) -> {
                    throw new IllegalStateException("algorithm blew up");
                },
                message -> {
                    throw new IllegalStateException("logging failed");
                });
        assertEquals(
                Boolean.FALSE, invokeCallback(AutoMeshFunnel.MODELING_MODE, argumentsOf(AutoMeshFunnel.MODELING_MODE)));
    }

    @Test
    void unexpectedArgumentAnswersNotHandled() {
        final List<String> diagnostics = new ArrayList<>();
        bridge = NativeAutoMeshBridge.install((funnel, arguments) -> Boolean.TRUE, diagnostics::add);
        assertEquals(
                Boolean.FALSE,
                callback().apply("not an argument array"),
                "a value that is not the funnel argument array must not be handled");
        assertEquals(
                Boolean.FALSE,
                callback().apply(new Object[] {new Object()}),
                "an argument array without a known funnel token must not be handled");
        assertEquals(2, diagnostics.size(), "both refusals must be reported: " + diagnostics);
    }

    @Test
    void nestedCallbackIsAnsweredWithoutReEnteringThePolicy() {
        final AtomicInteger policyCalls = new AtomicInteger();
        final List<String> diagnostics = new ArrayList<>();
        bridge = NativeAutoMeshBridge.install(
                (funnel, arguments) -> {
                    if (policyCalls.incrementAndGet() == 1) {
                        assertEquals(
                                Boolean.FALSE,
                                callback().apply(argumentsOf(funnel)),
                                "the nested confirmation must be answered not handled");
                    }
                    return Boolean.TRUE;
                },
                diagnostics::add);

        assertEquals(
                Boolean.TRUE, invokeCallback(AutoMeshFunnel.MODELING_MODE, argumentsOf(AutoMeshFunnel.MODELING_MODE)));
        assertEquals(1, policyCalls.get(), "the nested confirmation must not reach the policy");
        assertTrue(diagnostics.stream().anyMatch(message -> message.contains("nested")), diagnostics.toString());
    }

    @Test
    void installIsExclusiveAndReinstallableAfterClose() {
        bridge = NativeAutoMeshBridge.install((funnel, arguments) -> Boolean.TRUE, message -> {});
        assertThrows(
                IllegalStateException.class,
                () -> NativeAutoMeshBridge.install((funnel, arguments) -> Boolean.TRUE, message -> {}),
                "one runtime policy may own the guard");
        bridge.close();
        bridge = NativeAutoMeshBridge.install((funnel, arguments) -> Boolean.FALSE, message -> {});
        assertEquals(
                Boolean.FALSE,
                invokeCallback(AutoMeshFunnel.MESH_EDITOR_MODE, argumentsOf(AutoMeshFunnel.MESH_EDITOR_MODE)));
    }

    @Test
    void closeIsIdempotent() {
        bridge = NativeAutoMeshBridge.install((funnel, arguments) -> Boolean.TRUE, message -> {});
        bridge.close();
        bridge.close();
        final Properties properties = System.getProperties();
        assertFalse(
                properties.containsKey(NativeAutoMeshBridge.callbackKey(AutoMeshFunnel.MODELING_MODE)),
                "a repeated close must leave the property removed");
        bridge = null;
    }

    private static Object invokeCallback(final AutoMeshFunnel funnel, final Object[] arguments) {
        final Object answer = callback().apply(arguments == null ? new Object[0] : arguments);
        assertNotNull(answer, "the callback must always answer");
        return answer;
    }

    @SuppressWarnings("unchecked")
    private static Function<Object, Object> callback() {
        final Object callback =
                System.getProperties().get(NativeAutoMeshBridge.callbackKey(AutoMeshFunnel.MODELING_MODE));
        assertNotNull(callback, "the bridge must publish the modeling-mode callback");
        return (Function<Object, Object>) callback;
    }

    private static Object[] argumentsOf(final AutoMeshFunnel funnel) {
        return new Object[] {funnel.token(), new Object()};
    }
}
