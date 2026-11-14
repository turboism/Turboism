package dev.turboism.ui.toolbar;

import dev.turboism.internal.Requirements;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;
import java.util.Objects;

/** Normalized provider view over typed palette-toolbar contributions. */
public record PaletteToolbarContributionDescriptor(
        String pluginId,
        String contributionId,
        String actionId,
        String label,
        String iconResourcePath,
        String paletteId,
        String anchor,
        int order,
        dev.turboism.sdk.ui.resource.UiIconRef nativeIcon) {
    public PaletteToolbarContributionDescriptor(
            String pluginId,
            String contributionId,
            String actionId,
            String label,
            String iconResourcePath,
            String paletteId,
            String anchor,
            int order) {
        this(pluginId, contributionId, actionId, label, iconResourcePath, paletteId, anchor, order, null);
    }

    public PaletteToolbarContributionDescriptor {
        pluginId = Requirements.text(pluginId, "pluginId");
        contributionId = Requirements.text(contributionId, "contributionId");
        actionId = Requirements.text(actionId, "actionId");
        label = Requirements.text(label, "label");
        iconResourcePath = nativeIcon == null ? Requirements.text(iconResourcePath, "iconResourcePath") : "";
        paletteId = Requirements.text(paletteId, "paletteId");
        anchor = Requirements.text(anchor, "anchor");
    }

    /**
     * Normalizes one typed runtime contribution for palette-toolbar materialization.
     *
     * @param contribution the authority-owned contribution to adapt
     * @return a validated palette-toolbar descriptor
     * @throws IllegalArgumentException when the contribution has a different descriptor type
     */
    public static PaletteToolbarContributionDescriptor from(final EditorUiContribution<?> contribution) {
        Objects.requireNonNull(contribution, "contribution");
        if (!(contribution.descriptor() instanceof PaletteToolbarRegistry.PaletteToolbarContribution value)) {
            throw new IllegalArgumentException("Unsupported palette toolbar contribution descriptor");
        }
        return new PaletteToolbarContributionDescriptor(
                contribution.identity().pluginId(),
                value.contributionId(),
                value.actionId(),
                value.labelKey(),
                value.iconResourcePath(),
                value.paletteId(),
                value.anchor(),
                contribution.order(),
                value.nativeIcon());
    }

    /** @return the stable plugin-scoped identity used to join native buttons across reconciles */
    public String nativeId() {
        return pluginId + ":" + contributionId;
    }
}
