package dev.turboism.plugin.parameter.b1.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One parameter assignment from a CSV document.
 *
 * <p>Both components are mandatory. The value is kept as a {@link java.math.BigDecimal} so the
 * text's exact decimal is preserved rather than rounded through a binary float.
 *
 * @param id the Editor parameter id, non-empty and at most 256 code points as enforced by the codec
 * @param value the value to assign, exactly as written in the document
 */
public record ParameterCsvRow(String id, BigDecimal value) {
    public ParameterCsvRow {
        id = Objects.requireNonNull(id, "id");
        value = Objects.requireNonNull(value, "value");
    }
}
