package dev.turboism.sdk.ui;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;

/** Bounded form dialog descriptor (text and color fields) rendered by the runtime. */
@PreviewApi
public record FormDialogRequest(
    String id,
    String title,
    List<FormDialogField> fields,
    String acceptLabel,
    String cancelLabel,
    List<ChoiceDialogAction> actions
) {
    public FormDialogRequest {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("id must contain 1-128 characters");
        }
        Objects.requireNonNull(title, "title");
        if (title.isBlank() || title.length() > 256) {
            throw new IllegalArgumentException("title must contain 1-256 characters");
        }
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        if (fields.isEmpty() || fields.size() > 64) {
            throw new IllegalArgumentException("fields must contain between 1 and 64 entries");
        }
        if (fields.stream().map(FormDialogField::id).distinct().count() != fields.size()) {
            throw new IllegalArgumentException("field ids must be unique");
        }
        acceptLabel = requireText(acceptLabel, "acceptLabel");
        cancelLabel = requireText(cancelLabel, "cancelLabel");
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        if (actions.size() > 4) {
            throw new IllegalArgumentException("actions must contain at most 4 entries");
        }
    }

    public FormDialogRequest(
        final String id,
        final String title,
        final List<FormDialogField> fields,
        final String acceptLabel,
        final String cancelLabel
    ) {
        this(id, title, fields, acceptLabel, cancelLabel, List.of());
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException(name + " must contain 1-64 characters");
        }
        return value;
    }
}
