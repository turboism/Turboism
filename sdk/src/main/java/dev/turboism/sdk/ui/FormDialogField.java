package dev.turboism.sdk.ui;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** One bounded text or color field of a runtime-rendered form dialog. */
@PreviewApi
public record FormDialogField(
    String id,
    String label,
    String value,
    FormFieldKind kind
) {
    public FormDialogField {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("id must contain 1-128 characters");
        }
        Objects.requireNonNull(label, "label");
        if (label.isBlank() || label.length() > 128) {
            throw new IllegalArgumentException("label must contain 1-128 characters");
        }
        Objects.requireNonNull(value, "value");
        if (value.length() > 4_096) {
            throw new IllegalArgumentException("value exceeds 4096 characters");
        }
        kind = Objects.requireNonNull(kind, "kind");
    }
}
