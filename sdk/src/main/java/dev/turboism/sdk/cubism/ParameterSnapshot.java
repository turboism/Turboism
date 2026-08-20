package dev.turboism.sdk.cubism;

import java.util.Objects;

/**
 * Immutable snapshot of one model parameter and its value range at the moment of capture.
 *
 * <p>The range is recorded as the host reported it; this record does not verify that
 * {@code minValue <= value <= maxValue} and callers must not assume it.</p>
 *
 * @param id stable Editor-assigned parameter identifier
 * @param name display name of the parameter in the Editor's parameter palette
 * @param value the parameter's value at capture time
 * @param defaultValue the value the Editor restores when the parameter is reset
 * @param minValue lower bound of the parameter's declared range
 * @param maxValue upper bound of the parameter's declared range
 * @param visible whether the parameter is shown in the parameter palette
 * @param editable whether the user may change the value; a visible parameter may still be locked
 */
public record ParameterSnapshot(
    String id,
    String name,
    double value,
    double defaultValue,
    double minValue,
    double maxValue,
    boolean visible,
    boolean editable
) implements ModelObjectSnapshot {
    public ParameterSnapshot {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
    }
}
