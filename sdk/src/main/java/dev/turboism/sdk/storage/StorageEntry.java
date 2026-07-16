package dev.turboism.sdk.storage;

import java.util.Objects;

public record StorageEntry(
    StoragePath path,
    StorageEntryType type,
    long sizeBytes
) {
    public StorageEntry {
        path = Objects.requireNonNull(path, "path");
        type = Objects.requireNonNull(type, "type");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
