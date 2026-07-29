package dev.turboism.sdk.ui;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** One bounded SDK-owned option rendered by a runtime-owned choice dialog. */
@PreviewApi
public record ChoiceDialogOption(
    String id,
    String label,
    String detail,
    boolean enabled
) {
    public ChoiceDialogOption {
        id = requireText(id, "id", 128);
        label = requireText(label, "label", 256);
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.length() > 4_096) {
            throw new IllegalArgumentException("detail exceeds 4096 characters");
        }
    }

    private static String requireText(final String value, final String name, final int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be bounded non-blank text");
        }
        return value;
    }
}
