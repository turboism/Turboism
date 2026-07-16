package dev.turboism.sdk.storage;

import java.util.concurrent.CompletionStage;

public interface PluginStorage {

    CompletionStage<StorageReadResult<String>> readUtf8(
        StoragePath path,
        int maxBytes
    );

    CompletionStage<StorageReadResult<byte[]>> readBytes(
        StoragePath path,
        int maxBytes
    );

    CompletionStage<StorageWriteResult> writeUtf8Atomic(
        StoragePath path,
        String content
    );

    CompletionStage<StorageWriteResult> writeBytesAtomic(
        StoragePath path,
        byte[] content
    );

    CompletionStage<StorageListResult> list(
        StoragePath directory,
        int maxEntries
    );

    CompletionStage<StorageMutationResult> copy(
        StoragePath source,
        StoragePath target,
        boolean replaceExisting
    );

    CompletionStage<StorageMutationResult> moveAtomic(
        StoragePath source,
        StoragePath target,
        boolean replaceExisting
    );

    CompletionStage<StorageMutationResult> delete(
        StoragePath path,
        boolean recursive
    );
}
