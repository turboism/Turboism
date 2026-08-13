package dev.turboism.sdk.ui.context;

import java.util.Objects;

/** Immutable, generation-bound panel-tab context supplied to a Tab action. */
public record PanelTabSelection(
    long hostGeneration,
    String panelId,
    boolean floating
) {
    public PanelTabSelection {
        if (hostGeneration <= 0) {
            throw new IllegalArgumentException("hostGeneration must be positive");
        }
        panelId = requireText(panelId, "panelId");
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
