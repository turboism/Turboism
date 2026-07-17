package dev.turboism.sdk.storage;

import java.util.Optional;

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
