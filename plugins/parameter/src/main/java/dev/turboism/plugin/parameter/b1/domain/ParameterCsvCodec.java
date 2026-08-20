package dev.turboism.plugin.parameter.b1.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads and writes the {@code id,value} parameter CSV exchange format.
 *
 * <p>Parsing fails closed on the first defect and reports it as a value rather than an exception,
 * so a malformed file never reaches the Editor half-applied. Hard limits are enforced before any
 * work: at most 1,000,000 input characters and 10,000 data records. Quoting follows RFC 4180 (a
 * doubled {@code ""} is a literal quote); a lone {@code CR} not followed by {@code LF} is rejected
 * as a forbidden control character. Stateless and safe to call from any thread.
 */
public final class ParameterCsvCodec {
    private static final int MAX_INPUT = 1_000_000;
    private static final int MAX_ROWS = 10_000;
    private static final Pattern DECIMAL = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");

    private ParameterCsvCodec() {
    }

    /**
     * Parses the full document, validating the header, every field and every id.
     *
     * <p>Requires the exact header {@code id,value}. Each data record must have two fields; the id must
     * be non-empty, at most 256 code points, free of control characters, and unique across the document
     * (a repeat reports the record where the id was first seen). The value must match a plain decimal
     * with no leading zeros or exponent, and {@code -0} is rejected explicitly so that negative zero
     * cannot round-trip.
     *
     * @param input the whole CSV document; {@code null} is treated as an input-limit failure rather
     *     than throwing
     * @return the parsed rows in document order with no error, or an empty row list and the single
     *     first error, located by 1-based record and column
     */
    public static ParameterCsvParseResult parse(final String input) {
        if (input == null || input.length() > MAX_INPUT) return failure(ParameterCsvError.at(ParameterCsvErrorCode.INPUT_LIMIT, 1, 1));
        final ParseRecords parsed = records(input);
        if (parsed.error() != null) return failure(parsed.error());
        if (parsed.records().isEmpty() || !parsed.records().get(0).fields().equals(List.of("id", "value"))) {
            return failure(ParameterCsvError.at(ParameterCsvErrorCode.HEADER_INVALID, 1, 1));
        }
        if (parsed.records().size() - 1 > MAX_ROWS) return failure(ParameterCsvError.at(ParameterCsvErrorCode.ROW_LIMIT, MAX_ROWS + 2, input.length()));
        final List<ParameterCsvRow> rows = new ArrayList<>();
        final Map<String, Integer> firstRecord = new HashMap<>();
        for (int index = 1; index < parsed.records().size(); index++) {
            final Record record = parsed.records().get(index);
            if (record.fields().size() == 1 && record.fields().get(0).isEmpty()) return failure(ParameterCsvError.at(ParameterCsvErrorCode.RECORD_BLANK, index + 1, record.column()));
            if (record.fields().size() != 2) return failure(ParameterCsvError.at(ParameterCsvErrorCode.FIELD_COUNT, index + 1, record.column()));
            final String id = record.fields().get(0);
            if (id.isEmpty()) return failure(ParameterCsvError.at(ParameterCsvErrorCode.ID_EMPTY, index + 1, record.column()));
            if (id.codePointCount(0, id.length()) > 256) return failure(ParameterCsvError.at(ParameterCsvErrorCode.ID_LIMIT, index + 1, record.column()));
            if (hasForbiddenControl(id)) return failure(ParameterCsvError.at(ParameterCsvErrorCode.CONTROL_FORBIDDEN, index + 1, record.column()));
            final String rawValue = record.fields().get(1);
            if (!DECIMAL.matcher(rawValue).matches()) return failure(ParameterCsvError.at(ParameterCsvErrorCode.VALUE_INVALID, index + 1, record.column()));
            final BigDecimal value = new BigDecimal(rawValue);
            if (rawValue.startsWith("-") && value.compareTo(BigDecimal.ZERO) == 0) return failure(ParameterCsvError.at(ParameterCsvErrorCode.VALUE_NEGATIVE_ZERO, index + 1, record.column()));
            final Integer first = firstRecord.putIfAbsent(id, index + 1);
            if (first != null) return failure(ParameterCsvError.duplicate(index + 1, record.column(), first));
            rows.add(new ParameterCsvRow(id, value));
        }
        return new ParameterCsvParseResult(rows, Optional.empty());
    }

    /**
     * Renders rows as a CSV document, sorted by id so output is deterministic regardless of input
     * order.
     *
     * <p>Always emits the {@code id,value} header and terminates every record with {@code LF}. Values
     * are written in plain notation with trailing zeros stripped, and zero is normalised to {@code 0}.
     * Ids are quoted only when they contain a comma, quote or line break. The caller's list is copied
     * before sorting, so it is not reordered.
     *
     * @param input the rows to write; {@code null} produces a header-only document
     * @return the CSV text, always ending in a newline
     * @throws IllegalArgumentException if a row's id is empty, longer than 256 code points, or contains
     *     a forbidden control character — a programming error, since {@link #parse} cannot produce one
     */
    public static String serialize(final List<ParameterCsvRow> input) {
        final List<ParameterCsvRow> rows = new ArrayList<>(input == null ? List.of() : input);
        rows.sort(Comparator.comparing(ParameterCsvRow::id));
        final StringBuilder result = new StringBuilder("id,value\n");
        for (ParameterCsvRow row : rows) {
            validateProgrammatic(row);
            result.append(field(row.id())).append(',').append(decimal(row.value())).append('\n');
        }
        return result.toString();
    }

    private static ParseRecords records(final String input) {
        final List<Record> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        int recordColumn = 1;
        for (int index = 0; index < input.length(); index++) {
            final char value = input.charAt(index);
            if (quoted) {
                if (value == '"') {
                    if (index + 1 < input.length() && input.charAt(index + 1) == '"') { field.append('"'); index++; }
                    else { quoted = false; closedQuote = true; }
                } else field.append(value);
                continue;
            }
            if (closedQuote && value != ',' && value != '\r' && value != '\n') return new ParseRecords(List.of(), ParameterCsvError.at(ParameterCsvErrorCode.QUOTE_TRAILING, records.size() + 1, index + 1));
            if (value == '"') {
                if (field.length() != 0) return new ParseRecords(List.of(), ParameterCsvError.at(ParameterCsvErrorCode.QUOTE_TRAILING, records.size() + 1, index + 1));
                quoted = true; closedQuote = false;
            } else if (value == ',') {
                fields.add(field.toString()); field = new StringBuilder(); closedQuote = false;
            } else if (value == '\r') {
                if (index + 1 >= input.length() || input.charAt(index + 1) != '\n') {
                    return new ParseRecords(List.of(), ParameterCsvError.at(
                        ParameterCsvErrorCode.CONTROL_FORBIDDEN, records.size() + 1, index + 1
                    ));
                }
                fields.add(field.toString()); records.add(new Record(List.copyOf(fields), recordColumn));
                fields = new ArrayList<>(); field = new StringBuilder(); closedQuote = false;
                index++;
                recordColumn = index + 2;
            } else if (value == '\n') {
                fields.add(field.toString()); records.add(new Record(List.copyOf(fields), recordColumn));
                fields = new ArrayList<>(); field = new StringBuilder(); closedQuote = false;
                recordColumn = index + 2;
            } else field.append(value);
        }
        if (quoted) return new ParseRecords(List.of(), ParameterCsvError.at(ParameterCsvErrorCode.QUOTE_UNCLOSED, records.size() + 1, input.length()));
        if (!fields.isEmpty() || field.length() > 0 || input.isEmpty() || (!input.endsWith("\n") && !input.endsWith("\r"))) {
            fields.add(field.toString()); records.add(new Record(List.copyOf(fields), recordColumn));
        }
        return new ParseRecords(records, null);
    }

    private static void validateProgrammatic(ParameterCsvRow row) {
        if (row.id().isEmpty() || row.id().codePointCount(0, row.id().length()) > 256 || hasForbiddenControl(row.id())) throw new IllegalArgumentException("invalid parameter id");
    }

    private static boolean hasForbiddenControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character == 0 || (Character.isISOControl(character) && character != '\r' && character != '\n')) return true;
        }
        return false;
    }

    private static String decimal(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    private static String field(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static ParameterCsvParseResult failure(ParameterCsvError error) { return new ParameterCsvParseResult(List.of(), Optional.of(error)); }
    private record Record(List<String> fields, int column) { }
    private record ParseRecords(List<Record> records, ParameterCsvError error) { }
}
