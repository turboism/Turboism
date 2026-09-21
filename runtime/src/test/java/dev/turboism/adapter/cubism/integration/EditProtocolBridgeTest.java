package dev.turboism.adapter.cubism.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fake-WebSocket contract tests for {@link EditProtocolBridge}.
 *
 * <p>The fake socket is a plain {@link Object}: the bridge treats it as opaque and replies
 * through the injectable {@link EditSocketWriter}, which is exactly how the real deployment
 * reaches {@code org.java_websocket.WebSocket.send(String)} without linking the type.</p>
 */
class EditProtocolBridgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> sent = new ArrayList<>();
    private final EditProtocolBridge bridge =
        new EditProtocolBridge((socket, text) -> sent.add(text));
    private final Object socket = new Object();

    @AfterEach
    void clearKillSwitch() {
        System.clearProperty(EditProtocolBridge.ENABLED_PROPERTY);
    }

    @Test
    void anEditMethodIsClaimedAndAnsweredWithAnOfficialShapedFrame() throws Exception {
        final boolean claimed = bridge.onMessage(
            "{\"Version\":\"1.1.0\",\"Timestamp\":1,\"RequestId\":\"r-1\","
                + "\"Type\":\"Request\",\"Method\":\"EditBegin\",\"Data\":{}}",
            socket);

        assertTrue(claimed, "an editing method must be intercepted");
        assertEquals(EditProtocolBridge.Outcome.INTERCEPTED_PLACEHOLDER, bridge.lastOutcome());
        assertEquals(1, sent.size());

        final JsonNode frame = JSON.readTree(sent.get(0));
        assertEquals("1.1.0", frame.get("Version").asText());
        assertTrue(frame.get("Timestamp").isNumber(), "response timestamp is emitted");
        assertEquals("r-1", frame.get("RequestId").asText());
        assertEquals("Error", frame.get("Type").asText());
        assertEquals("EditBegin", frame.get("Method").asText());
        assertEquals("InvalidEditOperation", frame.get("Data").get("ErrorType").asText());
    }

    @Test
    void everyRecognizedMethodIsClaimed() {
        for (final String method : EditApiMethods.ALL) {
            sent.clear();
            assertTrue(bridge.onMessage(
                "{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":\"" + method + "\"}",
                socket), method + " must be intercepted");
            assertEquals(1, sent.size(), method + " must be answered");
        }
        assertEquals(36, EditApiMethods.ALL.size(),
            "the recognition set is exactly the 36 documented editing methods");
    }

    @Test
    void nativeMethodsAndUnknownMethodsFallThrough() {
        for (final String method : List.of(
            "RegisterPlugin", "GetIsApproval", "GetParameters", "SetParameterValues",
            "NotifyPhysicsFileExported", "GetCurrentEditMode", "GetAPIVersion",
            "TotallyUnknownMethod")) {
            assertFalse(bridge.onMessage(
                "{\"Version\":\"1.0.0\",\"Type\":\"Request\",\"Method\":\"" + method + "\"}",
                socket), method + " must stay native");
        }
        assertTrue(sent.isEmpty(), "a passthrough must never write a response");
        assertEquals(EditProtocolBridge.Outcome.PASSTHROUGH_UNKNOWN_METHOD,
            bridge.lastOutcome());
    }

    @Test
    void malformedAndNonObjectMessagesFallThrough() {
        for (final String raw : List.of(
            "not json", "{broken", "[]", "[1,2]", "\"text\"", "null", "", "42")) {
            assertFalse(bridge.onMessage(raw, socket), "must stay native: " + raw);
        }
        assertTrue(sent.isEmpty());
    }

    @Test
    void aMessageWithoutAMethodNameFallsThrough() {
        assertFalse(bridge.onMessage("{\"Version\":\"1.1.0\",\"Type\":\"Request\"}", socket));
        assertFalse(bridge.onMessage(
            "{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":42}", socket));
        assertFalse(bridge.onMessage(
            "{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":null}", socket));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aSufficientlyRecognizedMethodWithAnInsufficientVersionGetsTheVersionError()
        throws Exception {
        final boolean claimed = bridge.onMessage(
            "{\"Version\":\"1.0.0\",\"Type\":\"Request\",\"Method\":\"EditBegin\"}", socket);

        assertTrue(claimed);
        assertEquals(EditProtocolBridge.Outcome.INTERCEPTED_UNSUPPORTED_VERSION,
            bridge.lastOutcome());
        final JsonNode frame = JSON.readTree(sent.get(0));
        assertEquals("1.0.0", frame.get("Version").asText(),
            "the offending version is echoed like the host responder does");
        assertEquals("Error", frame.get("Type").asText());
        assertEquals("EditBegin", frame.get("Method").asText());
        assertEquals("UnsupportedVersion", frame.get("Data").get("ErrorType").asText());
    }

    @Test
    void aMissingOrMalformedVersionIsInsufficientToo() throws Exception {
        assertTrue(bridge.onMessage(
            "{\"Type\":\"Request\",\"Method\":\"EditBegin\"}", socket));
        assertEquals(EditProtocolBridge.Outcome.INTERCEPTED_UNSUPPORTED_VERSION,
            bridge.lastOutcome());
        assertTrue(bridge.onMessage(
            "{\"Version\":\"abc\",\"Type\":\"Request\",\"Method\":\"EditBegin\"}", socket));
        assertEquals(EditProtocolBridge.Outcome.INTERCEPTED_UNSUPPORTED_VERSION,
            bridge.lastOutcome());
        // A two-component version parses as 1.1.0 -> sufficient, placeholder path.
        assertTrue(bridge.onMessage(
            "{\"Version\":\"1.1\",\"Type\":\"Request\",\"Method\":\"EditBegin\"}", socket));
        assertEquals(EditProtocolBridge.Outcome.INTERCEPTED_PLACEHOLDER,
            bridge.lastOutcome());
        assertEquals(3, sent.size());
    }

    @Test
    void aNonRequestTypeIsAnsweredWithInvalidType() throws Exception {
        assertTrue(bridge.onMessage(
            "{\"Version\":\"1.1.0\",\"Type\":\"Event\",\"Method\":\"EditBegin\"}", socket));
        assertEquals(EditProtocolBridge.Outcome.INTERCEPTED_INVALID_TYPE, bridge.lastOutcome());
        final JsonNode frame = JSON.readTree(sent.get(0));
        assertEquals("InvalidType", frame.get("Data").get("ErrorType").asText());
    }

    @Test
    void theKillSwitchPassesEverythingThrough() {
        System.setProperty(EditProtocolBridge.ENABLED_PROPERTY, "false");
        try {
            assertFalse(bridge.onMessage(
                "{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":\"EditBegin\"}",
                socket));
            assertEquals(EditProtocolBridge.Outcome.PASSTHROUGH_DISABLED,
                bridge.lastOutcome());
            assertTrue(sent.isEmpty());
        } finally {
            System.clearProperty(EditProtocolBridge.ENABLED_PROPERTY);
        }
        assertTrue(bridge.onMessage(
            "{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":\"EditBegin\"}", socket));
    }

    @Test
    void aFailingSocketWriteStillClaimsTheMessage() {
        final EditProtocolBridge failing = new EditProtocolBridge((socket, text) -> {
            throw new IllegalStateException("socket closed");
        });
        assertTrue(failing.onMessage(
            "{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":\"EditBegin\"}", socket));
        assertEquals(EditProtocolBridge.Outcome.INTERCEPTED_SEND_FAILED,
            failing.lastOutcome());
        assertEquals(1, failing.interceptedCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReceiverAdaptsIntoTheLoaderNeutralContract() {
        final Object receiver = bridge.receiver();
        assertEquals(Boolean.FALSE,
            ((java.util.function.BiFunction<Object, Object, Object>) receiver)
                .apply("not json", socket));
        assertEquals(Boolean.TRUE,
            ((java.util.function.BiFunction<Object, Object, Object>) receiver)
                .apply("{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":\"EditEnd\"}",
                    socket));
        assertEquals(Boolean.FALSE,
            ((java.util.function.BiFunction<Object, Object, Object>) receiver)
                .apply(42, socket), "a non-String first argument must pass through");
    }

    @Test
    void theSerializedFrameMatchesTheHostRecipeByteShape() {
        assertTrue(bridge.onMessage(
            "{\"Version\":\"1.1.0\",\"RequestId\":7,\"Type\":\"Request\","
                + "\"Method\":\"GetPartStructure\"}",
            socket));
        final String frame = sent.get(0);
        assertTrue(frame.startsWith("{ \"Version\" : \"1.1.0\", \"Timestamp\" : "),
            "field order and spacing follow the host concat recipe: " + frame);
        assertTrue(frame.contains(", \"RequestId\" : 7, \"Type\" : \"Error\", "
            + "\"Method\" : \"GetPartStructure\", \"Data\" : { \"ErrorType\" : "
            + "\"InvalidEditOperation\"}}"), "raw Data fragment and closing brace: " + frame);
        assertTrue(frame.endsWith("}"));
    }

    @Test
    void countersTrackBothPaths() {
        bridge.onMessage("{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":\"EditEnd\"}",
            socket);
        bridge.onMessage("{\"Version\":\"1.0.0\",\"Type\":\"Request\",\"Method\":\"GetParameters\"}",
            socket);
        assertEquals(1, bridge.interceptedCount());
        assertEquals(1, bridge.passedThroughCount());
    }
}
