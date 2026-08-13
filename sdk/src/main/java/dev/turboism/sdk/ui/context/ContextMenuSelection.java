package dev.turboism.sdk.ui.context;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;

/** Immutable, generation-bound selection supplied to an object context-menu action. */
@PreviewApi
public record ContextMenuSelection(
    long hostGeneration,
    String documentId,
    ContextMenuRegistry.Location location,
    List<Item> items
) {
    public ContextMenuSelection {
        documentId = requireText(documentId, "documentId");
        location = Objects.requireNonNull(location, "location");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    @PreviewApi
    public record Item(
        ContextMenuRegistry.ObjectKind kind,
        String id
    ) {
        public Item {
            kind = Objects.requireNonNull(kind, "kind");
            id = requireText(id, "id");
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
