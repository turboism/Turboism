package dev.turboism.sdk.storage;

/**
 * Classification of a {@link StorageError}. The set is closed so callers
 * can switch exhaustively; it covers path and policy rejection
 * ({@code INVALID_PATH}, {@code PERMISSION_DENIED}, {@code LINK_ESCAPE}),
 * limits ({@code SIZE_LIMIT_EXCEEDED}, {@code QUOTA_EXCEEDED}), atomicity
 * gaps, partial completion, and plain I/O failure.
 */
public enum StorageErrorCode {
    INVALID_PATH,
    PERMISSION_DENIED,
    NOT_FOUND,
    ALREADY_EXISTS,
    TYPE_MISMATCH,
    SIZE_LIMIT_EXCEEDED,
    QUOTA_EXCEEDED,
    LINK_ESCAPE,
    ATOMIC_REPLACE_UNAVAILABLE,
    CROSS_ROOT_ATOMIC_MOVE_UNSUPPORTED,
    PARTIAL_DELETE,
    CONFLICT,
    CANCELED,
    RUNTIME_UNAVAILABLE,
    IO_FAILURE
}
