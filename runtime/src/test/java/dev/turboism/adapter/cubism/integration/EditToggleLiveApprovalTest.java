package dev.turboism.adapter.cubism.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.turboism.sdk.cubism.id.DocumentId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol-matrix tests for the 051 approval migration (US1/US3): the native 「编辑」
 * checkbox state is the live approval source — checked grants registered connections edit
 * approval immediately, unchecked denies, and a flip takes effect on the very next request
 * in both directions because a live-state gate is re-consulted instead of latched.
 *
 * <p>Every frame is sent through {@link EditProtocolBridge#onMessage} over the fake engine;
 * the approval gate is the production {@link EditToggleApprovalGate} driven by an
 * {@link EditToggleState} the test flips directly (the injected checkbox's item listener
 * does the same on the EDT).</p>
 */
class EditToggleLiveApprovalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> sent = new ArrayList<>();
    private final Object socketA = new Object();
    private final FakeEditEngine.Service service = new FakeEditEngine.Service(
        FakeEditEngine.FakeSession::new);
    private final AtomicReference<Optional<DocumentId>> document =
        new AtomicReference<>(Optional.of(EditFixtures.DOCUMENT));
    private final EditToggleState toggle = new EditToggleState();
    private EditProtocolBridge bridge;

    @BeforeEach
    void wire() {
        final EditBridgeEnvironment env = FakeEditEngine.env(
            FakeEditEngine.registered(), service,
            document::get, new EditToggleApprovalGate(toggle));
        bridge = FakeEditEngine.bridge(sent, env);
    }

    private JsonNode request(final Object socket, final String method, final String data)
        throws Exception {
        final int before = sent.size();
        final boolean claimed = bridge.onMessage(
            "{\"Version\":\"1.1.0\",\"Timestamp\":1,\"RequestId\":\"r\","
                + "\"Type\":\"Request\",\"Method\":\"" + method + "\",\"Data\":" + data + "}",
            socket);
        assertTrue(claimed, method + " must be intercepted");
        assertEquals(before + 1, sent.size(), method + " must be answered once");
        return JSON.readTree(sent.get(sent.size() - 1));
    }

    private JsonNode response(final Object socket, final String method, final String data)
        throws Exception {
        final JsonNode frame = request(socket, method, data);
        assertEquals("Response", frame.get("Type").asText(),
            method + " must answer a Response frame: " + frame);
        return frame.get("Data");
    }

    private String errorType(final Object socket, final String method, final String data)
        throws Exception {
        final JsonNode frame = request(socket, method, data);
        assertEquals("Error", frame.get("Type").asText(),
            method + " must answer an Error frame: " + frame);
        return frame.get("Data").get("ErrorType").asText();
    }

    @Test
    void uncheckedCheckboxDeniesApprovalAndEditMethods() throws Exception {
        assertFalse(response(socketA, "GetIsEditApproval", "{}")
            .get("Result").asBoolean());
        assertEquals("InvalidEditOperation",
            errorType(socketA, "EditBegin", "{}"));
        assertEquals(0, service.openCalls, "no engine call may happen while denied");
    }

    @Test
    void checkedCheckboxGrantsImmediatelyWithoutAGatedCallFirst() throws Exception {
        toggle.setEnabled(true);

        // US1: the checkbox IS the grant — GetIsEditApproval answers it live on a
        // connection that never resolved an approval decision.
        assertTrue(response(socketA, "GetIsEditApproval", "{}")
            .get("Result").asBoolean());
        assertTrue(response(socketA, "EditBegin", "{}")
            .get("Result").asBoolean());
        assertEquals(1, service.openCalls);
    }

    @Test
    void uncheckingRevokesApprovalOnTheNextRequest() throws Exception {
        toggle.setEnabled(true);
        assertTrue(response(socketA, "EditBegin", "{}").get("Result").asBoolean());

        toggle.setEnabled(false);

        // Instant flip: the previously granted connection is denied without a latch.
        assertFalse(response(socketA, "GetIsEditApproval", "{}")
            .get("Result").asBoolean());
        assertEquals("InvalidEditOperation",
            errorType(socketA, "EditParameter", "{\"ParameterId\":\"p\",\"Value\":1}"));
    }

    @Test
    void denialIsNotLatchedSoRecheckingGrantsAgain() throws Exception {
        assertEquals("InvalidEditOperation",
            errorType(socketA, "EditBegin", "{}"));

        toggle.setEnabled(true);

        assertTrue(response(socketA, "GetIsEditApproval", "{}")
            .get("Result").asBoolean());
        assertTrue(response(socketA, "EditBegin", "{}").get("Result").asBoolean());
    }

    @Test
    void engineAdmissionStillConjunctsTheToggle() throws Exception {
        service.approved = false;
        toggle.setEnabled(true);

        // Checkbox on but the engine does not admit the binding: the read reports false,
        // matching the 050 granted && admitted conjunction.
        assertFalse(response(socketA, "GetIsEditApproval", "{}")
            .get("Result").asBoolean());
    }

    @Test
    void unregisteredConnectionsAreRejectedRegardlessOfTheToggle() throws Exception {
        toggle.setEnabled(true);
        final EditProtocolBridge cold = FakeEditEngine.bridge(sent,
            FakeEditEngine.env(FakeEditEngine.unregistered(), service,
                document::get, new EditToggleApprovalGate(toggle)));

        final int before = sent.size();
        cold.onMessage(
            "{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":\"EditBegin\",\"Data\":{}}",
            socketA);
        assertEquals("PluginNotRegistered",
            JSON.readTree(sent.get(before)).get("Data").get("ErrorType").asText());
        assertEquals(0, service.openCalls);
    }
}
