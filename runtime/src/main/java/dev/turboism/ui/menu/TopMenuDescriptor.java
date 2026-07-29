package dev.turboism.ui.menu;

import java.util.List;
import java.util.Objects;

/** Complete deterministic snapshot of the runtime-owned Turboism top menu. */
public record TopMenuDescriptor(
    String menuId,
    String label,
    List<TopMenuItemDescriptor> items
) {

    public static final String MENU_ID = "turboism.menu";
    public static final String MENU_LABEL = "Turboism";

    public TopMenuDescriptor {
        menuId = requireText(menuId, "menuId");
        label = requireText(label, "label");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
    }

    static TopMenuDescriptor turboism(final List<TopMenuItemDescriptor> items) {
        return new TopMenuDescriptor(MENU_ID, MENU_LABEL, items);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
