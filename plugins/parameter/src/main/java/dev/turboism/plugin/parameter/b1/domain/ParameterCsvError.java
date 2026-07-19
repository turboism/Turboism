package dev.turboism.plugin.parameter.b1.domain;

import java.util.Locale;
import java.util.Objects;

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

    public static ParameterCsvError at(ParameterCsvErrorCode code, int record, int column) {
        return new ParameterCsvError(code, record, column, key(code), 0);
    }

    public static ParameterCsvError duplicate(int record, int column, int firstRecord) {
        return new ParameterCsvError(ParameterCsvErrorCode.DUPLICATE_ID, record, column,
            key(ParameterCsvErrorCode.DUPLICATE_ID), firstRecord);
    }

    private static String key(ParameterCsvErrorCode code) {
        return "parameter.csv.error." + code.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
