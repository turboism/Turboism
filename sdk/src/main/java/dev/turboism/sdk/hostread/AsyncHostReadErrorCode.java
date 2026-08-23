package dev.turboism.sdk.hostread;

/**
 * Classification of why an asynchronous host read was rejected at submission time or did not
 * complete successfully.
 *
 * <p>The set is closed: callers may switch over it exhaustively. {@code CANCELED} is reserved —
 * {@link AsyncHostReadResult} requires it for, and only for, a {@code CANCELED} status.
 */
public enum AsyncHostReadErrorCode {
    CAPABILITY_UNAVAILABLE,
    PERMISSION_DENIED,
    HOST_VERSION_UNSUPPORTED,
    MAPPING_NOT_VERIFIED,
    VALIDATION_FAILURE,
    TIMEOUT,
    CANCELED,
    BACKPRESSURE,
    RUNTIME_UNAVAILABLE,
    RUNTIME_FAILURE
}
