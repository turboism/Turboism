package dev.turboism.sdk.storage;

import java.util.Optional;

public record StorageWriteResult(
    boolean written,
    Optional<StorageError> error
) {
    public StorageWriteResult {
        error = StorageContracts.requireOptional(error, "error");
        StorageContracts.validateWrite(written, error);
    }
}
