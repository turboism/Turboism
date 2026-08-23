package dev.turboism.sdk.storage;

import java.util.List;
import java.util.Optional;

/**
 * Outcome of a directory listing.
 *
 * @param entries immediate children found, defensively copied and
 *     unmodifiable; empty when the listing failed
 * @param error present only when the listing failed
 * @param truncated whether the requested entry ceiling was reached and
 *     further children exist; always {@code false} on failure
 */
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
