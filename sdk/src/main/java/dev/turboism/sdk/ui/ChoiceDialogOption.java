package dev.turboism.sdk.ui;


import java.util.List;
import java.util.Objects;

/** One bounded SDK-owned option rendered by a runtime-owned choice dialog. */
public record ChoiceDialogOption(
    String id,
    String label,
    String detail,
    boolean enabled,
    List<ChoiceDialogDetailRow> detailRows
) {
    public ChoiceDialogOption {
        id = requireText(id, "id", 128);
        label = requireText(label, "label", 256);
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.length() > 4_096) {
            throw new IllegalArgumentException("detail exceeds 4096 characters");
        }
        detailRows = List.copyOf(Objects.requireNonNull(detailRows, "detailRows"));
        if (detailRows.size() > 12) {
            throw new IllegalArgumentException("detailRows must contain at most 12 entries");
        }
    }

    public ChoiceDialogOption(
        final String id,
        final String label,
        final String detail,
        final boolean enabled
    ) {
        this(id, label, detail, enabled, List.of());
    }

    private static String requireText(final String value, final String name, final int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be bounded non-blank text");
        }
        return value;
    }
}
