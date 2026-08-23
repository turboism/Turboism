package dev.turboism.sdk.storage;

import java.util.Optional;

/**
 * Outcome of a read, carrying exactly one of a value or an error.
 *
 * <p>When the value is a {@code byte[]} it is cloned on construction and
 * again on every {@link #value()} call, so the result never shares a
 * mutable buffer with the runtime or with other callers.</p>
 *
 * @param <T> payload type, typically {@code String} or {@code byte[]}
 * @param value the content read; empty exactly when an error is present
 * @param error present exactly when no value was read
 * @param truncated whether the byte ceiling cut the read short; always
 *     {@code false} on failure
 */
public record StorageReadResult<T>(
    Optional<T> value,
    Optional<StorageError> error,
    boolean truncated
) {
    public StorageReadResult {
        value = defensiveValue(StorageContracts.requireOptional(value, "value"));
        error = StorageContracts.requireOptional(error, "error");
        StorageContracts.validateRead(value.isPresent(), error, truncated);
    }

    @Override
    public Optional<T> value() {
        return defensiveValue(value);
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<T> defensiveValue(final Optional<T> value) {
        if (value.isPresent() && value.orElseThrow() instanceof byte[] bytes) {
            return Optional.of((T) bytes.clone());
        }
        return value;
    }
}
