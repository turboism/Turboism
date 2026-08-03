package dev.turboism.ui.panel;

import dev.turboism.sdk.ui.context.ContextMenuRegistry;

import java.util.Objects;

/** Generation-bound, owner-preserving, host-neutral panel-tab menu contribution. */
public record PanelTabMenuContribution(
    long hostGeneration,
    String pluginId,
    ContextMenuRegistry.ContextMenuContribution contribution
) {
    public PanelTabMenuContribution {
        if (hostGeneration <= 0) {
            throw new IllegalArgumentException("hostGeneration must be positive");
        }
        pluginId = requireText(pluginId, "pluginId");
        contribution = Objects.requireNonNull(contribution, "contribution");
        if (contribution.target() != ContextMenuRegistry.Target.PANEL_TAB) {
            throw new IllegalArgumentException("panel-tab menu contribution requires PANEL_TAB target");
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
