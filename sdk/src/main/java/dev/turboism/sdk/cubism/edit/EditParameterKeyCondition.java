package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry of the keyform-condition list accepted by object read and edit requests.
 *
 * <p>Mirrors the official {@code Parameters[]} selector: each entry pairs a parameter id with a
 * key value so the host can resolve which keyform state the request applies to. An empty list on
 * a request applies the operation at the default keyform. {@code IsExactMatch} on the request
 * controls whether partially matching conditions count.
 *
 * @param parameter the parameter to condition on; empty entries widen the condition
 * @param value the key value to condition on; empty entries widen the condition
 */
public record EditParameterKeyCondition(Optional<ParameterId> parameter, Optional<Double> value) {

    public EditParameterKeyCondition {
        Objects.requireNonNull(parameter, "parameter");
        Objects.requireNonNull(value, "value");
        value.ifPresent(v -> {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("value must be finite");
            }
        });
    }

    /** Returns a condition pinning one parameter to one key value. */
    public static EditParameterKeyCondition of(final ParameterId parameter, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
        return new EditParameterKeyCondition(
            Optional.of(Objects.requireNonNull(parameter, "parameter")), Optional.of(value));
    }
}
