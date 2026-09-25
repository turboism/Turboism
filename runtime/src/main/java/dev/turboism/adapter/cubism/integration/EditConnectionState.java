package dev.turboism.adapter.cubism.integration;

import dev.turboism.sdk.cubism.edit.EditSession;
import dev.turboism.sdk.cubism.id.DocumentId;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mutable per-WebSocket editing state the bridge maintains alongside the host's own connection
 * record (US3/US4/US5).
 *
 * <p>One instance exists per socket while it carries editing traffic. It tracks the Turboism
 * edit-approval decision (undecided until the first gated method arrives, then latched), the
 * owned engine session while {@code EditBegin}..{@code EditEnd} is open, the {@code
 * NotifyUndoCancel} subscription flag, and the external-ID binding: the official {@code
 * ModelUID} strings the client learned from the native {@code GetCurrentModelUID} mapped to the
 * {@link DocumentId} they were bound against. A bound uid whose document is no longer active is
 * stale and fails typed — the binding is never silently re-pointed.</p>
 */
final class EditConnectionState {

    /** Turboism edit-approval latch for this connection. */
    enum Approval {
        UNDECIDED,
        APPROVED,
        DENIED
    }

    // Weak on purpose: the state map is a WeakHashMap keyed by the socket, so a strong
    // socket reference here would pin every dead connection's entry forever.
    private final WeakReference<Object> socket;
    private Approval approval = Approval.UNDECIDED;
    private EditSession session;
    private String sessionKey = "";
    private boolean undoCancelSubscribed;
    private EditApiVersion negotiatedVersion;
    private final Map<String, DocumentId> modelUidBindings = new LinkedHashMap<>();
    private Map<String, dev.turboism.sdk.cubism.edit.EditObjectKind> kindCache;

    EditConnectionState(final Object socket) {
        this.socket = new WeakReference<>(socket);
    }

    /** {@return the owning socket, or null once the host has let it go} */
    Object socket() {
        return socket.get();
    }

    Approval approval() {
        return approval;
    }

    void approval(final Approval value) {
        approval = value;
    }

    /** {@return the session this connection owns, or null} */
    EditSession session() {
        return session;
    }

    /** {@return whether this connection owns an open engine session} */
    boolean ownsOpenSession() {
        return session != null && session.isOpen();
    }

    void session(final EditSession value, final String key) {
        session = value;
        sessionKey = value == null ? "" : key;
        if (value == null) {
            kindCache = null;
        }
    }

    String sessionKey() {
        return sessionKey;
    }

    boolean undoCancelSubscribed() {
        return undoCancelSubscribed;
    }

    void undoCancelSubscribed(final boolean subscribed) {
        undoCancelSubscribed = subscribed;
    }

    EditApiVersion negotiatedVersion() {
        return negotiatedVersion;
    }

    void negotiatedVersion(final EditApiVersion version) {
        negotiatedVersion = version;
    }

    /**
     * {@return the document bound to {@code modelUid}, or empty when this uid was never seen on
     * this connection}
     */
    Optional<DocumentId> boundDocument(final String modelUid) {
        return Optional.ofNullable(modelUidBindings.get(modelUid));
    }

    /** Binds an external {@code ModelUID} to the document it was observed against. */
    void bindModelUid(final String modelUid, final DocumentId document) {
        modelUidBindings.put(modelUid, document);
    }

    /** Drops all uid bindings — document switches tombstone the whole external id space. */
    void clearModelUidBindings() {
        modelUidBindings.clear();
        kindCache = null;
    }

    /** {@return the cached id→kind map for the open session, or empty} */
    Optional<Map<String, dev.turboism.sdk.cubism.edit.EditObjectKind>> kindCache() {
        return Optional.ofNullable(kindCache);
    }

    void kindCache(final Map<String, dev.turboism.sdk.cubism.edit.EditObjectKind> cache) {
        kindCache = cache;
    }
}
