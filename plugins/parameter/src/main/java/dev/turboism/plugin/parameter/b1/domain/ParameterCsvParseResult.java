package dev.turboism.plugin.parameter.b1.domain;

import java.util.List;
import java.util.Optional;

public record ParameterCsvParseResult(List<ParameterCsvRow> rows, Optional<ParameterCsvError> error) {
    public ParameterCsvParseResult {
        rows = List.copyOf(rows);
        error = error == null ? Optional.empty() : error;
    }

    public boolean valid() {
        return error.isEmpty();
    }
}
