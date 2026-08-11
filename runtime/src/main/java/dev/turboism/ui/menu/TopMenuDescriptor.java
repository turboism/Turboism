package dev.turboism.ui.menu;

import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Complete deterministic snapshot of one top-level menu (plugin-owned or shared reserved root). */
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

    /**
     * Reserved root menu aggregated across contributing plugins (the shared
     * Turboism root). Items keep their originating plugin identity and route
     * back to their owning plugin.
     */
    static TopMenuDescriptor shared(
        final String rootLabel,
        final List<TopMenuItemDescriptor> items
    ) {
        final String label = requireText(rootLabel, "rootLabel");
        final List<TopMenuItemDescriptor> snapshot = List.copyOf(items);
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (snapshot.stream().anyMatch(item -> !item.rootLabel().equals(label))) {
            throw new IllegalArgumentException("shared top-menu items must share one root label");
        }
        final String encodedLabel = Base64.getUrlEncoder().withoutPadding().encodeToString(
            label.getBytes(StandardCharsets.UTF_8)
        );
        return new TopMenuDescriptor("turboism.menu.shared." + encodedLabel, label, snapshot);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
