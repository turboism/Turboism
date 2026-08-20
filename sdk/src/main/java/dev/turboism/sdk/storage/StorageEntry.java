package dev.turboism.sdk.storage;

import java.util.Objects;

/**
 * One child observed by {@link PluginStorage#list}.
 *
 * @param path location of the entry within a granted root
 * @param type whether the entry is a file or a directory
 * @param sizeBytes size in bytes as reported by the host filesystem; never
 *     negative
 */
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
