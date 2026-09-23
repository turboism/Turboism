package dev.turboism.sdk.hostread;

import java.util.Optional;

/**
 * Entry point through which a plugin asks for host state without blocking the caller's thread.
 *
 * <p>Submission is the only operation: it returns immediately with an
 * {@link AsyncHostReadSubmission} describing whether the read was accepted, coalesced into an
 * identical in-flight read, or rejected. A rejection is reported as data on the submission, not as
 * a thrown exception, so backpressure and unavailable capabilities are ordinary control flow.
 */
public interface AsyncHostReadService {

    /**
     * Offers a read request to the service.
     *
     * @param request the read to perform, including the caller's timeout
     * @return the submission outcome: a handle when accepted or coalesced, an error when rejected
     */
    AsyncHostReadSubmission submit(AsyncHostReadRequest request);

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static AsyncHostReadService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: queries report empty results. */
    enum Unavailable implements AsyncHostReadService {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public AsyncHostReadSubmission submit(final AsyncHostReadRequest request) {
            java.util.Objects.requireNonNull(request, "request");
            return new AsyncHostReadSubmission(
                AsyncHostReadSubmissionStatus.REJECTED,
                Optional.empty(),
                Optional.of(new AsyncHostReadError(
                    AsyncHostReadErrorCode.RUNTIME_UNAVAILABLE,
                    "async host read service is not available"
                ))
            );
        }
    }
}
