package dev.turboism.sdk.ui;

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
    CompletionStage<UserFileReadResult<String>> readUtf8(
        UserFileHandle handle,
        int maxBytes
    );

    /**
     * Reads up to {@code maxBytes} of the handle's file as raw bytes, asynchronously.
     */
    CompletionStage<UserFileReadResult<byte[]>> readBytes(
        UserFileHandle handle,
        int maxBytes
    );

    /**
     * Atomically replaces the handle's file content with {@code content} as UTF-8,
     * asynchronously.
     */
    CompletionStage<UserFileWriteResult> writeUtf8Atomic(
        UserFileHandle handle,
        String content
    );

    /**
     * Atomically replaces the handle's file content with {@code content}, asynchronously.
     */
    CompletionStage<UserFileWriteResult> writeBytesAtomic(
        UserFileHandle handle,
        byte[] content
    );
}
