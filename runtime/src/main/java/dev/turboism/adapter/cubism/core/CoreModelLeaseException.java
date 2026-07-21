package dev.turboism.adapter.cubism.core;

import java.util.Objects;

/** Typed internal failure while using a borrowed active-model lease. */
final class CoreModelLeaseException extends IllegalStateException {

    private final CoreModelFailure failure;

    CoreModelLeaseException(final CoreModelFailure failure) {
        super(Objects.requireNonNull(failure, "failure").message());
        this.failure = failure;
    }

    CoreModelFailure failure() {
        return failure;
    }
}
