package dev.turboism.sdk.storage;

import java.util.Objects;

public record StorageError(
    StorageErrorCode code,
    String message,
    StoragePath path
) {
    public StorageError {
        code = Objects.requireNonNull(code, "code");
        message = StorageContracts.requireText(message, "message");
        path = Objects.requireNonNull(path, "path");
    }
}
