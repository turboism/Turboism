package dev.turboism.sdk.ui;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Mediated access to files outside plugin storage, granted only by an explicit
 * user choice.
 *
 * <p>A plugin never names a path: it submits a {@link UserFileRequest}, the
 * runtime shows the chooser, and a granted {@link UserFileHandle} is the only
 * capability that the read and write methods accept. Every operation is
 * asynchronous and reports failure as an error value in the result rather than
 * as an exception, so a denied, expired, revoked, or foreign handle surfaces as
 * a {@link UserFileError} rather than a throw.</p>
 */
public interface UserFileAccessService {

    /**
     * Shows the user chooser for {@code request} and completes with the granted handle or the
     * reason none was granted.
     */
    CompletionStage<UserFileRequestResult> request(UserFileRequest request);

    /**
     * Reads up to {@code maxBytes} of the handle's file as UTF-8 text, asynchronously.
     */
    CompletionStage<UserFileReadResult<String>> readUtf8(UserFileHandle handle, int maxBytes);

    /**
     * Reads at most {@code maxBytes} bytes, with a maximum request of 16 MiB.
     * A larger file returns a bounded value with {@code truncated=true}; a
     * request above this limit returns {@link UserFileErrorCode#SIZE_LIMIT_EXCEEDED}.
     */
    CompletionStage<UserFileReadResult<byte[]>> readBytes(UserFileHandle handle, int maxBytes);

    /**
     * Reads at most {@code maxBytes} bytes from the handle's file, with a maximum
     * request limit of 128 MiB (134,217,728 bytes).
     *
     * <p>A larger file returns a bounded value with {@code truncated=true}; a
     * request above this 128 MiB limit returns {@link UserFileErrorCode#SIZE_LIMIT_EXCEEDED}.</p>
     *
     * <p>The default implementation provides backward compatibility: requests up to
     * 16 MiB delegate to {@link #readBytes(UserFileHandle, int)}, while requests
     * above 16 MiB complete with a structured unsupported error
     * ({@link UserFileErrorCode#RUNTIME_UNAVAILABLE}). Runtimes supporting higher
     * limits override this method.</p>
     *
     * @param handle the granted user-file handle
     * @param maxBytes the maximum number of bytes to read, up to 134,217,728 (128 MiB)
     * @return a completion stage with the read result or structured error
     */
    default CompletionStage<UserFileReadResult<byte[]>> readBytesBounded(
            final UserFileHandle handle, final int maxBytes) {
        if (maxBytes <= 16 * 1024 * 1024) {
            return readBytes(handle, maxBytes);
        }
        return CompletableFuture.completedFuture(new UserFileReadResult<>(
                Optional.empty(),
                Optional.of(new UserFileError(
                        UserFileErrorCode.RUNTIME_UNAVAILABLE,
                        "user file bounded read above 16 MiB is not supported by this service")),
                false));
    }

    /**
     * Atomically replaces the handle's file content with {@code content} as UTF-8,
     * asynchronously.
     */
    CompletionStage<UserFileWriteResult> writeUtf8Atomic(UserFileHandle handle, String content);

    /**
     * Atomically replaces the handle's file content with {@code content}, asynchronously.
     */
    CompletionStage<UserFileWriteResult> writeBytesAtomic(UserFileHandle handle, byte[] content);

    /**
     * Atomically replaces one authorized WRITE target within an explicit byte budget.
     * The budget must be positive and at most 128 MiB; content may be empty.
     * The runtime takes an immutable copy before asynchronous I/O and retains the existing
     * grant, permission, lifetime and atomic-replace checks. This operation does not create
     * a grant or accept a path. A false result guarantees the target was left unchanged.
     *
     * <p>The compatibility default delegates budgets up to 8 MiB to the original writer;
     * larger budgets fail closed as unsupported. It does not raise the original writer's limit.
     */
    default CompletionStage<UserFileWriteResult> writeBytesAtomicBounded(
            final UserFileHandle handle, final byte[] content, final int maxBytes) {
        java.util.Objects.requireNonNull(content, "content");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        if (maxBytes > 128 * 1024 * 1024 || content.length > maxBytes)
            return CompletableFuture.completedFuture(new UserFileWriteResult(
                    false,
                    Optional.of(new UserFileError(
                            UserFileErrorCode.SIZE_LIMIT_EXCEEDED,
                            "Bounded user-file write exceeds its byte budget."))));
        if (maxBytes <= 8 * 1024 * 1024) return writeBytesAtomic(handle, content);
        return CompletableFuture.completedFuture(new UserFileWriteResult(
                false,
                Optional.of(new UserFileError(
                        UserFileErrorCode.RUNTIME_UNAVAILABLE,
                        "Bounded user-file write above 8 MiB is unavailable."))));
    }

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
    static UserFileAccessService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: calls that report outcomes complete with the structured unavailability result; and queries report empty results. */
    enum Unavailable implements UserFileAccessService {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public CompletionStage<UserFileRequestResult> request(final UserFileRequest request) {
            return CompletableFuture.completedFuture(new UserFileRequestResult(
                    UserFileRequestStatus.UNAVAILABLE, Optional.empty(), Optional.of(unavailable())));
        }

        @Override
        public CompletionStage<UserFileReadResult<String>> readUtf8(final UserFileHandle handle, final int maxBytes) {
            return CompletableFuture.completedFuture(
                    new UserFileReadResult<>(Optional.empty(), Optional.of(unavailable()), false));
        }

        @Override
        public CompletionStage<UserFileReadResult<byte[]>> readBytes(final UserFileHandle handle, final int maxBytes) {
            return CompletableFuture.completedFuture(
                    new UserFileReadResult<>(Optional.empty(), Optional.of(unavailable()), false));
        }

        @Override
        public CompletionStage<UserFileReadResult<byte[]>> readBytesBounded(
                final UserFileHandle handle, final int maxBytes) {
            return CompletableFuture.completedFuture(
                    new UserFileReadResult<>(Optional.empty(), Optional.of(unavailable()), false));
        }

        @Override
        public CompletionStage<UserFileWriteResult> writeUtf8Atomic(final UserFileHandle handle, final String content) {
            return CompletableFuture.completedFuture(new UserFileWriteResult(false, Optional.of(unavailable())));
        }

        @Override
        public CompletionStage<UserFileWriteResult> writeBytesAtomic(
                final UserFileHandle handle, final byte[] content) {
            return CompletableFuture.completedFuture(new UserFileWriteResult(false, Optional.of(unavailable())));
        }

        private static UserFileError unavailable() {
            return new UserFileError(
                    UserFileErrorCode.RUNTIME_UNAVAILABLE, "user file access service is not available");
        }
    }
}
