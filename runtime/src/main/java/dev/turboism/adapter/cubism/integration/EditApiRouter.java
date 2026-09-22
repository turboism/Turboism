package dev.turboism.adapter.cubism.integration;

import com.fasterxml.jackson.databind.JsonNode;

import dev.turboism.sdk.cubism.edit.DeformerOps;
import dev.turboism.sdk.cubism.edit.EditDeformerAttachMode;
import dev.turboism.sdk.cubism.edit.EditObjectKind;
import dev.turboism.sdk.cubism.edit.EditObjectNode;
import dev.turboism.sdk.cubism.edit.EditParameterKeyCondition;
import dev.turboism.sdk.cubism.edit.EditSession;
import dev.turboism.sdk.cubism.edit.EditSessionCloseOutcome;
import dev.turboism.sdk.cubism.edit.EditSessionCloseResult;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditSessionOptions;
import dev.turboism.sdk.cubism.edit.ParameterKeyOps;
import dev.turboism.sdk.cubism.edit.ParameterStructureOps;
import dev.turboism.sdk.cubism.edit.PartObjectOps;
import dev.turboism.sdk.cubism.edit.SelectionOps;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.permission.CubismPermissionException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * The 36-method editing router: per-method gating in the official dispatch order plus engine
 * delegation to the Phase-046 session surface.
 *
 * <p>Gate order mirrors the official dispatcher ({@code api-internal-routes.json}
 * conventions): registration precheck first (every editing method carries {@code c()=true} —
 * a completed native {@code RegisterPlugin} on this connection), then protocol version and
 * {@code Type="Request"}, then edit approval ({@code d()}), then session ownership
 * ({@code e()} — the request must arrive on the socket that opened the session). The
 * descriptive {@code b()} flag is never enforced, matching the alpha2 finding that its
 * handler list is populated but unread.</p>
 *
 * <p>Several official reads carry {@code d()=false, e()=false} — they are served on a
 * registered connection without an edit session. The 046 engine exposes its operation
 * families only inside an admitted {@link EditSession}, so such a read arriving while this
 * connection owns no session runs inside a transient silent session cancelled immediately
 * afterwards: no history entry is recorded and the model is untouched, matching the official
 * observable behaviour.</p>
 *
 * <p>{@code ModelUID} strings are external identities the client learned from the native
 * {@code GetCurrentModelUID} (a 1.0.x method the bridge does not intercept). The bridge cannot
 * read that uid through verified members, so the first sighting of a uid is bound to the
 * document active at that moment; a bound uid whose document is no longer active fails
 * {@code InvalidDocument} and is never re-pointed (US5 stale semantics).</p>
 */
final class EditApiRouter {

    private final EditBridgeEnvironment env;
    private final EditSocketWriter writer;
    private final Map<Object, EditConnectionState> states =
        java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, Route> routes = new HashMap<>();

    /** The single bridge-global engine session owner (the engine admits one per binding). */
    private EditConnectionState sessionOwner;

    EditApiRouter(final EditBridgeEnvironment env, final EditSocketWriter writer) {
        this.env = Objects.requireNonNull(env, "env");
        this.writer = Objects.requireNonNull(writer, "writer");
        registerRoutes();
    }

    // ------------------------------------------------------------------
    // dispatch
    // ------------------------------------------------------------------

    /**
     * Routes one recognized editing request. The envelope is strictly parsed and its method
     * name is in {@link EditApiMethods#ALL}; every failure is a typed {@link EditApiFailure}.
     *
     * @return the response {@code Data} fragment to serialize
     */
    JsonNode dispatch(
        final EditApiEnvelope envelope,
        final Object socket
    ) throws EditApiFailure {
        final EditConnectionInfo info = env.inspector().inspect(socket).orElse(null);
        if (info == null || !info.registered()) {
            throw new EditApiFailure(EditApiErrorCode.PLUGIN_NOT_REGISTERED);
        }
        final EditApiVersion version = EditApiVersion.parse(envelope.version());
        if (version == null || !version.atLeast(EditApiVersion.EDIT_API_MINIMUM)) {
            throw new EditApiFailure(EditApiErrorCode.UNSUPPORTED_VERSION);
        }
        if (!"Request".equals(envelope.type())) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_TYPE);
        }
        final Route route = routes.get(envelope.method());
        if (route == null) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_EDIT_OPERATION);
        }
        final EditConnectionState state = stateFor(socket);
        state.negotiatedVersion(version);
        if (route.approval) {
            requireApproval(state, info);
        }
        try {
            return switch (route.access) {
                case REGISTERED_ONLY -> route.handler.handle(
                    new Exchange(envelope, state, info, null));
                case SESSION_OWNER -> route.handler.handle(
                    new Exchange(envelope, state, info, requireOwnedSession(state)));
                case SESSION_READ -> withReadSession(state, session ->
                    route.handler.handle(new Exchange(envelope, state, info, session)));
            };
        } catch (EditApiFailure failure) {
            throw failure;
        } catch (NoSuchElementException absent) {
            throw new EditApiFailure(route.missingIdError);
        } catch (IllegalArgumentException malformed) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        } catch (EditSessionException engineRefusal) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_EDIT_OPERATION);
        } catch (CubismPermissionException permission) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_EDIT_OPERATION);
        } catch (RuntimeException unexpected) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_EDIT_OPERATION);
        }
    }

    private EditConnectionState stateFor(final Object socket) {
        synchronized (states) {
            return states.computeIfAbsent(socket, EditConnectionState::new);
        }
    }

    /** {@return live per-connection state view; tests only} */
    Map<Object, EditConnectionState> states() {
        return states;
    }

    // ------------------------------------------------------------------
    // gates
    // ------------------------------------------------------------------

    /**
     * The {@code d()} gate: the connection must hold Turboism edit approval. An undecided
     * connection is resolved through the approval gate — the production implementation shows
     * the approval dialog on this first gated request (US4); denial latches and fails the
     * request with the editing-operation error the official dispatcher emits for unapproved
     * edit calls.
     */
    private void requireApproval(
        final EditConnectionState state,
        final EditConnectionInfo info
    ) throws EditApiFailure {
        if (state.approval() == EditConnectionState.Approval.APPROVED) {
            return;
        }
        if (state.approval() == EditConnectionState.Approval.DENIED) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_EDIT_OPERATION);
        }
        final boolean granted;
        try {
            granted = env.approvalGate().requestApproval(info);
        } catch (RuntimeException failure) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_EDIT_OPERATION);
        }
        state.approval(granted
            ? EditConnectionState.Approval.APPROVED
            : EditConnectionState.Approval.DENIED);
        if (!granted) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_EDIT_OPERATION);
        }
    }

    /** The {@code e()} gate: the request must arrive on the socket owning the live session. */
    private EditSession requireOwnedSession(
        final EditConnectionState state
    ) throws EditApiFailure {
        final EditSession session = state.session();
        if (session == null || !session.isOpen() || sessionOwner != state) {
            if (sessionOwner == state) {
                sessionOwner = null;
                state.session(null, "");
            }
            throw new EditApiFailure(EditApiErrorCode.INVALID_EDIT_OPERATION);
        }
        return session;
    }

    /**
     * Runs a sessionless-capable read: on the owned open session when one exists, otherwise on
     * a transient silent session cancelled immediately after the read — the engine requires
     * an admitted session for every operation family while the official protocol does not.
     */
    private JsonNode withReadSession(
        final EditConnectionState state,
        final SessionWork work
    ) throws EditApiFailure, EditSessionException {
        final EditSession owned = state.session();
        if (owned != null && owned.isOpen() && sessionOwner == state) {
            return work.run(owned);
        }
        if (owned != null) {
            state.session(null, "");
            if (sessionOwner == state) {
                sessionOwner = null;
            }
        }
        final EditSession transientSession = env.editSessions().open(
            env.pluginContext(), activeDocument(), EditSessionOptions.silentDialog());
        try {
            return work.run(transientSession);
        } finally {
            try {
                if (transientSession.isOpen()) {
                    transientSession.cancel();
                }
            } catch (RuntimeException ignored) {
                // A transient read that cannot roll back is best-effort; the engine owns the
                // session lifecycle and force-cancels on staleness.
            }
        }
    }

    @FunctionalInterface
    private interface SessionWork {
        JsonNode run(EditSession session) throws EditApiFailure, EditSessionException;
    }

    private void onUndoCancelled(
        final EditConnectionState state,
        final dev.turboism.sdk.cubism.edit.CancelSource source
    ) {
        // The engine session ended host-side; drop the ownership link so later requests fail
        // typed instead of touching a dead session, and notify subscribers.
        if (sessionOwner == state) {
            sessionOwner = null;
            state.session(null, "");
        }
        if (state.undoCancelSubscribed()) {
            sendEvent(state);
        }
    }

    /** Pushes the official {@code NotifyUndoCancel} event frame on the subscribed socket. */
    private void sendEvent(final EditConnectionState state) {
        final Object socket = state.socket();
        if (socket == null) {
            return;
        }
        final EditApiVersion version = state.negotiatedVersion();
        final String frame = EditApiResponses.event(
            version == null ? EditApiVersion.EDIT_API_MINIMUM.toString() : version.toString(),
            "NotifyUndoCancel",
            EditApiWire.result(true).toString(),
            System.currentTimeMillis());
        try {
            writer.send(socket, frame);
        } catch (Throwable ignored) {
            // Event delivery is best-effort: a dead socket must not break the undo path.
        }
    }

    // ------------------------------------------------------------------
    // ids
    // ------------------------------------------------------------------

    private DocumentId activeDocument() throws EditApiFailure {
        return env.activeDocument()
            .orElseThrow(() -> new EditApiFailure(EditApiErrorCode.INVALID_DOCUMENT));
    }

    /**
     * Validates the request {@code ModelUID} against the connection's binding table (US5).
     * A first-seen uid binds to the currently active document; a bound uid whose document is
     * no longer active is stale and fails {@code InvalidDocument} without re-binding.
     */
    private void requireModelUid(
        final EditConnectionState state,
        final EditApiPayload payload
    ) throws EditApiFailure {
        final String uid = payload.requiredString("ModelUID");
        final Optional<DocumentId> bound = state.boundDocument(uid);
        if (bound.isPresent()) {
            if (!bound.orElseThrow().equals(activeDocument())) {
                throw new EditApiFailure(EditApiErrorCode.INVALID_DOCUMENT);
            }
            return;
        }
        state.bindModelUid(uid, activeDocument());
    }

    /**
     * Resolves a bare official object id to a {@link ModelObjectReference}. The kind comes from
     * the model's structure trees (cached per session); a glue id resolves via a sentinel kind
     * the engine's GetObject route accepts, and an absent id fails {@code InvalidModel}.
     */
    private ModelObjectReference objectReference(
        final EditConnectionState state,
        final EditSession session,
        final String id
    ) throws EditApiFailure {
        final EditObjectKind kind = resolveKind(state, session, id);
        if (kind == null) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_MODEL);
        }
        return new ModelObjectReference(sentinelKind(kind), id);
    }

    /**
     * The {@link ModelObjectKind} for a resolved palette kind. Glue and art-path ids have no
     * {@code ModelObjectKind}: glue is reported as the GetObject sentinel (the engine resolves
     * glue by id alone) and art paths are rejected — they are not addressable by any editing
     * route.
     */
    private ModelObjectKind sentinelKind(final EditObjectKind kind) throws EditApiFailure {
        return switch (kind) {
            case PART -> ModelObjectKind.PART;
            case ART_MESH -> ModelObjectKind.ART_MESH;
            case WARP_DEFORMER -> ModelObjectKind.WARP_DEFORMER;
            case ROTATION_DEFORMER -> ModelObjectKind.ROTATION_DEFORMER;
            case GLUE -> ModelObjectKind.PART;
            case ART_PATH -> throw new EditApiFailure(EditApiErrorCode.INVALID_MODEL);
        };
    }

    private EditObjectKind resolveKind(
        final EditConnectionState state,
        final EditSession session,
        final String id
    ) throws EditApiFailure {
        Map<String, EditObjectKind> cache =
            state.session() == session ? state.kindCache().orElse(null) : null;
        if (cache == null) {
            cache = buildKindMap(session);
            if (state.session() == session) {
                state.kindCache(cache);
            }
        }
        return cache.get(id);
    }

    private Map<String, EditObjectKind> buildKindMap(final EditSession session) {
        final Map<String, EditObjectKind> kinds = new HashMap<>();
        try {
            collectKinds(session.partObjects().partStructure(), kinds);
        } catch (EditSessionException | RuntimeException ignored) {
            // a partial map still resolves the ids the palette tree carries
        }
        try {
            collectKinds(session.deformers().deformerStructure(), kinds);
        } catch (EditSessionException | RuntimeException ignored) {
            // a partial map still resolves the ids the deformer tree carries
        }
        return kinds;
    }

    private void collectKinds(
        final EditObjectNode node,
        final Map<String, EditObjectKind> kinds
    ) {
        kinds.putIfAbsent(node.id().value(), node.kind());
        for (final EditObjectNode child : node.children()) {
            collectKinds(child, kinds);
        }
    }

    // ------------------------------------------------------------------
    // payload helpers
    // ------------------------------------------------------------------

    private List<EditParameterKeyCondition> conditions(
        final EditApiPayload payload
    ) throws EditApiFailure {
        final Optional<List<JsonNode>> raw = payload.optionalArray("Parameters");
        if (raw.isEmpty()) {
            return List.of();
        }
        final List<EditParameterKeyCondition> conditions =
            new ArrayList<>(raw.orElseThrow().size());
        for (final JsonNode entry : raw.orElseThrow()) {
            if (entry == null || !entry.isObject()) {
                throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
            }
            final JsonNode id = entry.get("Id");
            final JsonNode value = entry.get("Value");
            if (id != null && !id.isNull() && !id.isTextual()) {
                throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
            }
            if (value != null && !value.isNull()
                && !(value.isNumber() && Double.isFinite(value.asDouble()))) {
                throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
            }
            final Optional<ParameterId> parameter =
                id != null && id.isTextual() && !id.asText().isEmpty()
                    ? Optional.of(parameterId(id.asText()))
                    : Optional.empty();
            final Optional<Double> keyValue =
                value != null && value.isNumber()
                    ? Optional.of(value.asDouble())
                    : Optional.empty();
            conditions.add(new EditParameterKeyCondition(parameter, keyValue));
        }
        return List.copyOf(conditions);
    }

    private ParameterId parameterId(final String value) throws EditApiFailure {
        try {
            return new ParameterId(value);
        } catch (IllegalArgumentException malformed) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
    }

    private ParameterGroupId parameterGroupId(final String value) throws EditApiFailure {
        try {
            return new ParameterGroupId(value);
        } catch (IllegalArgumentException malformed) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
    }

    private PartId partId(final String value) throws EditApiFailure {
        try {
            return new PartId(value);
        } catch (IllegalArgumentException malformed) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
    }

    private DeformerId deformerId(final String value) throws EditApiFailure {
        try {
            return new DeformerId(value);
        } catch (IllegalArgumentException malformed) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
    }

    private ArtMeshId artMeshId(final String value) throws EditApiFailure {
        try {
            return new ArtMeshId(value);
        } catch (IllegalArgumentException malformed) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
    }

    private GlueId glueId(final String value) throws EditApiFailure {
        try {
            return new GlueId(value);
        } catch (IllegalArgumentException malformed) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
    }

    private List<ModelObjectId> objectIds(final List<String> raw) throws EditApiFailure {
        final List<ModelObjectId> ids = new ArrayList<>(raw.size());
        for (final String value : raw) {
            try {
                ids.add(new ModelObjectId(value));
            } catch (IllegalArgumentException malformed) {
                throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
            }
        }
        return ids;
    }

    private Optional<EditDeformerAttachMode> attachMode(
        final EditApiPayload payload
    ) throws EditApiFailure {
        final Optional<String> mode = payload.optionalString("Mode");
        if (mode.isEmpty()) {
            return Optional.empty();
        }
        final Optional<EditDeformerAttachMode> parsed =
            EditApiWire.parseAttachMode(mode.orElseThrow());
        if (parsed.isEmpty()) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
        return parsed;
    }

    /** {@return present-and-boolean as Optional; absent as empty; wrong type fails} */
    private Optional<Boolean> flag(
        final EditApiPayload payload,
        final String field
    ) throws EditApiFailure {
        return payload.has(field)
            ? Optional.of(payload.requiredBoolean(field))
            : Optional.empty();
    }

    // ------------------------------------------------------------------
    // routes
    // ------------------------------------------------------------------

    private void registerRoutes() {
        // session family
        route("GetIsEditApproval", Access.REGISTERED_ONLY, false, this::getIsEditApproval);
        route("EditBegin", Access.REGISTERED_ONLY, true, this::editBegin);
        route("EditEnd", Access.REGISTERED_ONLY, true, this::editEnd);
        route("EditSendLog", Access.SESSION_OWNER, true, this::editSendLog);
        route("EditSendProgress", Access.SESSION_OWNER, true, this::editSendProgress);
        route("NotifyUndoCancel", Access.REGISTERED_ONLY, false, this::notifyUndoCancel);
        // parameter keys
        route("AddParameterKey", Access.SESSION_OWNER, true, this::addParameterKey);
        route("DeleteParameterKey", Access.SESSION_OWNER, true, this::deleteParameterKey);
        route("MoveParameterKey", Access.SESSION_OWNER, true, this::moveParameterKey);
        route("GetParameterKeys", Access.SESSION_READ, false, this::getParameterKeys);
        route("GetObjectsByParameterKeys", Access.SESSION_OWNER, false,
            this::getObjectsByParameterKeys);
        // parameter structure
        route("GetParameterStructure", Access.SESSION_READ, false, this::getParameterStructure);
        route("AddParameter", Access.SESSION_OWNER, true, this::addParameter);
        route("AddParameterGroup", Access.SESSION_OWNER, true, this::addParameterGroup);
        route("EditParameter", Access.SESSION_OWNER, true, this::editParameter);
        route("EditParameterGroup", Access.SESSION_OWNER, true, this::editParameterGroup);
        route("DeleteParameter", Access.SESSION_OWNER, true, this::deleteParameter);
        route("DeleteParameterGroup", Access.SESSION_OWNER, true, this::deleteParameterGroup);
        route("MoveParameter", Access.SESSION_OWNER, true, this::moveParameter);
        route("MoveParameterGroup", Access.SESSION_OWNER, true, this::moveParameterGroup);
        // selection
        route("GetSelectedObjects", Access.SESSION_READ, false, this::getSelectedObjects);
        route("AddSelectedObjects", Access.SESSION_OWNER, true, this::addSelectedObjects);
        route("ClearSelectedObjects", Access.SESSION_READ, false, this::clearSelectedObjects);
        // parts / objects
        route("GetPartStructure", Access.SESSION_READ, false, this::getPartStructure);
        route("GetObject", Access.SESSION_READ, false, this::getObject);
        route("DeleteObject", Access.SESSION_OWNER, true, this::deleteObject);
        route("MoveObjectOnPartsPalette", Access.SESSION_OWNER, true,
            this::moveObjectOnPartsPalette);
        route("AddPart", Access.SESSION_OWNER, true, this::addPart);
        route("EditPart", Access.SESSION_OWNER, true, this::editPart);
        route("EditArtMesh", Access.SESSION_OWNER, true, this::editArtMesh);
        route("EditGlue", Access.SESSION_OWNER, true, this::editGlue);
        // deformers
        route("GetDeformerStructure", Access.SESSION_READ, true, this::getDeformerStructure);
        route("AddRotationDeformer", Access.SESSION_OWNER, true, this::addRotationDeformer);
        route("AddWarpDeformer", Access.SESSION_OWNER, true, this::addWarpDeformer);
        route("EditRotationDeformer", Access.SESSION_OWNER, true, this::editRotationDeformer);
        route("EditWarpDeformer", Access.SESSION_OWNER, true, this::editWarpDeformer);
    }

    private void route(
        final String method,
        final Access access,
        final boolean approval,
        final Handler handler
    ) {
        routes.put(method, new Route(access, approval, missingIdError(method), handler));
    }

    /** The typed error for ids the engine reports absent, per family. */
    private EditApiErrorCode missingIdError(final String method) {
        return switch (method) {
            case "AddParameter", "AddParameterGroup", "EditParameter", "EditParameterGroup",
                 "DeleteParameter", "DeleteParameterGroup", "MoveParameter",
                 "MoveParameterGroup", "AddParameterKey", "DeleteParameterKey",
                 "MoveParameterKey", "GetParameterKeys", "GetObjectsByParameterKeys" ->
                EditApiErrorCode.INVALID_PARAMETER;
            default -> EditApiErrorCode.INVALID_MODEL;
        };
    }

    // ------------------------------------------------------------------
    // session handlers
    // ------------------------------------------------------------------

    private JsonNode getIsEditApproval(final Exchange exchange) {
        final boolean granted = exchange.state.approval()
            == EditConnectionState.Approval.APPROVED;
        boolean admitted = false;
        if (granted) {
            try {
                admitted = env.editSessions().isEditApproved(env.pluginContext());
            } catch (EditSessionException | RuntimeException unavailable) {
                admitted = false;
            }
        }
        return EditApiWire.result(granted && admitted);
    }

    private JsonNode editBegin(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final boolean silent = exchange.payload().optionalBoolean("Silent", false);
        final EditConnectionState state = exchange.state;
        if (state.ownsOpenSession() && sessionOwner == state) {
            return EditApiWire.result(true);
        }
        if (sessionOwner != null) {
            return EditApiWire.result(false);
        }
        final DocumentId document;
        try {
            document = activeDocument();
        } catch (EditApiFailure noDocument) {
            return EditApiWire.result(false);
        }
        final EditSession session;
        try {
            session = env.editSessions().open(
                env.pluginContext(), document,
                new EditSessionOptions(silent,
                    Optional.of((s, source) -> onUndoCancelled(state, source))));
        } catch (EditSessionException | RuntimeException refused) {
            return EditApiWire.result(false);
        }
        state.session(session, exchange.info().sessionKey());
        sessionOwner = state;
        return EditApiWire.result(true);
    }

    private JsonNode editEnd(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final boolean cancel = exchange.payload().optionalBoolean("Cancel", false);
        final EditConnectionState owner = sessionOwner;
        final EditSession session = owner == null ? null : owner.session();
        if (session == null || !session.isOpen()) {
            return EditApiWire.result(false);
        }
        try {
            final EditSessionCloseResult result =
                cancel ? session.cancel() : session.close();
            owner.session(null, "");
            sessionOwner = null;
            return EditApiWire.result(
                result.outcome() != EditSessionCloseOutcome.FAILED);
        } catch (EditSessionException | RuntimeException failure) {
            owner.session(null, "");
            sessionOwner = null;
            return EditApiWire.result(false);
        }
    }

    private JsonNode editSendLog(final Exchange exchange) throws EditApiFailure, EditSessionException {
        exchange.session().log(exchange.payload().requiredString("Message"));
        return EditApiWire.empty();
    }

    private JsonNode editSendProgress(final Exchange exchange) throws EditApiFailure, EditSessionException {
        exchange.session().progress(exchange.payload().requiredNumber("Value"));
        return EditApiWire.empty();
    }

    private JsonNode notifyUndoCancel(final Exchange exchange) throws EditApiFailure, EditSessionException {
        exchange.state.undoCancelSubscribed(
            exchange.payload().optionalBoolean("Enabled", false));
        return EditApiWire.accepted(true);
    }

    // ------------------------------------------------------------------
    // parameter keys
    // ------------------------------------------------------------------

    private JsonNode addParameterKey(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        final ModelObjectReference object =
            objectReference(exchange.state, exchange.session(), data.requiredString("ObjectId"));
        final ParameterId parameter = parameterId(data.requiredString("ParameterId"));
        return EditApiWire.result(exchange.session().parameterKeys().addParameterKey(
            new ParameterKeyOps.AddParameterKey(
                object, parameter, data.requiredNumber("KeyValue"))));
    }

    private JsonNode deleteParameterKey(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        final Optional<ModelObjectReference> object = optionalObjectRef(exchange, "ObjectId");
        final Optional<ParameterId> parameter = optionalId(data, "ParameterId",
            this::parameterId);
        return EditApiWire.result(exchange.session().parameterKeys().deleteParameterKey(
            new ParameterKeyOps.DeleteParameterKey(
                object, parameter, data.optionalNumber("KeyValue"),
                data.optionalBoolean("Strict", true))));
    }

    private JsonNode moveParameterKey(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        final Optional<ModelObjectReference> object = optionalObjectRef(exchange, "ObjectId");
        final Optional<ParameterId> parameter = optionalId(data, "ParameterId",
            this::parameterId);
        return EditApiWire.result(exchange.session().parameterKeys().moveParameterKey(
            new ParameterKeyOps.MoveParameterKey(
                object, parameter, data.requiredNumber("FromValue"),
                data.requiredNumber("ToValue"), data.optionalBoolean("Strict", true),
                data.optionalBoolean("ForceOverwrite", false))));
    }

    private JsonNode getParameterKeys(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        final ModelObjectReference object =
            objectReference(exchange.state, exchange.session(), data.requiredString("ObjectId"));
        return EditApiWire.parameterKeys(exchange.session().parameterKeys().parameterKeys(
            new ParameterKeyOps.GetParameterKeys(object)));
    }

    private JsonNode getObjectsByParameterKeys(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        final ParameterId parameter = parameterId(data.requiredString("ParameterId"));
        return EditApiWire.ids(exchange.session().parameterKeys().objectsByParameterKeys(
            new ParameterKeyOps.GetObjectsByParameterKeys(
                parameter, data.requiredNumber("KeyValue"))));
    }

    // ------------------------------------------------------------------
    // parameter structure
    // ------------------------------------------------------------------

    private JsonNode getParameterStructure(final Exchange exchange) throws EditApiFailure, EditSessionException {
        requireModelUid(exchange.state, exchange.payload());
        return EditApiWire.parameterStructure(
            exchange.session().parameterStructure().parameterStructure());
    }

    private JsonNode addParameter(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().parameterStructure().addParameter(
            new ParameterStructureOps.AddParameter(
                data.optionalString("Name"), optionalId(data, "Id", this::parameterId),
                optionalId(data, "GroupId", this::parameterGroupId),
                data.optionalNumber("Min"), data.optionalNumber("Default"),
                data.optionalNumber("Max"), data.optionalBoolean("IsBlendShape", false))));
    }

    private JsonNode addParameterGroup(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().parameterStructure().addParameterGroup(
            new ParameterStructureOps.AddParameterGroup(
                data.optionalString("Name"),
                optionalId(data, "Id", this::parameterGroupId))));
    }

    private JsonNode editParameter(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().parameterStructure().editParameter(
            new ParameterStructureOps.EditParameter(
                parameterId(data.requiredString("Id")),
                optionalId(data, "NewId", this::parameterId), data.optionalString("Name"),
                data.optionalNumber("Min"), data.optionalNumber("Default"),
                data.optionalNumber("Max"), flag(data, "IsRepeat"))));
    }

    private JsonNode editParameterGroup(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().parameterStructure().editParameterGroup(
            new ParameterStructureOps.EditParameterGroup(
                parameterGroupId(data.requiredString("Id")),
                optionalId(data, "NewId", this::parameterGroupId), data.optionalString("Name"),
                EditApiWire.parseLabelColor(data))));
    }

    private JsonNode deleteParameter(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().parameterStructure().deleteParameter(
            new ParameterStructureOps.DeleteParameter(
                parameterId(data.requiredString("Id")))));
    }

    private JsonNode deleteParameterGroup(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().parameterStructure().deleteParameterGroup(
            new ParameterStructureOps.DeleteParameterGroup(
                parameterGroupId(data.requiredString("Id")))));
    }

    private JsonNode moveParameter(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().parameterStructure().moveParameter(
            new ParameterStructureOps.MoveParameter(
                parameterId(data.requiredString("Id")),
                parameterGroupId(data.requiredString("GroupId")),
                data.optionalInt("InsertIndex"))));
    }

    private JsonNode moveParameterGroup(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().parameterStructure().moveParameterGroup(
            new ParameterStructureOps.MoveParameterGroup(
                parameterGroupId(data.requiredString("Id")),
                (int) data.requiredNumber("InsertIndex"))));
    }

    // ------------------------------------------------------------------
    // selection
    // ------------------------------------------------------------------

    private JsonNode getSelectedObjects(final Exchange exchange) throws EditApiFailure, EditSessionException {
        requireModelUid(exchange.state, exchange.payload());
        return EditApiWire.ids(exchange.session().selection().selectedObjects());
    }

    private JsonNode addSelectedObjects(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().selection().addSelectedObjects(
            new SelectionOps.AddSelectedObjects(
                objectIds(data.optionalStringList("Ids").orElse(List.of())))));
    }

    private JsonNode clearSelectedObjects(final Exchange exchange) throws EditApiFailure, EditSessionException {
        requireModelUid(exchange.state, exchange.payload());
        return EditApiWire.result(exchange.session().selection().clearSelectedObjects());
    }

    // ------------------------------------------------------------------
    // parts / objects
    // ------------------------------------------------------------------

    private JsonNode getPartStructure(final Exchange exchange) throws EditApiFailure, EditSessionException {
        requireModelUid(exchange.state, exchange.payload());
        return EditApiWire.partStructure(exchange.session().partObjects().partStructure());
    }

    private JsonNode getObject(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        final ModelObjectReference object =
            objectReference(exchange.state, exchange.session(), data.requiredString("Id"));
        return EditApiWire.objectSnapshot(exchange.session().partObjects().object(
            new PartObjectOps.GetObject(object, conditions(data))));
    }

    private JsonNode deleteObject(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        final ModelObjectReference object =
            objectReference(exchange.state, exchange.session(), data.requiredString("Id"));
        return EditApiWire.result(exchange.session().partObjects().deleteObject(
            new PartObjectOps.DeleteObject(object)));
    }

    private JsonNode moveObjectOnPartsPalette(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        final ModelObjectReference object =
            objectReference(exchange.state, exchange.session(), data.requiredString("Id"));
        return EditApiWire.result(exchange.session().partObjects().moveObjectOnPartsPalette(
            new PartObjectOps.MoveObjectOnPartsPalette(
                object, optionalId(data, "ParentId", this::partId),
                optionalId(data, "InsertId", this::modelObjectId),
                data.optionalInt("InsertIndex"))));
    }

    private JsonNode addPart(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().partObjects().addPart(
            new PartObjectOps.AddPart(
                data.optionalString("Name"), optionalId(data, "Id", this::partId),
                data.optionalInt("DrawOrder"),
                objectIds(data.optionalStringList("Ids").orElse(List.of())),
                data.optionalBoolean("IsNested", false))));
    }

    private JsonNode editPart(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().partObjects().editPart(
            new PartObjectOps.EditPart(
                partId(data.requiredString("Id")), conditions(data),
                data.optionalBoolean("IsExactMatch", true),
                optionalId(data, "NewId", this::partId), data.optionalString("Name"),
                optionalId(data, "ParentId", this::partId),
                flag(data, "IsGrouped"), flag(data, "IsGuidImage"), flag(data, "IsOffscreen"),
                optionalIdList(data, "ClippingIds"), flag(data, "IsReverseMask"),
                data.optionalInt("DrawOrder"), data.optionalNumber("Opacity"),
                data.optionalString("MultiplyColor"), data.optionalString("ScreenColor"),
                EditApiWire.optionalEnum(data, "ColorBlend", EditApiWire.colorBlendNames(),
                    dev.turboism.sdk.cubism.edit.EditColorBlend.class),
                EditApiWire.optionalEnum(data, "AlphaBlend", EditApiWire.alphaBlendNames(),
                    dev.turboism.sdk.cubism.edit.EditAlphaBlend.class),
                EditApiWire.parseLabelColor(data))));
    }

    private JsonNode editArtMesh(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().partObjects().editArtMesh(
            new PartObjectOps.EditArtMesh(
                artMeshId(data.requiredString("Id")), conditions(data),
                data.optionalBoolean("IsExactMatch", true),
                optionalId(data, "NewId", this::artMeshId), data.optionalString("Name"),
                optionalId(data, "ParentId", this::partId),
                optionalId(data, "ParentDeformerId", this::deformerId),
                optionalIdList(data, "ClippingIds"), flag(data, "IsReverseMask"),
                data.optionalInt("DrawOrder"), data.optionalNumber("Opacity"),
                data.optionalString("MultiplyColor"), data.optionalString("ScreenColor"),
                EditApiWire.optionalEnum(data, "ColorBlend", EditApiWire.colorBlendNames(),
                    dev.turboism.sdk.cubism.edit.EditColorBlend.class),
                EditApiWire.optionalEnum(data, "AlphaBlend", EditApiWire.alphaBlendNames(),
                    dev.turboism.sdk.cubism.edit.EditAlphaBlend.class),
                flag(data, "IsCulling"), EditApiWire.parseLabelColor(data))));
    }

    private JsonNode editGlue(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().partObjects().editGlue(
            new PartObjectOps.EditGlue(
                glueId(data.requiredString("Id")), conditions(data),
                data.optionalBoolean("IsExactMatch", true),
                optionalId(data, "NewId", this::glueId), data.optionalString("Name"),
                optionalId(data, "ParentId", this::partId),
                data.optionalNumber("Intensity"), EditApiWire.parseLabelColor(data))));
    }

    // ------------------------------------------------------------------
    // deformers
    // ------------------------------------------------------------------

    private JsonNode getDeformerStructure(final Exchange exchange) throws EditApiFailure, EditSessionException {
        requireModelUid(exchange.state, exchange.payload());
        return EditApiWire.deformerStructure(
            exchange.session().deformers().deformerStructure());
    }

    private JsonNode addRotationDeformer(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().deformers().addRotationDeformer(
            new DeformerOps.AddRotationDeformer(
                data.optionalString("Name"), optionalId(data, "Id", this::deformerId),
                optionalId(data, "ParentId", this::partId),
                objectIds(data.optionalStringList("TargetObjectIds").orElse(List.of())),
                attachMode(data).orElse(EditDeformerAttachMode.AS_PARENT))));
    }

    private JsonNode addWarpDeformer(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().deformers().addWarpDeformer(
            new DeformerOps.AddWarpDeformer(
                data.optionalString("Name"), optionalId(data, "Id", this::deformerId),
                optionalId(data, "ParentId", this::partId),
                objectIds(data.optionalStringList("TargetObjectIds").orElse(List.of())),
                attachMode(data).orElse(EditDeformerAttachMode.AS_PARENT),
                data.optionalInt("WarpDivH"), data.optionalInt("WarpDivV"),
                data.optionalInt("BezierDivH"), data.optionalInt("BezierDivV"),
                flag(data, "ConsiderChildKeyforms"), flag(data, "SnapCenter"))));
    }

    private JsonNode editRotationDeformer(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().deformers().editRotationDeformer(
            new DeformerOps.EditRotationDeformer(
                deformerId(data.requiredString("Id")), conditions(data),
                data.optionalBoolean("IsExactMatch", true),
                optionalId(data, "NewId", this::deformerId), data.optionalString("Name"),
                optionalId(data, "ParentId", this::partId),
                optionalId(data, "ParentDeformerId", this::deformerId),
                data.optionalNumber("Angle"), data.optionalNumber("BaseAngle"),
                data.optionalNumber("Scale"), data.optionalNumber("Opacity"),
                data.optionalString("MultiplyColor"), data.optionalString("ScreenColor"),
                EditApiWire.parseLabelColor(data))));
    }

    private JsonNode editWarpDeformer(final Exchange exchange) throws EditApiFailure, EditSessionException {
        final EditApiPayload data = exchange.payload();
        requireModelUid(exchange.state, data);
        return EditApiWire.result(exchange.session().deformers().editWarpDeformer(
            new DeformerOps.EditWarpDeformer(
                deformerId(data.requiredString("Id")), conditions(data),
                data.optionalBoolean("IsExactMatch", true),
                optionalId(data, "NewId", this::deformerId), data.optionalString("Name"),
                optionalId(data, "ParentId", this::partId),
                optionalId(data, "ParentDeformerId", this::deformerId),
                data.optionalNumber("Opacity"), data.optionalString("MultiplyColor"),
                data.optionalString("ScreenColor"), data.optionalInt("WarpDivH"),
                data.optionalInt("WarpDivV"), data.optionalInt("BezierDivH"),
                data.optionalInt("BezierDivV"), EditApiWire.parseLabelColor(data))));
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface IdParser<T> {
        T parse(String value) throws EditApiFailure;
    }

    private <T> Optional<T> optionalId(
        final EditApiPayload payload,
        final String field,
        final IdParser<T> parser
    ) throws EditApiFailure {
        final Optional<String> text = payload.optionalString(field);
        return text.isPresent() ? Optional.of(parser.parse(text.orElseThrow()))
            : Optional.empty();
    }

    private Optional<ModelObjectReference> optionalObjectRef(
        final Exchange exchange,
        final String field
    ) throws EditApiFailure {
        final Optional<String> id = exchange.payload().optionalString(field);
        return id.isPresent()
            ? Optional.of(
                objectReference(exchange.state, exchange.session(), id.orElseThrow()))
            : Optional.empty();
    }

    private ModelObjectId modelObjectId(final String value) throws EditApiFailure {
        try {
            return new ModelObjectId(value);
        } catch (IllegalArgumentException malformed) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
    }

    private Optional<List<ModelObjectId>> optionalIdList(
        final EditApiPayload payload,
        final String field
    ) throws EditApiFailure {
        final Optional<List<String>> raw = payload.optionalStringList(field);
        return raw.isPresent() ? Optional.of(objectIds(raw.orElseThrow())) : Optional.empty();
    }

    /** Exchange passed to handlers: the request view plus the resolved session (or null). */
    private final class Exchange {
        private final EditApiEnvelope envelope;
        private final EditConnectionState state;
        private final EditConnectionInfo info;
        private final EditSession session;

        private Exchange(
            final EditApiEnvelope envelope,
            final EditConnectionState state,
            final EditConnectionInfo info,
            final EditSession session
        ) {
            this.envelope = envelope;
            this.state = state;
            this.info = info;
            this.session = session;
        }

        EditApiPayload payload() throws EditApiFailure {
            return EditApiPayload.of(envelope.data());
        }

        EditSession session() {
            return session;
        }

        EditConnectionInfo info() {
            return info;
        }
    }

    private enum Access {
        /** Registered connection only; no edit session is consulted. */
        REGISTERED_ONLY,
        /** The socket must own the live session ({@code e()}). */
        SESSION_OWNER,
        /**
         * A sessionless-capable read ({@code e()=false}): runs on the owned session when one
         * is open, else inside a transient silent session cancelled afterwards.
         */
        SESSION_READ
    }

    private record Route(
        Access access, boolean approval, EditApiErrorCode missingIdError, Handler handler) {
    }

    @FunctionalInterface
    private interface Handler {
        JsonNode handle(Exchange exchange) throws EditApiFailure, EditSessionException;
    }
}
