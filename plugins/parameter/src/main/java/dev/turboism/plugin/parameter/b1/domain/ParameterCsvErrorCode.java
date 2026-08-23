package dev.turboism.plugin.parameter.b1.domain;

/**
 * Classification of a parameter CSV defect.
 *
 * <p>Each constant maps to a localization key of the form
 * {@code parameter.csv.error.<name-in-kebab-case>}. Only {@code DUPLICATE_ID} carries a second
 * record reference; the rest are located by a single position.
 */
public enum ParameterCsvErrorCode {
    INPUT_LIMIT,
    ROW_LIMIT,
    HEADER_INVALID,
    RECORD_BLANK,
    FIELD_COUNT,
    QUOTE_UNCLOSED,
    QUOTE_TRAILING,
    CONTROL_FORBIDDEN,
    ID_EMPTY,
    ID_LIMIT,
    VALUE_INVALID,
    VALUE_NEGATIVE_ZERO,
    DUPLICATE_ID
}
