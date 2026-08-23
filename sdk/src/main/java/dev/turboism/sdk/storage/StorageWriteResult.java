package dev.turboism.sdk.storage;

import java.util.Optional;

/**
 * Outcome of an atomic write.
 *
 * @param written whether the file now holds the new content; exactly one of
 *     this flag and the error is set
 * @param error present exactly when nothing was written
 */
public record StorageWriteResult(
    boolean written,
    Optional<StorageError> error
) {
    public StorageWriteResult {
        error = StorageContracts.requireOptional(error, "error");
        StorageContracts.validateWrite(written, error);
    }
}
