package dev.turboism.sdk.storage;

import java.util.Optional;

/**
 * Outcome of a copy, move, or delete.
 *
 * @param changed whether the filesystem was modified; an unchanged result
 *     always carries an error
 * @param error present on failure, and additionally on a changed result
 *     only for {@link StorageErrorCode#PARTIAL_DELETE}, where some but not
 *     all of a recursive delete succeeded
 */
public record StorageMutationResult(
    boolean changed,
    Optional<StorageError> error
) {
    public StorageMutationResult {
        error = StorageContracts.requireOptional(error, "error");
        StorageContracts.validateMutation(changed, error);
    }
}
