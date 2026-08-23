package dev.turboism.sdk.ui;


import java.util.Objects;

/** One structured label/value row rendered in a runtime choice-dialog detail panel. */
public record ChoiceDialogDetailRow(
    String label,
    String value,
    String url
) {
    public ChoiceDialogDetailRow {
        Objects.requireNonNull(label, "label");
        if (label.isBlank() || label.length() > 128) {
            throw new IllegalArgumentException("label must contain 1-128 characters");
        }
        Objects.requireNonNull(value, "value");
        if (value.length() > 4_096) {
            throw new IllegalArgumentException("value exceeds 4096 characters");
        }
        url = url == null ? "" : url;
        if (url.length() > 2_048) {
            throw new IllegalArgumentException("url exceeds 2048 characters");
        }
    }
}
