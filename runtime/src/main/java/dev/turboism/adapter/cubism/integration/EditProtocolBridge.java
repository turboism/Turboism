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
 * <p>Claimed requests are routed by {@link EditApiRouter}: the official gate order
 * (registration → version → type → edit approval → session ownership) runs inside the
 * dispatcher, and handlers delegate to the Phase-046 session engine behind the injectable
 * {@link EditBridgeEnvironment}. A bridge built without an environment stays fail-closed —
 * every editing request is answered {@code PluginNotRegistered}.</p>
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
        INTERCEPTED_UNREGISTERED,
        INTERCEPTED_UNSUPPORTED_VERSION,
        INTERCEPTED_INVALID_TYPE,
        INTERCEPTED_RESPONDED,
        INTERCEPTED_ERROR,
        INTERCEPTED_SEND_FAILED
    }

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final int TRACE_LIMIT = 64;
    private final java.util.concurrent.atomic.AtomicLong traceCount =
        new java.util.concurrent.atomic.AtomicLong();

    private final EditSocketWriter writer;
    private final EditApiRouter router;
    private final AtomicReference<Outcome> lastOutcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicLong intercepted = new AtomicLong();
    private final AtomicLong passedThrough = new AtomicLong();

    /**
     * A bridge with no runtime environment: editing requests are claimed but answered
     * {@code PluginNotRegistered} — fail closed, never engine-touching.
     */
    public EditProtocolBridge() {
        this(EditSocketWriter.reflective());
    }

    public EditProtocolBridge(final EditSocketWriter writer) {
        this(writer, EditBridgeEnvironment.unavailable());
    }

    public EditProtocolBridge(
        final EditSocketWriter writer,
        final EditBridgeEnvironment environment
    ) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.router = new EditApiRouter(
            Objects.requireNonNull(environment, "environment"), writer);
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
        if (TRACE_LIMIT > 0 && traceCount.getAndIncrement() < TRACE_LIMIT) {
            System.out.println("[turboism-edit-bridge] onMessage outcome=begin raw="
                + (raw == null ? "null" : raw.substring(0, Math.min(120, raw.length()))));
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

        // Recognition is committed: the router runs the official gate order
        // (registration → version → type → approval → ownership) and answers or fails typed.
        try {
            final JsonNode data = router.dispatch(envelope, socket);
            return respond(envelope, socket, data.toString());
        } catch (EditApiFailure failure) {
            return answer(envelope, socket, failure.code(), outcomeFor(failure.code()));
        }
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

    /** {@return the router view; tests and diagnostics only} */
    EditApiRouter router() {
        return router;
    }

    private static Outcome outcomeFor(final EditApiErrorCode code) {
        return switch (code) {
            case UNSUPPORTED_VERSION -> Outcome.INTERCEPTED_UNSUPPORTED_VERSION;
            case INVALID_TYPE -> Outcome.INTERCEPTED_INVALID_TYPE;
            case PLUGIN_NOT_REGISTERED -> Outcome.INTERCEPTED_UNREGISTERED;
            default -> Outcome.INTERCEPTED_ERROR;
        };
    }

    private boolean respond(
        final EditApiEnvelope envelope,
        final Object socket,
        final String dataJson
    ) {
        final String frame = EditApiResponses.response(
            envelope.version(), envelope.requestId(), envelope.method(), dataJson,
            System.currentTimeMillis());
        try {
            writer.send(socket, frame);
            lastOutcome.set(Outcome.INTERCEPTED_RESPONDED);
        } catch (Throwable failure) {
            lastOutcome.set(Outcome.INTERCEPTED_SEND_FAILED);
        }
        intercepted.incrementAndGet();
        return true;
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
        if (traceCount.get() <= TRACE_LIMIT) {
            System.out.println("[turboism-edit-bridge] onMessage outcome=" + outcome);
        }
        return false;
    }
}
