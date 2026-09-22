package dev.turboism.adapter.cubism.integration;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.Objects;
import java.util.Optional;

/**
 * Production {@link EditConnectionInspector}: reads the host's per-connection {@code ab}
 * session record exclusively through the verified WebSocket selectors.
 *
 * <p>Resolution chain (all members evidenced in
 * {@code host-evidence/integration-54compat/dispatcher-internals.md} and bound in the
 * editor-model verification records):</p>
 *
 * <pre>
 *   x.a            (holder singleton field, public static final)
 *     -&gt; x.a()    (dispatcher instance getter → l)
 *     -&gt; l.b(ws)  (socket → ab session record)
 *     -&gt; ab.h()   (registration-complete flag)
 *     -&gt; ab.g()   (persisted plugin authorization)
 *     -&gt; ab.i()   (per-connection session key)
 * </pre>
 *
 * <p>Any resolution or invocation failure — missing verified bindings, a stale host class
 * shape, an absent record — yields {@link Optional#empty()}: the router then answers
 * {@code PluginNotRegistered}, which is the fail-closed direction.</p>
 */
public final class VerifiedEditConnectionInspector implements EditConnectionInspector {

    /** Verified alias constants matching the editor-model verification records. */
    public static final String ALIAS_HOLDER_INSTANCE =
        "cubism.integration.websocket.dispatch.holder-instance";
    public static final String ALIAS_DISPATCHER_INSTANCE =
        "cubism.integration.websocket.dispatch.instance";
    public static final String ALIAS_SESSION_LOOKUP =
        "cubism.integration.websocket.dispatch.session-lookup";
    public static final String ALIAS_SESSION_SOCKET =
        "cubism.integration.websocket.session.socket";
    public static final String ALIAS_SESSION_REGISTERED =
        "cubism.integration.websocket.session.registered";
    public static final String ALIAS_SESSION_APPROVED =
        "cubism.integration.websocket.session.approved";
    public static final String ALIAS_SESSION_KEY =
        "cubism.integration.websocket.session.key";

    private final VerifiedMemberResolver resolver;

    public VerifiedEditConnectionInspector(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public Optional<EditConnectionInfo> inspect(final Object socket) {
        if (socket == null) {
            return Optional.empty();
        }
        try {
            final Object holder = resolver.readStaticField(ALIAS_HOLDER_INSTANCE);
            if (holder == null) {
                return Optional.empty();
            }
            final Object dispatcher = resolver.invoke(ALIAS_DISPATCHER_INSTANCE, holder);
            if (dispatcher == null) {
                return Optional.empty();
            }
            final Object record = resolver.invoke(ALIAS_SESSION_LOOKUP, dispatcher, socket);
            if (record == null) {
                return Optional.empty();
            }
            // Sanity: the record must name this socket — a mismatched record means the host
            // surface drifted and the whole inspection is untrusted.
            final Object owner = resolver.invoke(ALIAS_SESSION_SOCKET, record);
            if (owner != socket) {
                return Optional.empty();
            }
            final boolean registered =
                Boolean.TRUE.equals(resolver.invoke(ALIAS_SESSION_REGISTERED, record));
            final boolean authorized =
                Boolean.TRUE.equals(resolver.invoke(ALIAS_SESSION_APPROVED, record));
            final Object key = resolver.invoke(ALIAS_SESSION_KEY, record);
            return Optional.of(new EditConnectionInfo(
                registered, authorized,
                key instanceof String text ? text : "", ""));
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }
}
