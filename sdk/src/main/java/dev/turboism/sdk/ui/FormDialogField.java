package dev.turboism.sdk.ui;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;

/** One bounded text, select, or color field of a runtime-rendered form dialog. */
@PreviewApi
public record FormDialogField(
    String id,
    String label,
    String value,
    FormFieldKind kind,
    List<String> options
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
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        if (options.size() > 32) {
            throw new IllegalArgumentException("options must contain at most 32 entries");
        }
        if (kind == FormFieldKind.SELECT && options.isEmpty()) {
            throw new IllegalArgumentException("select fields require at least one option");
        }
    }

    public FormDialogField(
        final String id,
        final String label,
        final String value,
        final FormFieldKind kind
    ) {
        this(id, label, value, kind, List.of());
    }
}
