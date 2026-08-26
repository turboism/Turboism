package dev.turboism.test.fake;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;

/**
 * Simulates a sidecar process for tests.
 *
 * <p>Tests control the fake by enqueueing replies, crashes, or timeouts. Each
 * configured behavior is consumed in order by a test-side dispatcher.
 */
public final class FakeSidecarProcess {

    public enum Behavior {
        SUCCESS,
        ERROR,
        TIMEOUT
    }

    public record Response(
        Behavior behavior,
        String payload,
        String errorCode,
        String errorMessage
    ) {
        public Response {
            Objects.requireNonNull(behavior, "behavior");
        }
    }

    private final Queue<Response> responses = new ArrayDeque<>();

    /**
     * Enqueues a successful sidecar response with the given JSON payload.
     *
     * @param payload the response payload; must not be null
     */
    public void enqueueResponse(String payload) {
        responses.add(new Response(Behavior.SUCCESS, Objects.requireNonNull(payload, "payload"), null, null));
    }

    /**
     * Enqueues a sidecar crash with the given error code and message.
     *
     * @param errorCode    the machine-readable error code; must not be null
     * @param errorMessage the human-readable error message; must not be null
     */
    public void simulateCrash(String errorCode, String errorMessage) {
        responses.add(new Response(
            Behavior.ERROR,
            null,
            Objects.requireNonNull(errorCode, "errorCode"),
            Objects.requireNonNull(errorMessage, "errorMessage")
        ));
    }

    /**
     * Enqueues a sidecar timeout. A dispatcher will return a future that
     * never completes.
     */
    public void simulateTimeout() {
        responses.add(new Response(Behavior.TIMEOUT, null, null, null));
    }

    /**
     * Returns the next configured result, or null if none was configured.
     */
    public Response nextResponse() {
        return responses.poll();
    }
}
