package dev.turboism.core.runtime;

import java.time.Duration;
import java.util.Objects;

/** Immutable bound for waiting for owned work or a concurrent close to finish.
 * @param timeout positive maximum wait
 */
public record QuiescencePolicy(Duration timeout) {
    public QuiescencePolicy {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}
