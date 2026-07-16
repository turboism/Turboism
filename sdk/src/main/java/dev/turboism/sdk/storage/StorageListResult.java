package dev.turboism.sdk.storage;

import java.util.List;
import java.util.Optional;

public record StorageListResult(
    List<StorageEntry> entries,
    Optional<StorageError> error,
    boolean truncated
) {
    public StorageListResult {
        entries = StorageContracts.copy(entries, "entries");
        error = StorageContracts.requireOptional(error, "error");
        if (error.isPresent() && (!entries.isEmpty() || truncated)) {
            throw new IllegalArgumentException(
                "failed storage list must contain no entries and must not be truncated"
            );
        }
    }
}
