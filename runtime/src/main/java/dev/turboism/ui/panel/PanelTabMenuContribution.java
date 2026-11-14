package dev.turboism.ui.panel;

import dev.turboism.internal.Requirements;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import java.util.Objects;

/** Generation-bound, owner-preserving, host-neutral panel-tab menu contribution. */
public record PanelTabMenuContribution(
        long hostGeneration, String pluginId, ContextMenuRegistry.ContextMenuContribution contribution) {
    public PanelTabMenuContribution {
        if (hostGeneration <= 0) {
            throw new IllegalArgumentException("hostGeneration must be positive");
        }
        pluginId = Requirements.text(pluginId, "pluginId");
        contribution = Objects.requireNonNull(contribution, "contribution");
        if (contribution.target() != ContextMenuRegistry.Target.PANEL_TAB) {
            throw new IllegalArgumentException("panel-tab menu contribution requires PANEL_TAB target");
        }
    }
}
