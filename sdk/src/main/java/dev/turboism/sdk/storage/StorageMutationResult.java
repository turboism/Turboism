package dev.turboism.sdk.storage;

import java.util.Optional;

public record StorageMutationResult(
    boolean changed,
    Optional<StorageError> error
) {
    public StorageMutationResult {
        error = StorageContracts.requireOptional(error, "error");
        StorageContracts.validateMutation(changed, error);
    }
}
