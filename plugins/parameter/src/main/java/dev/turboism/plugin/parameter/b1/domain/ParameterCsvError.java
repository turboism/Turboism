package dev.turboism.plugin.parameter.b1.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * The single first defect found while parsing a parameter CSV, located for the user.
 *
 * <p>Positions are 1-based: {@code record} counts the header as record 1, and {@code column} is the
 * 1-based character offset within the input at which the offending record or character starts.
 *
 * @param code the machine-readable defect classification
 * @param record 1-based record number the defect was found in
 * @param column 1-based character position the defect is attributed to
 * @param messageKey localization key derived from the code, for presenting the error
 * @param firstRecord for {@code DUPLICATE_ID}, the record where the id was first seen; {@code 0}
 *     for every other code
 */
public record ParameterCsvError(
    ParameterCsvErrorCode code,
    int record,
    int column,
    String messageKey,
    int firstRecord
) {
    public ParameterCsvError {
        code = Objects.requireNonNull(code, "code");
        messageKey = Objects.requireNonNull(messageKey, "messageKey");
    }

    /**
     * @param code the defect classification
     * @param record 1-based record number
     * @param column 1-based character position
     * @return the error with its message key derived from the code and no first-record reference; not
     *     for {@code DUPLICATE_ID}, which needs {@link #duplicate}
     */
    public static ParameterCsvError at(ParameterCsvErrorCode code, int record, int column) {
        return new ParameterCsvError(code, record, column, key(code), 0);
    }

    /**
     * @param record 1-based record number of the repeated id
     * @param column 1-based character position of that record
     * @param firstRecord 1-based record number where the same id was first seen
     * @return a {@code DUPLICATE_ID} error carrying both records, so the message can point at the
     *     earlier occurrence as well as the later one
     */
    public static ParameterCsvError duplicate(int record, int column, int firstRecord) {
        return new ParameterCsvError(ParameterCsvErrorCode.DUPLICATE_ID, record, column,
            key(ParameterCsvErrorCode.DUPLICATE_ID), firstRecord);
    }

    private static String key(ParameterCsvErrorCode code) {
        return "parameter.csv.error." + code.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
