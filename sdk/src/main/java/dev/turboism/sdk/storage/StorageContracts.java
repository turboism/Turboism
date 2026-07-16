package dev.turboism.sdk.storage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class StorageContracts {

    private StorageContracts() {
    }

    static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static <T> Optional<T> requireOptional(final Optional<T> value, final String name) {
        return Objects.requireNonNull(value, name);
    }

    static <T> List<T> copy(final List<T> value, final String name) {
        return List.copyOf(Objects.requireNonNull(value, name));
    }

    static void validateRead(
        final boolean hasValue,
        final Optional<StorageError> error,
        final boolean truncated
    ) {
        if (hasValue == error.isPresent()) {
            throw new IllegalArgumentException(
                "storage read must contain exactly one of value or error"
            );
        }
        if (error.isPresent() && truncated) {
            throw new IllegalArgumentException(
                "failed storage read must not be truncated"
            );
        }
    }

    static void validateWrite(
        final boolean written,
        final Optional<StorageError> error
    ) {
        if (written == error.isPresent()) {
            throw new IllegalArgumentException(
                "storage write success/error algebra is invalid"
            );
        }
    }

    static void validateMutation(
        final boolean changed,
        final Optional<StorageError> error
    ) {
        if (!changed && error.isEmpty()) {
            throw new IllegalArgumentException(
                "unchanged storage mutation requires an error"
            );
        }
        if (changed && error.isPresent()
            && error.orElseThrow().code() != StorageErrorCode.PARTIAL_DELETE) {
            throw new IllegalArgumentException(
                "changed storage mutation may contain only PARTIAL_DELETE"
            );
        }
    }
}
