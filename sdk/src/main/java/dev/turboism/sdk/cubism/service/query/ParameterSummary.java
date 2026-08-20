package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.Objects;

/**
 * A point-in-time view of one model parameter as the Editor reports it.
 *
 * <p>Immutable: {@code currentValue} is the value read when the summary was made and does not follow
 * later edits.
 *
 * @param id stable identifier of the parameter
 * @param name the Editor-assigned display name; may be empty but never {@code null}
 * @param currentValue the parameter's value at capture time
 * @param bounds the allowed range and rest value declared for the parameter
 * @param visible whether the Editor shows the parameter in the parameter panel
 * @param editable whether the Editor permits the user to change the value
 */
public record ParameterSummary(
    ParameterId id,
    String name,
    double currentValue,
    ParameterBounds bounds,
    boolean visible,
    boolean editable
) {
    public ParameterSummary {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        bounds = Objects.requireNonNull(bounds, "bounds");
    }

    /** @return the lowest value the parameter may take, from {@link #bounds()} */
    public double minValue() {
        return bounds.minValue();
    }

    /** @return the highest value the parameter may take, from {@link #bounds()} */
    public double maxValue() {
        return bounds.maxValue();
    }

    /** @return the parameter's rest value, what it returns to when reset, from {@link #bounds()} */
    public double defaultValue() {
        return bounds.defaultValue();
    }
}
