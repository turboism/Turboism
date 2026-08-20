package dev.turboism.sdk.hostread;

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
}
