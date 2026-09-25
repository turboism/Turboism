package dev.turboism.adapter.cubism.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.turboism.sdk.cubism.edit.CancelSource;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditSessionOptions;
import dev.turboism.sdk.cubism.id.DocumentId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol-matrix tests over the fake engine (spec-050 R2/R3/R5).
 *
 * <p>Every frame is sent through {@link EditProtocolBridge#onMessage} with an injectable
 * environment: registration, approval, session service and active document are all faked, so
 * the tests exercise gate order, payload validation, session delegation, id bridging and
 * stale failure modes without any host class.</p>
 */
class EditApiRouterMatrixTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> sent = new ArrayList<>();
    private final Object socketA = new Object();
    private final Object socketB = new Object();
    private final FakeEditEngine.Service service = new FakeEditEngine.Service(
        FakeEditEngine.FakeSession::new);
    private final AtomicReference<Optional<DocumentId>> document =
        new AtomicReference<>(Optional.of(EditFixtures.DOCUMENT));
    private EditBridgeEnvironment env;
    private EditProtocolBridge bridge;

    @BeforeEach
    void wire() {
        env = FakeEditEngine.env(FakeEditEngine.registered(), service,
            document::get, FakeEditEngine.approveAll());
        bridge = FakeEditEngine.bridge(sent, env);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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

    private String modelData() {
        return "{\"ModelUID\":\"" + EditFixtures.MODEL_UID + "\"}";
    }

    private JsonNode begin(final Object socket) throws Exception {
        return response(socket, "EditBegin", "{}");
    }

    // ------------------------------------------------------------------
    // R2 — negative paths
    // ------------------------------------------------------------------

    @Test
    void unregisteredConnectionsAreRejectedBeforeAnyGate() throws Exception {
        final EditProtocolBridge cold = FakeEditEngine.bridge(sent,
            FakeEditEngine.env(FakeEditEngine.unregistered(), service,
                document::get, FakeEditEngine.approveAll()));
        final boolean claimed = cold.onMessage(
            "{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":\"EditBegin\",\"Data\":{}}",
            socketA);
        assertTrue(claimed);
        assertEquals("PluginNotRegistered",
            JSON.readTree(sent.get(0)).get("Data").get("ErrorType").asText());
        assertEquals(0, service.openCalls, "no engine call may happen for unregistered");
    }

    @Test
    void aSocketWithoutAHostRecordIsUnregisteredToo() throws Exception {
        final EditProtocolBridge cold = FakeEditEngine.bridge(sent,
            FakeEditEngine.env(EditConnectionInspector.unavailable(), service,
                document::get, FakeEditEngine.approveAll()));
        assertTrue(cold.onMessage(
            "{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":\"GetIsEditApproval\","
                + "\"Data\":{}}", socketA));
        assertEquals("PluginNotRegistered",
            JSON.readTree(sent.get(0)).get("Data").get("ErrorType").asText());
    }

    @Test
    void deniedApprovalFailsGatedMethodsAndLatches() throws Exception {
        final EditProtocolBridge denied = FakeEditEngine.bridge(sent,
            FakeEditEngine.env(FakeEditEngine.registered(), service,
                document::get, FakeEditEngine.denyAll()));
        assertEquals("InvalidEditOperation",
            errorOn(denied, socketA, "EditBegin", "{}"));
        assertEquals("InvalidEditOperation",
            errorOn(denied, socketA, "GetDeformerStructure", modelData()),
            "the denial is latched for the connection");
        // Reads outside the approval gate still run.
        assertEquals("Response",
            JSON.readTree(requestFrame(denied, socketA, "GetPartStructure", modelData()))
                .get("Type").asText());
    }

    private String errorOn(
        final EditProtocolBridge target,
        final Object socket,
        final String method,
        final String data
    ) throws Exception {
        target.onMessage(
            "{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":\"" + method
                + "\",\"Data\":" + data + "}", socket);
        final JsonNode frame = JSON.readTree(sent.get(sent.size() - 1));
        return frame.get("Data").get("ErrorType").asText();
    }

    private String requestFrame(
        final EditProtocolBridge target,
        final Object socket,
        final String method,
        final String data
    ) {
        target.onMessage(
            "{\"Version\":\"1.1.0\",\"Type\":\"Request\",\"Method\":\"" + method
                + "\",\"Data\":" + data + "}", socket);
        return sent.get(sent.size() - 1);
    }

    @Test
    void aNonOwnerSocketCannotDriveTheSession() throws Exception {
        begin(socketA);
        assertEquals("InvalidEditOperation",
            errorType(socketB, "EditSendLog", "{\"Message\":\"x\"}"),
            "e()-gated methods refuse a foreign socket");
        // But a second EditBegin on the other socket is answered Result:false, not an error.
        assertFalse(response(socketB, "EditBegin", "{}").get("Result").asBoolean());
    }

    @Test
    void sessionGatedWritesRequireAnOpenSession() throws Exception {
        for (final String method : List.of(
            "AddParameterKey", "DeleteParameterKey", "MoveParameterKey",
            "AddParameter", "AddParameterGroup", "EditParameter", "EditParameterGroup",
            "DeleteParameter", "DeleteParameterGroup", "MoveParameter", "MoveParameterGroup",
            "AddSelectedObjects", "DeleteObject", "MoveObjectOnPartsPalette", "AddPart",
            "EditPart", "EditArtMesh", "EditGlue", "AddRotationDeformer", "AddWarpDeformer",
            "EditRotationDeformer", "EditWarpDeformer", "GetObjectsByParameterKeys",
            "EditSendLog", "EditSendProgress")) {
            assertEquals("InvalidEditOperation",
                errorType(socketA, method, "{}"), method + " without a session");
        }
    }

    @Test
    void malformedPayloadsFailWithInvalidData() throws Exception {
        begin(socketA);
        // AddParameterKey requires ObjectId/ParameterId/KeyValue.
        assertEquals("InvalidData", errorType(socketA, "AddParameterKey", modelData()));
        assertEquals("InvalidData",
            errorType(socketA, "AddParameterKey",
                "{\"ModelUID\":\"" + EditFixtures.MODEL_UID + "\",\"ObjectId\":\"part-1\","
                    + "\"ParameterId\":\"ParamAngle\",\"KeyValue\":\"oops\"}"));
        // Non-object Data fails before field validation.
        assertEquals("InvalidData", errorType(socketA, "EditEnd", "42"));
        // EditSendProgress out-of-range value surfaces as InvalidData via the record guard.
        assertEquals("InvalidData",
            errorType(socketA, "EditSendProgress", "{\"Value\":2.5}"));
    }

    @Test
    void unknownObjectIdsFailWithInvalidModel() throws Exception {
        begin(socketA);
        assertEquals("InvalidModel",
            errorType(socketA, "GetObject",
                "{\"ModelUID\":\"" + EditFixtures.MODEL_UID + "\",\"Id\":\"nope\"}"));
        assertEquals("InvalidModel",
            errorType(socketA, "DeleteObject",
                "{\"ModelUID\":\"" + EditFixtures.MODEL_UID + "\",\"Id\":\"nope\"}"));
    }

    @Test
    void aMissingActiveDocumentFailsWithInvalidDocument() throws Exception {
        document.set(Optional.empty());
        assertEquals("InvalidDocument",
            errorType(socketA, "GetPartStructure", modelData()));
        // EditBegin without a document answers Result:false, not an error.
        assertFalse(response(socketA, "EditBegin", "{}").get("Result").asBoolean());
    }

    // ------------------------------------------------------------------
    // R3 — official lifecycle over the fake engine
    // ------------------------------------------------------------------

    @Test
    void getIsEditApprovalReflectsTheApprovalLatch() throws Exception {
        // Undecided: false without touching the gate.
        assertFalse(response(socketA, "GetIsEditApproval", "{}").get("Result").asBoolean());
        // A gated method resolves approval through the gate; the read then reports it.
        begin(socketA);
        assertTrue(response(socketA, "GetIsEditApproval", "{}").get("Result").asBoolean());
    }

    @Test
    void editBeginOpensAnEngineSessionWithTheOptions() throws Exception {
        final JsonNode data = response(socketA, "EditBegin", "{\"Silent\":true}");
        assertTrue(data.get("Result").asBoolean());
        assertEquals(1, service.openCalls);
        assertEquals(EditFixtures.DOCUMENT, service.lastDocument);
        assertTrue(service.lastOptions.silent());
        assertTrue(service.lastOptions.undoCancelListener().isPresent(),
            "the undo-cancel subscription channel is attached at open");
        // A second begin on the owning socket is idempotent.
        assertTrue(response(socketA, "EditBegin", "{}").get("Result").asBoolean());
        assertEquals(1, service.openCalls);
    }

    @Test
    void editEndCommitsOrCancelsTheSession() throws Exception {
        begin(socketA);
        final FakeEditEngine.FakeSession first = service.sessions.get(0);
        assertTrue(response(socketA, "EditEnd", "{}").get("Result").asBoolean());
        assertEquals(1, first.closeCalls);
        assertEquals(0, first.cancelCalls);

        begin(socketA);
        final FakeEditEngine.FakeSession second = service.sessions.get(1);
        assertTrue(response(socketA, "EditEnd", "{\"Cancel\":true}").get("Result").asBoolean());
        assertEquals(1, second.cancelCalls);
        assertEquals(0, second.closeCalls);

        // No session: Result:false.
        assertFalse(response(socketA, "EditEnd", "{}").get("Result").asBoolean());
    }

    @Test
    void sendLogAndProgressReachTheSession() throws Exception {
        begin(socketA);
        final FakeEditEngine.FakeSession session = service.sessions.get(0);
        response(socketA, "EditSendLog", "{\"Message\":\"half way\"}");
        response(socketA, "EditSendProgress", "{\"Value\":0.5}");
        assertEquals(List.of("half way"), session.logs);
        assertEquals(List.of(0.5), session.progresses);
    }

    @Test
    void notifyUndoCancelSubscribesAndReceivesTheEvent() throws Exception {
        begin(socketA);
        final FakeEditEngine.FakeSession session = service.sessions.get(0);
        assertTrue(response(socketA, "NotifyUndoCancel", "{\"Enabled\":true}")
            .get("Accepted").asBoolean());

        session.fireUndoCancel(CancelSource.USER);
        assertEquals(3, sent.size(), "the event is pushed on the subscribed socket");
        final JsonNode event = JSON.readTree(sent.get(2));
        assertEquals("Event", event.get("Type").asText());
        assertEquals("NotifyUndoCancel", event.get("Method").asText());
        assertTrue(event.get("Data").get("Result").asBoolean());

        // The dead session is dropped: later session methods fail typed.
        assertEquals("InvalidEditOperation",
            errorType(socketA, "EditSendLog", "{\"Message\":\"late\"}"));
    }

    @Test
    void unsubscribedConnectionsReceiveNoEvent() throws Exception {
        begin(socketA);
        final FakeEditEngine.FakeSession session = service.sessions.get(0);
        session.fireUndoCancel(CancelSource.USER);
        assertEquals(1, sent.size(), "no subscription means no push");
    }

    @Test
    void writeOperationsDelegateWithMappedFields() throws Exception {
        begin(socketA);
        final FakeEditEngine.FakeSession session = service.sessions.get(0);

        final JsonNode addKey = response(socketA, "AddParameterKey",
            "{\"ModelUID\":\"" + EditFixtures.MODEL_UID + "\",\"ObjectId\":\"part-1\","
                + "\"ParameterId\":\"ParamAngle\",\"KeyValue\":0.5}");
        assertTrue(addKey.get("Result").asBoolean());
        final var keyRequest =
            (dev.turboism.sdk.cubism.edit.ParameterKeyOps.AddParameterKey)
                session.keys.lastRequest;
        assertEquals("ParamAngle", keyRequest.parameter().value());
        assertEquals(0.5, keyRequest.keyValue());
        assertEquals("part-1", keyRequest.object().id());
        assertEquals(dev.turboism.sdk.cubism.model.ModelObjectKind.PART,
            keyRequest.object().kind(), "the object kind is resolved from the model trees");

        final JsonNode addParam = response(socketA, "AddParameter",
            "{\"ModelUID\":\"" + EditFixtures.MODEL_UID + "\",\"Name\":\"New\","
                + "\"Min\":0.0,\"Max\":1.0,\"Default\":0.0}");
        assertTrue(addParam.get("Result").asBoolean());
        assertNotNull(session.structure.lastRequest);

        assertTrue(response(socketA, "AddSelectedObjects",
            "{\"ModelUID\":\"" + EditFixtures.MODEL_UID + "\",\"Ids\":[\"mesh-1\"]}")
            .get("Result").asBoolean());
        assertNotNull(session.selection.lastRequest);
    }

    @Test
    void readsInsideASessionUseTheOwnedSession() throws Exception {
        begin(socketA);
        response(socketA, "GetParameterStructure", modelData());
        assertEquals(1, service.openCalls, "no transient session is opened while owned");
    }

    @Test
    void sessionlessReadsRunOnATransientSession() throws Exception {
        final JsonNode data = response(socketA, "GetParameterStructure", modelData());
        assertTrue(data.get("ParameterStructure").has("Entries"));
        assertEquals(1, service.openCalls, "the read ran inside a transient session");
        final FakeEditEngine.FakeSession transientSession = service.sessions.get(0);
        assertEquals(1, transientSession.cancelCalls,
            "the transient session is rolled back, never committed");
        assertFalse(transientSession.isOpen());
        assertTrue(service.lastOptions.silent(),
            "transient reads request the silent dialog");
    }

    @Test
    void getParameterStructureReturnsTheOfficialTreeShape() throws Exception {
        final JsonNode data = response(socketA, "GetParameterStructure", modelData());
        final JsonNode root = data.get("ParameterStructure");
        assertEquals("Root", root.get("Name").asText());
        assertEquals("pg-root", root.get("Id").asText());
        final JsonNode entry = root.get("Entries").get(0);
        assertEquals("Parameter", entry.get("EntryType").asText());
        assertEquals("ParamAngle", entry.get("Id").asText());
        assertEquals(-30.0, entry.get("Min").asDouble());
        assertFalse(entry.get("IsRepeat").asBoolean());
    }

    @Test
    void getObjectReturnsTheTypedSnapshot() throws Exception {
        final JsonNode data = response(socketA, "GetObject",
            "{\"ModelUID\":\"" + EditFixtures.MODEL_UID + "\",\"Id\":\"part-1\"}");
        assertTrue(data.get("Result").asBoolean());
        assertEquals("Part", data.get("Type").asText());
        assertEquals("PartA", data.get("Data").get("Name").asText());
        assertEquals(500, data.get("Data").get("DrawOrder").asInt());
    }

    @Test
    void getParameterKeysReturnsTheKeyValueRows() throws Exception {
        begin(socketA);
        final FakeEditEngine.FakeSession session = service.sessions.get(0);
        session.keys.keysResult = List.of(
            new dev.turboism.sdk.cubism.edit.ParameterKeyOps.ParameterKeyValues(
                new dev.turboism.sdk.cubism.id.ParameterId("ParamAngle"),
                List.of(-30.0, 0.0, 30.0)));
        final JsonNode data = response(socketA, "GetParameterKeys",
            "{\"ModelUID\":\"" + EditFixtures.MODEL_UID + "\",\"ObjectId\":\"part-1\"}");
        assertEquals("ParamAngle", data.get("Parameters").get(0).get("Id").asText());
        assertEquals(3, data.get("Parameters").get(0).get("KeyValues").size());
    }

    @Test
    void allThirtySixMethodsAnswerOverTheOfficialLifecycle() throws Exception {
        // The official order: approve, begin, operate, end — every method must reach a
        // Response frame (the engine fakes return canned successes; this asserts the whole
        // dispatch table is wired, not that the host accepted each mutation).
        final String uid = "\"ModelUID\":\"" + EditFixtures.MODEL_UID + "\"";
        final java.util.LinkedHashMap<String, String> payloads = new java.util.LinkedHashMap<>();
        payloads.put("GetIsEditApproval", "{}");
        payloads.put("EditBegin", "{\"Silent\":true}");
        payloads.put("EditSendLog", "{\"Message\":\"log-line\"}");
        payloads.put("EditSendProgress", "{\"Value\":0.25}");
        payloads.put("NotifyUndoCancel", "{\"Enabled\":true}");
        payloads.put("AddParameterKey", "{" + uid
            + ",\"ObjectId\":\"part-1\",\"ParameterId\":\"ParamAngle\",\"KeyValue\":0.5}");
        payloads.put("DeleteParameterKey", "{" + uid + ",\"ObjectId\":\"part-1\"}");
        payloads.put("MoveParameterKey", "{" + uid
            + ",\"ObjectId\":\"part-1\",\"FromValue\":0.0,\"ToValue\":1.0}");
        payloads.put("GetParameterKeys", "{" + uid + ",\"ObjectId\":\"part-1\"}");
        payloads.put("GetObjectsByParameterKeys", "{" + uid
            + ",\"ParameterId\":\"ParamAngle\",\"KeyValue\":0.5}");
        payloads.put("GetParameterStructure", "{" + uid + "}");
        payloads.put("AddParameter", "{" + uid + ",\"Name\":\"NewParam\"}");
        payloads.put("AddParameterGroup", "{" + uid + ",\"Name\":\"NewGroup\"}");
        payloads.put("EditParameter", "{" + uid + ",\"Id\":\"ParamAngle\",\"Name\":\"Renamed\"}");
        payloads.put("EditParameterGroup", "{" + uid + ",\"Id\":\"pg-root\",\"Name\":\"Renamed\"}");
        payloads.put("DeleteParameter", "{" + uid + ",\"Id\":\"ParamToDelete\"}");
        payloads.put("DeleteParameterGroup", "{" + uid + ",\"Id\":\"pg-to-delete\"}");
        payloads.put("MoveParameter", "{" + uid
            + ",\"Id\":\"ParamAngle\",\"GroupId\":\"pg-root\"}");
        payloads.put("MoveParameterGroup", "{" + uid
            + ",\"Id\":\"pg-root\",\"InsertIndex\":0}");
        payloads.put("GetSelectedObjects", "{" + uid + "}");
        payloads.put("AddSelectedObjects", "{" + uid + ",\"Ids\":[\"mesh-1\"]}");
        payloads.put("ClearSelectedObjects", "{" + uid + "}");
        payloads.put("GetPartStructure", "{" + uid + "}");
        payloads.put("GetObject", "{" + uid + ",\"Id\":\"part-1\"}");
        payloads.put("DeleteObject", "{" + uid + ",\"Id\":\"mesh-1\"}");
        payloads.put("MoveObjectOnPartsPalette", "{" + uid + ",\"Id\":\"mesh-1\"}");
        payloads.put("AddPart", "{" + uid + ",\"Name\":\"NewPart\",\"Ids\":[\"mesh-1\"]}");
        payloads.put("EditPart", "{" + uid + ",\"Id\":\"part-1\",\"Name\":\"Renamed\"}");
        payloads.put("EditArtMesh", "{" + uid + ",\"Id\":\"mesh-1\",\"Opacity\":0.5}");
        payloads.put("EditGlue", "{" + uid + ",\"Id\":\"glue-1\",\"Intensity\":0.5}");
        payloads.put("GetDeformerStructure", "{" + uid + "}");
        payloads.put("AddRotationDeformer", "{" + uid
            + ",\"TargetObjectIds\":[\"mesh-1\"],\"Mode\":\"AsParent\"}");
        payloads.put("AddWarpDeformer", "{" + uid
            + ",\"TargetObjectIds\":[\"mesh-1\"],\"Mode\":\"AsChild\",\"WarpDivH\":3}");
        payloads.put("EditRotationDeformer", "{" + uid
            + ",\"Id\":\"rot-1\",\"Angle\":45.0}");
        payloads.put("EditWarpDeformer", "{" + uid
            + ",\"Id\":\"warp-1\",\"WarpDivH\":4}");
        payloads.put("EditEnd", "{}");
        assertEquals(EditApiMethods.ALL.size(), payloads.size(),
            "the matrix must cover every recognized method exactly once");
        assertTrue(EditApiMethods.ALL.containsAll(payloads.keySet()));

        for (final var entry : payloads.entrySet()) {
            final JsonNode data = response(socketA, entry.getKey(), entry.getValue());
            assertNotNull(data, entry.getKey() + " must carry a Data body");
        }
        assertEquals(1, service.openCalls,
            "GetIsEditApproval opens nothing and every in-session read reuses the owned "
                + "session — exactly one engine open for the whole lifecycle");
    }

    // ------------------------------------------------------------------
    // R5 — stale external ids
    // ------------------------------------------------------------------

    @Test
    void aModelUidBoundToASwitchedAwayDocumentIsStale() throws Exception {
        response(socketA, "GetPartStructure", modelData());
        document.set(Optional.of(new DocumentId("doc-2")));
        assertEquals("InvalidDocument",
            errorType(socketA, "GetPartStructure", modelData()),
            "a bound uid must not follow the document switch");
        // The tombstone persists: the same uid never silently rebinds.
        assertEquals("InvalidDocument",
            errorType(socketA, "GetPartStructure", modelData()));
    }

    @Test
    void aNewUidBindsToTheCurrentDocument() throws Exception {
        response(socketA, "GetPartStructure", modelData());
        document.set(Optional.of(new DocumentId("doc-2")));
        final JsonNode data = response(socketA, "GetPartStructure",
            "{\"ModelUID\":\"fresh-uid\"}");
        assertTrue(data.has("PartStructure"), "a first-seen uid binds to the live document");
    }

    @Test
    void sessionStateIsPerConnection() throws Exception {
        begin(socketA);
        // socketB can still run sessionless reads while socketA owns the session.
        final JsonNode data = response(socketB, "GetPartStructure", modelData());
        assertTrue(data.has("PartStructure"));
        // And socketA's session stays owned.
        assertEquals("Response",
            JSON.readTree(requestFrame(bridge, socketA, "EditSendLog",
                "{\"Message\":\"still mine\"}")).get("Type").asText());
    }
}
