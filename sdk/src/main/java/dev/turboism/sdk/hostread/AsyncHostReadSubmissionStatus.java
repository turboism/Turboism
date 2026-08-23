package dev.turboism.sdk.hostread;

/**
 * Disposition of a read request at the moment it was offered to the service.
 *
 * <p>{@code COALESCED} means the request was satisfied by joining an equivalent read already in
 * flight rather than starting new work; from the caller's side it behaves exactly like
 * {@code ACCEPTED} and still yields a handle.
 */
public enum AsyncHostReadSubmissionStatus {
    ACCEPTED,
    COALESCED,
    REJECTED
}
