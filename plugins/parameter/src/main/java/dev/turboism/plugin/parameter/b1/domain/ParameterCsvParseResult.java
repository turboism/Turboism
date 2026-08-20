package dev.turboism.plugin.parameter.b1.domain;

import java.util.List;
import java.util.Optional;

/**
 * The result of parsing a parameter CSV: either every row, or the single first error.
 *
 * <p>The two are mutually exclusive by construction of the codec — a failed parse carries no rows.
 * The compact constructor copies the rows into an immutable list and normalises a {@code null}
 * error to {@link java.util.Optional#empty()}.
 *
 * @param rows the parsed rows in document order, immutable and empty on failure
 * @param error the first defect found, empty on success
 */
public record ParameterCsvParseResult(List<ParameterCsvRow> rows, Optional<ParameterCsvError> error) {
    public ParameterCsvParseResult {
        rows = List.copyOf(rows);
        error = error == null ? Optional.empty() : error;
    }

    /** @return whether the document parsed cleanly, so {@link #rows()} may be applied as-is. */
    public boolean valid() {
        return error.isEmpty();
    }
}
