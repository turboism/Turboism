package dev.turboism.sdk.ui;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** One secondary action button rendered inside a runtime choice dialog. */
@PreviewApi
public record ChoiceDialogAction(String id, String label) {
    public ChoiceDialogAction {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("id must contain 1-64 characters");
        }
        Objects.requireNonNull(label, "label");
        if (label.isBlank() || label.length() > 64) {
            throw new IllegalArgumentException("label must contain 1-64 characters");
        }
    }
}
