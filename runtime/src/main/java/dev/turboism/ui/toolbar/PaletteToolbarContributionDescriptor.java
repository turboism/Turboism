package dev.turboism.ui.toolbar;

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
    int order
) {
    public PaletteToolbarContributionDescriptor {
        pluginId = requireText(pluginId, "pluginId");
        contributionId = requireText(contributionId, "contributionId");
        actionId = requireText(actionId, "actionId");
        label = requireText(label, "label");
        iconResourcePath = requireText(iconResourcePath, "iconResourcePath");
        paletteId = requireText(paletteId, "paletteId");
        anchor = requireText(anchor, "anchor");
    }

    /**
     * Normalizes one typed runtime contribution for palette-toolbar materialization.
     *
     * @param contribution the authority-owned contribution to adapt
     * @return a validated palette-toolbar descriptor
     * @throws IllegalArgumentException when the contribution has a different descriptor type
     */
    public static PaletteToolbarContributionDescriptor from(
        final EditorUiContribution<?> contribution
    ) {
        Objects.requireNonNull(contribution, "contribution");
        if (!(contribution.descriptor()
            instanceof PaletteToolbarRegistry.PaletteToolbarContribution value)) {
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
            contribution.order()
        );
    }

    /** @return the stable plugin-scoped identity used to join native buttons across reconciles */
    public String nativeId() {
        return pluginId + ":" + contributionId;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
