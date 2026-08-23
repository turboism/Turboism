package dev.turboism.sdk.ui;

/**
 * Why a user-file request, read, or write did not succeed.
 *
 * <p>These are the only failure classifications the SDK exposes; the runtime
 * maps host and I/O conditions onto them so plugins never see host detail.</p>
 */
public enum UserFileErrorCode {
    PERMISSION_DENIED,
    INVALID_GRANT,
    MODE_MISMATCH,
    GRANT_EXPIRED,
    GRANT_REVOKED,
    FOREIGN_GRANT,
    SIZE_LIMIT_EXCEEDED,
    ATOMIC_REPLACE_UNAVAILABLE,
    CANCELED,
    RUNTIME_UNAVAILABLE,
    IO_FAILURE
}
