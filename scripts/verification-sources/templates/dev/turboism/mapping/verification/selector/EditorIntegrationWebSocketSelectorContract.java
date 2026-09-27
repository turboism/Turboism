package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive selector contract for the host-owned external-application WebSocket
 * integration surface ({@code com.live2d.cubism.doc.webSocket.*}) that the
 * official-1.1.0-compatible editing bridge intercepts (spec 050).
 *
 * <p>Every member below is declared with the precise owner internal name, member name, JVM
 * descriptor, and access flags observed on the exact Cubism 5.2.03, 5.3.02, and 5.3.03 host
 * artifacts, evidenced in {@code host-evidence/integration-54compat/dispatcher-internals.md}
 * and reviewed in {@code host-evidence/integration-54compat/mapping-candidates.json}. The
 * capability id is bound on all three reviewed records; any member a record drops still fails
 * the feature closed.</p>
 *
 * <p>The dispatcher entry {@code l.a(String, WebSocket)} is the single interception point; the
 * remaining members let the bridge correlate a socket with the host's own connection record
 * ({@code ab}) — registration and persisted approval — and enumerate the connection registry.
 * The responder ({@code k}) and server ({@code U}) classes are admission evidence only: the
 * bridge serializes its own envelopes and never calls host members outside this contract.</p>
 */
public final class EditorIntegrationWebSocketSelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";

    /** Capability gating the edit-protocol dispatch interception feature. */
    public static final String DISPATCH_CAPABILITY_ID =
        "cubism.integration.edit-api-bridge.dispatch";

    /** The private final {@code l.a(String, WebSocket)} dispatch entry the hook transforms. */
    public static final String DISPATCH_ENTRY_ALIAS =
        "cubism.integration.websocket.dispatch.on-message";

    /**
     * Members needed to resolve the host connection record for a socket: the dispatcher class,
     * its interception entry, the connection registry field, the socket→{@code ab} lookup, and
     * the {@code x} holder singleton that carries the unique {@code l} dispatcher instance
     * ({@code x.a} field + {@code x.a()} getter, public on all three artifacts).
     */
    public static final Set<String> DISPATCH_ALIASES = Set.of(
        "cubism.integration.websocket.dispatch.class",
        "cubism.integration.websocket.dispatch.on-message",
        "cubism.integration.websocket.dispatch.registry",
        "cubism.integration.websocket.dispatch.session-lookup",
        "cubism.integration.websocket.dispatch.holder",
        "cubism.integration.websocket.dispatch.holder-instance",
        "cubism.integration.websocket.dispatch.instance"
    );

    /**
     * {@code ab} session-record members: class admission plus the socket, persisted-approval
     * query, registration flag, and per-connection session key readers.
     */
    public static final Set<String> SESSION_RECORD_ALIASES = Set.of(
        "cubism.integration.websocket.session.class",
        "cubism.integration.websocket.session.socket",
        "cubism.integration.websocket.session.approved",
        "cubism.integration.websocket.session.registered",
        "cubism.integration.websocket.session.key"
    );

    /**
     * Responder ({@code k}) and server ({@code U}) admission evidence. The bridge writes its own
     * frames; these pins prove the reviewed envelope sender and service class exist unchanged.
     */
    public static final Set<String> SERVICE_EVIDENCE_ALIASES = Set.of(
        "cubism.integration.websocket.responder.class",
        "cubism.integration.websocket.responder.send-error",
        "cubism.integration.websocket.server.class"
    );

    /**
     * The full member set the dispatch interception feature needs on the host: the interception
     * point itself plus the connection-state surface used for the registration and approval
     * gates. Admission checks this set under {@link #DISPATCH_CAPABILITY_ID}.
     */
    public static final Set<String> REQUIRED_ALIASES = unionAll(
        DISPATCH_ALIASES,
        SESSION_RECORD_ALIASES,
        SERVICE_EVIDENCE_ALIASES
    );

    private static Set<String> unionAll(final Set<String>... sets) {
        final java.util.HashSet<String> values = new java.util.HashSet<>();
        for (final Set<String> set : sets) {
            values.addAll(set);
        }
        return Set.copyOf(values);
    }

    private EditorIntegrationWebSocketSelectorContract() {
    }
}
