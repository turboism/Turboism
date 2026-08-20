package dev.turboism.sdk.storage;

import java.util.Objects;

/**
 * Why a storage operation did not succeed. Carried inside a result rather
 * than thrown, so plugin code handles failure as data.
 *
 * @param code machine-readable classification callers should branch on
 * @param message human-readable detail; never blank, and not intended for
 *     programmatic matching
 * @param path the path the operation was attempting when it failed
 */
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
