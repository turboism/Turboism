package dev.turboism.adapter.cubism.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * Phase-1 protocol bridge for the 5.4-style editing API over the host's own WebSocket service.
 *
 * <p>Installed as a loader-neutral {@link BiFunction} in {@link System#getProperties()} and
 * invoked at the entry of the host dispatcher
 * ({@code com.live2d.cubism.doc.webSocket.l.a(String, WebSocket)}) before the host's JSONIC
 * decode. The contract is deliberately narrow:</p>
 *
 * <ul>
 *   <li>{@code true} — Turboism owns this message; the host body must return immediately.</li>
 *   <li>{@code false} — the message is native 1.0.x, unknown, malformed, or the bridge is
 *       disabled; the host dispatcher must see it exactly as if the hook were absent.</li>
 * </ul>
 *
 * <p>Only the 36 editing-API method names in {@link EditApiMethods} are ever claimed, and only
 * after a strict JSON parse succeeded and produced a textual {@code Method}. Every failure —
 * disabled bridge, unparseable payload, missing or unknown method, unwritable socket — resolves
 * to {@code false} or, once the message is claimed, to an official-shaped error envelope.</p>
 *
 * <p>Phase 1 does not dispatch: recognized, well-formed, version-sufficient requests are
 * answered with {@code InvalidEditOperation} as the not-implemented marker. The 046 engine is
 * wired in Phase 2.</p>
 */
public final class EditProtocolBridge {

    /** System-property key under which the loader-neutral receiver is published. */
    public static final String RECEIVER_PROPERTY =
        "dev.turboism.integration.edit-protocol.receiver";

    /**
     * Kill switch. Any value other than {@code "false"} (case-insensitive) leaves the bridge
     * active; {@code "false"} makes every message fall through natively.
     */
    public static final String ENABLED_PROPERTY =
        "dev.turboism.integration.edit-protocol.enabled";

    /** What the bridge concluded for the most recent message; diagnostics only. */
    public enum Outcome {
        NONE,
        PASSTHROUGH_DISABLED,
        PASSTHROUGH_UNPARSEABLE,
        PASSTHROUGH_NOT_OBJECT,
        PASSTHROUGH_NO_METHOD,
        PASSTHROUGH_UNKNOWN_METHOD,
        INTERCEPTED_UNSUPPORTED_VERSION,
        INTERCEPTED_INVALID_TYPE,
        INTERCEPTED_PLACEHOLDER,
        INTERCEPTED_SEND_FAILED
    }

    private static final String TYPE_REQUEST = "Request";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final EditSocketWriter writer;
    private final AtomicReference<Outcome> lastOutcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicLong intercepted = new AtomicLong();
    private final AtomicLong passedThrough = new AtomicLong();

    public EditProtocolBridge() {
        this(EditSocketWriter.reflective());
    }

    public EditProtocolBridge(final EditSocketWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * {@return the loader-neutral receiver published into {@link System#getProperties()}}
     *
     * <p>The injected prologue calls {@code apply(rawMessage, socket)}; both arguments arrive as
     * {@code Object} and the result is read back as a boxed {@link Boolean}.</p>
     */
    public BiFunction<Object, Object, Object> receiver() {
        return (raw, socket) ->
            raw instanceof String message && socket != null && onMessage(message, socket)
                ? Boolean.TRUE
                : Boolean.FALSE;
    }

    /**
     * Handles one raw WS text frame before the host dispatcher sees it.
     *
     * @param raw    the exact message text the host received
     * @param socket the host {@code org.java_websocket.WebSocket} handle
     * @return {@code true} when Turboism answered the message and the host must skip it
     */
    public boolean onMessage(final String raw, final Object socket) {
        Objects.requireNonNull(socket, "socket");
        if (!enabled()) {
            return pass(Outcome.PASSTHROUGH_DISABLED);
        }
        if (raw == null) {
            return pass(Outcome.PASSTHROUGH_UNPARSEABLE);
        }
        final JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception malformed) {
            // Looser JSONIC dialects and plain garbage stay on the native path; the host
            // dispatcher owns every failure mode for messages we cannot strictly claim.
            return pass(Outcome.PASSTHROUGH_UNPARSEABLE);
        }
        if (root == null || !root.isObject()) {
            return pass(Outcome.PASSTHROUGH_NOT_OBJECT);
        }
        final EditApiEnvelope envelope = EditApiEnvelope.of(root);
        final String method = envelope.method();
        if (method == null) {
            return pass(Outcome.PASSTHROUGH_NO_METHOD);
        }
        if (!EditApiMethods.isEditApiMethod(method)) {
            return pass(Outcome.PASSTHROUGH_UNKNOWN_METHOD);
        }

        // Recognition is committed: from here the message is answered by Turboism, in the
        // host's validation order (version resolution precedes the Type check).
        final EditApiVersion version = EditApiVersion.parse(envelope.version());
        if (version == null || !version.atLeast(EditApiVersion.EDIT_API_MINIMUM)) {
            return answer(envelope, socket, EditApiErrorCode.UNSUPPORTED_VERSION,
                Outcome.INTERCEPTED_UNSUPPORTED_VERSION);
        }
        if (!TYPE_REQUEST.equals(envelope.type())) {
            return answer(envelope, socket, EditApiErrorCode.INVALID_TYPE,
                Outcome.INTERCEPTED_INVALID_TYPE);
        }
        return answer(envelope, socket, EditApiErrorCode.INVALID_EDIT_OPERATION,
            Outcome.INTERCEPTED_PLACEHOLDER);
    }

    /** {@return the outcome of the most recent call, or {@link Outcome#NONE}} */
    public Outcome lastOutcome() {
        return lastOutcome.get();
    }

    /** {@return messages Turboism claimed (answered or attempted)} */
    public long interceptedCount() {
        return intercepted.get();
    }

    /** {@return messages left to the native host path} */
    public long passedThroughCount() {
        return passedThrough.get();
    }

    private boolean enabled() {
        final String flag;
        try {
            flag = System.getProperty(ENABLED_PROPERTY);
        } catch (RuntimeException unavailable) {
            return true;
        }
        return !"false".equalsIgnoreCase(flag);
    }

    private boolean answer(
        final EditApiEnvelope envelope,
        final Object socket,
        final EditApiErrorCode code,
        final Outcome outcome
    ) {
        final String frame = EditApiResponses.error(
            envelope.version(), envelope.requestId(), envelope.method(), code,
            System.currentTimeMillis());
        try {
            writer.send(socket, frame);
            lastOutcome.set(outcome);
        } catch (Throwable failure) {
            // The message is already claimed; a failed write must not leak host-side
            // behaviour, so the outcome is still reported as intercepted.
            lastOutcome.set(Outcome.INTERCEPTED_SEND_FAILED);
        }
        intercepted.incrementAndGet();
        return true;
    }

    private boolean pass(final Outcome outcome) {
        lastOutcome.set(outcome);
        passedThrough.incrementAndGet();
        return false;
    }
}
