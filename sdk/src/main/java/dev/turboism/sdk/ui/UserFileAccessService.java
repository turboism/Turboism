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

    CompletionStage<UserFileRequestResult> request(UserFileRequest request);

    CompletionStage<UserFileReadResult<String>> readUtf8(
        UserFileHandle handle,
        int maxBytes
    );

    CompletionStage<UserFileReadResult<byte[]>> readBytes(
        UserFileHandle handle,
        int maxBytes
    );

    CompletionStage<UserFileWriteResult> writeUtf8Atomic(
        UserFileHandle handle,
        String content
    );

    CompletionStage<UserFileWriteResult> writeBytesAtomic(
        UserFileHandle handle,
        byte[] content
    );

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    static UserFileAccessService unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements UserFileAccessService {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public CompletionStage<UserFileRequestResult> request(
            final UserFileRequest request
        ) {
            return CompletableFuture.completedFuture(new UserFileRequestResult(
                UserFileRequestStatus.UNAVAILABLE,
                Optional.empty(),
                Optional.of(unavailable())
            ));
        }

        @Override public CompletionStage<UserFileReadResult<String>> readUtf8(
            final UserFileHandle handle,
            final int maxBytes
        ) {
            return CompletableFuture.completedFuture(new UserFileReadResult<>(
                Optional.empty(), Optional.of(unavailable()), false));
        }

        @Override public CompletionStage<UserFileReadResult<byte[]>> readBytes(
            final UserFileHandle handle,
            final int maxBytes
        ) {
            return CompletableFuture.completedFuture(new UserFileReadResult<>(
                Optional.empty(), Optional.of(unavailable()), false));
        }

        @Override public CompletionStage<UserFileWriteResult> writeUtf8Atomic(
            final UserFileHandle handle,
            final String content
        ) {
            return CompletableFuture.completedFuture(
                new UserFileWriteResult(false, Optional.of(unavailable())));
        }

        @Override public CompletionStage<UserFileWriteResult> writeBytesAtomic(
            final UserFileHandle handle,
            final byte[] content
        ) {
            return CompletableFuture.completedFuture(
                new UserFileWriteResult(false, Optional.of(unavailable())));
        }

        private static UserFileError unavailable() {
            return new UserFileError(
                UserFileErrorCode.RUNTIME_UNAVAILABLE,
                "user file access service is not available"
            );
        }
    }
}
