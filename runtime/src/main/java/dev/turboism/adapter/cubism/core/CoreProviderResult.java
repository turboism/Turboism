package dev.turboism.adapter.cubism.core;

import java.util.Objects;
import java.util.Optional;

/** Closed success/failure result that never carries raw Cubism Core objects. */
public record CoreProviderResult<T>(
    Optional<T> value,
    Optional<CoreProviderFailure> failure
) {

    public CoreProviderResult {
        value = Objects.requireNonNull(value, "value");
        failure = Objects.requireNonNull(failure, "failure");
        if (value.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("exactly one of value or failure must be present");
        }
    }

    public static <T> CoreProviderResult<T> success(final T value) {
        return new CoreProviderResult<>(
            Optional.of(Objects.requireNonNull(value, "value")),
            Optional.empty()
        );
    }

    public static <T> CoreProviderResult<T> failed(final CoreProviderFailure failure) {
        return new CoreProviderResult<>(
            Optional.empty(),
            Optional.of(Objects.requireNonNull(failure, "failure"))
        );
    }

    public boolean isSuccess() {
        return value.isPresent();
    }
}
