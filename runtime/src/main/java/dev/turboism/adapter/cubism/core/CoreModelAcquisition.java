package dev.turboism.adapter.cubism.core;

import java.util.Objects;
import java.util.Optional;

/** Closed acquisition result; failures never masquerade as an empty active model. */
record CoreModelAcquisition(
    Optional<CoreModelLease> lease,
    Optional<CoreModelFailure> failure
) {

    CoreModelAcquisition {
        lease = Objects.requireNonNull(lease, "lease");
        failure = Objects.requireNonNull(failure, "failure");
        if (lease.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("exactly one of lease or failure must be present");
        }
    }

    static CoreModelAcquisition acquired(final CoreModelLease lease) {
        return new CoreModelAcquisition(
            Optional.of(Objects.requireNonNull(lease, "lease")),
            Optional.empty()
        );
    }

    static CoreModelAcquisition failed(
        final CoreModelFailure.Code code,
        final String message
    ) {
        return new CoreModelAcquisition(
            Optional.empty(),
            Optional.of(new CoreModelFailure(code, message))
        );
    }

    boolean isAcquired() {
        return lease.isPresent();
    }
}
