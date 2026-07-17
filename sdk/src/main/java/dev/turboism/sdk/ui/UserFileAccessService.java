package dev.turboism.sdk.ui;

import java.util.concurrent.CompletionStage;

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
}
