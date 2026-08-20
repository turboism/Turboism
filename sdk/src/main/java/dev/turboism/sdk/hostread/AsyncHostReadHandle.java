package dev.turboism.sdk.hostread;

import java.util.concurrent.CompletionStage;

/**
 * Caller-side control surface for one in-flight asynchronous host read, handed out by an accepted
 * or coalesced {@link AsyncHostReadSubmission}.
 *
 * <p>A handle carries no result value itself: the outcome arrives through {@link #completion()},
 * which always settles with an {@link AsyncHostReadResult} describing success, failure, or
 * cancellation rather than by completing exceptionally in the ordinary case. Because a coalesced
 * submission may share the underlying read with other callers, {@link #cancel()} expresses this
 * caller's intent and is not guaranteed to stop the work.
 *
 * <p>The handle is {@link AutoCloseable} so callers can release their interest deterministically;
 * {@link #close()} does not throw a checked exception.
 */
public interface AsyncHostReadHandle extends AutoCloseable {

    /**
     * @return the intent this read was submitted for, unchanged from the originating request
     */
    AsyncHostReadIntent intent();

    /**
     * @return the read's lifecycle state at the moment of the call; may still advance afterwards
     */
    AsyncHostReadStatus status();

    /**
     * Requests cancellation of this read.
     *
     * @return whether the cancellation request took effect for this handle; {@code false} when the
     *     read had already settled or could not be stopped
     */
    boolean cancel();

    /**
     * @return a stage that settles once the read reaches a terminal state, carrying the success,
     *     failure, or cancellation outcome as an {@link AsyncHostReadResult}
     */
    CompletionStage<AsyncHostReadResult> completion();

    @Override
    void close();
}
