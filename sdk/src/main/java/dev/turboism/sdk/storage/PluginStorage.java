package dev.turboism.sdk.storage;

import java.util.concurrent.CompletionStage;

/**
 * Sandboxed filesystem access granted to a plugin, rooted at the three
 * {@link StorageRoot} directories the runtime owns on the plugin's behalf.
 *
 * <p>Every operation is asynchronous and never throws for an I/O or policy
 * problem: failures are reported inside the returned result as a
 * {@link StorageError}. Paths outside the granted roots are rejected by
 * {@link StoragePath} construction rather than followed.</p>
 */
public interface PluginStorage {

    /**
     * Reads a file as UTF-8 text.
     *
     * @param path file to read, inside a granted root
     * @param maxBytes ceiling on bytes read; a longer file yields a truncated
     *     result rather than an error
     * @return the decoded text, or a {@link StorageError} when the file is
     *     missing, is not a regular file, or the read fails
     */
    CompletionStage<StorageReadResult<String>> readUtf8(
        StoragePath path,
        int maxBytes
    );

    /**
     * Reads a file as raw bytes.
     *
     * @param path file to read, inside a granted root
     * @param maxBytes ceiling on bytes read; a longer file yields a truncated
     *     result rather than an error
     * @return the bytes, or a {@link StorageError} when the read fails; the
     *     carried array is cloned on every access, so callers never share a
     *     mutable buffer
     */
    CompletionStage<StorageReadResult<byte[]>> readBytes(
        StoragePath path,
        int maxBytes
    );

    /**
     * Replaces a file with the given text, encoded as UTF-8, atomically: a
     * concurrent reader observes either the previous or the new content,
     * never a partial write.
     *
     * @param path file to write, inside a granted root
     * @param content text to store
     * @return a written result, or a {@link StorageError} such as
     *     {@link StorageErrorCode#ATOMIC_REPLACE_UNAVAILABLE} when the host
     *     filesystem cannot guarantee atomicity
     */
    CompletionStage<StorageWriteResult> writeUtf8Atomic(
        StoragePath path,
        String content
    );

    /**
     * Replaces a file with the given bytes atomically.
     *
     * @param path file to write, inside a granted root
     * @param content bytes to store
     * @return a written result, or a {@link StorageError} describing why the
     *     atomic replace could not be completed
     */
    CompletionStage<StorageWriteResult> writeBytesAtomic(
        StoragePath path,
        byte[] content
    );

    /**
     * Lists the immediate children of a directory; the listing does not
     * recurse.
     *
     * @param directory directory to enumerate, inside a granted root
     * @param maxEntries ceiling on returned entries; exceeding it produces a
     *     truncated listing rather than an error
     * @return the entries, or a {@link StorageError}; a failed listing carries
     *     no entries and is never marked truncated
     */
    CompletionStage<StorageListResult> list(
        StoragePath directory,
        int maxEntries
    );

    /**
     * Copies a file or directory to another location under a granted root.
     *
     * @param source existing path to copy from
     * @param target path to create
     * @param replaceExisting when {@code false}, an existing target fails with
     *     {@link StorageErrorCode#ALREADY_EXISTS} instead of being overwritten
     * @return whether anything changed, plus the error when it did not
     */
    CompletionStage<StorageMutationResult> copy(
        StoragePath source,
        StoragePath target,
        boolean replaceExisting
    );

    /**
     * Moves a path atomically. A move between two different
     * {@link StorageRoot}s cannot be made atomic and is reported as
     * {@link StorageErrorCode#CROSS_ROOT_ATOMIC_MOVE_UNSUPPORTED}.
     *
     * @param source existing path to move from
     * @param target path to move to
     * @param replaceExisting when {@code false}, an existing target fails with
     *     {@link StorageErrorCode#ALREADY_EXISTS}
     * @return whether anything changed, plus the error when it did not
     */
    CompletionStage<StorageMutationResult> moveAtomic(
        StoragePath source,
        StoragePath target,
        boolean replaceExisting
    );

    /**
     * Deletes a file or directory.
     *
     * @param path path to remove
     * @param recursive when {@code false}, a non-empty directory is not
     *     removed; when {@code true}, a removal that completes only in part is
     *     reported as changed with a
     *     {@link StorageErrorCode#PARTIAL_DELETE} error
     * @return whether anything changed, plus the error when it did not
     */
    CompletionStage<StorageMutationResult> delete(
        StoragePath path,
        boolean recursive
    );
}
