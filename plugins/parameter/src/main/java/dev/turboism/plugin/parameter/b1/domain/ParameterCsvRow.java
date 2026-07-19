package dev.turboism.plugin.parameter.b1.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record ParameterCsvRow(String id, BigDecimal value) {
    public ParameterCsvRow {
        id = Objects.requireNonNull(id, "id");
        value = Objects.requireNonNull(value, "value");
    }
}
