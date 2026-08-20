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

    /**
     * @param <T> carried value type
     * @param value the produced value, never null
     * @return a success result with no failure
     * @throws NullPointerException if {@code value} is null
     */
    public static <T> CoreProviderResult<T> success(final T value) {
        return new CoreProviderResult<>(
            Optional.of(Objects.requireNonNull(value, "value")),
            Optional.empty()
        );
    }

    /**
     * @param <T> carried value type
     * @param failure why the provider could not be produced
     * @return a failure result with no value
     * @throws NullPointerException if {@code failure} is null
     */
    public static <T> CoreProviderResult<T> failed(final CoreProviderFailure failure) {
        return new CoreProviderResult<>(
            Optional.empty(),
            Optional.of(Objects.requireNonNull(failure, "failure"))
        );
    }

    /**
     * @return true when this result carries a value; the compact constructor guarantees value and
     *     failure are never both present nor both absent, so a false answer means a failure is present
     */
    public boolean isSuccess() {
        return value.isPresent();
    }
}
