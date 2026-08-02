package dev.turboism.ui.menu;

import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Complete deterministic snapshot of one plugin-owned top-level menu. */
public record TopMenuDescriptor(
    String menuId,
    String label,
    List<TopMenuItemDescriptor> items
) {
    public TopMenuDescriptor {
        menuId = requireText(menuId, "menuId");
        label = requireText(label, "label");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
    }

    static TopMenuDescriptor owned(
        final String rootLabel,
        final List<TopMenuItemDescriptor> items
    ) {
        final String label = requireText(rootLabel, "rootLabel");
        final List<TopMenuItemDescriptor> snapshot = List.copyOf(items);
        if (snapshot.isEmpty() || snapshot.stream().anyMatch(item ->
            !item.rootLabel().equals(label))) {
            throw new IllegalArgumentException("top-menu items must share one root label");
        }
        final String owner = snapshot.get(0).pluginId();
        final String encodedLabel = Base64.getUrlEncoder().withoutPadding().encodeToString(
            label.getBytes(StandardCharsets.UTF_8)
        );
        return new TopMenuDescriptor("turboism.menu." + owner + "." + encodedLabel, label, snapshot);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
