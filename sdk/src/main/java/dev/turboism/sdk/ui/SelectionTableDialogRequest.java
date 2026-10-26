package dev.turboism.sdk.ui;

import dev.turboism.sdk.ui.resource.UiIconRef;
import java.util.List;
import java.util.Objects;

/** Immutable preview table: native type icon, original text, proposed text and selection. */
public record SelectionTableDialogRequest(
        String id,
        String title,
        String hint,
        List<String> columns,
        List<Row> rows,
        String acceptLabel,
        String cancelLabel) {
    public SelectionTableDialogRequest {
        id = text(id, "id", 128);
        title = text(title, "title", 256);
        hint = Objects.requireNonNull(hint, "hint");
        columns = List.copyOf(columns);
        if (columns.size() != 4 || columns.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("Four column labels are required");
        rows = List.copyOf(rows);
        if (rows.size() > 10000 || rows.stream().map(Row::id).distinct().count() != rows.size())
            throw new IllegalArgumentException("At most 10000 unique rows are allowed");
        acceptLabel = text(acceptLabel, "acceptLabel", 64);
        cancelLabel = text(cancelLabel, "cancelLabel", 64);
    }
    /** One stable row with a typed native icon and a mandatory localized fallback. */
    public record Row(
            String id,
            UiIconRef icon,
            String iconFallback,
            String original,
            String target,
            boolean selectable,
            boolean selected) {
        public Row {
            id = text(id, "row id", 256);
            icon = Objects.requireNonNull(icon, "icon");
            iconFallback = text(iconFallback, "iconFallback", 128);
            original = Objects.requireNonNull(original, "original");
            target = Objects.requireNonNull(target, "target");
            if (selected && !selectable) throw new IllegalArgumentException("An unselectable row cannot be selected");
        }
    }

    private static String text(String value, String name, int max) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > max) throw new IllegalArgumentException(name + " has invalid length");
        return value;
    }
}
