package dev.turboism.ui.toolbar;

import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;

import java.util.Objects;

/** Normalized provider view over compatible and preferred main-toolbar contributions. */
public record MainToolbarContributionDescriptor(
    String pluginId,
    String contributionId,
    String actionId,
    String label,
    String tooltip,
    MainToolbarRegistry.IconVariants icons,
    MainToolbarRegistry.Placement placement,
    int order
) {
    public MainToolbarContributionDescriptor {
        pluginId = requireText(pluginId, "pluginId");
        contributionId = requireText(contributionId, "contributionId");
        actionId = requireText(actionId, "actionId");
        label = requireText(label, "label");
        tooltip = requireText(tooltip, "tooltip");
        icons = Objects.requireNonNull(icons, "icons");
        placement = Objects.requireNonNull(placement, "placement");
    }

    /**
     * Normalizes either main-toolbar contribution shape into one descriptor.
     *
     * <p>A preferred {@code MainToolbarButtonContribution} maps across directly. A compatible
     * {@code MainToolbarContribution} is upgraded: its label key doubles as the tooltip key, its
     * single icon path becomes the normal icon variant, and its textual anchor is translated to a
     * {@link MainToolbarRegistry.Placement} — only {@code start}/{@code first}, {@code end}/
     * {@code last}, and {@code before:}/{@code after:host-home-entry} are understood.
     *
     * @param contribution the contribution to normalize
     * @return the normalized descriptor, carrying the contribution's declared order
     * @throws NullPointerException if {@code contribution} is {@code null}
     * @throws IllegalArgumentException if the descriptor is neither supported shape, if a
     *     compatible contribution names an anchor outside the list above, or if any required
     *     text field is blank
     */
    public static MainToolbarContributionDescriptor from(
        final EditorUiContribution<?> contribution
    ) {
        Objects.requireNonNull(contribution, "contribution");
        final Object descriptor = contribution.descriptor();
        if (descriptor instanceof MainToolbarRegistry.MainToolbarButtonContribution button) {
            return new MainToolbarContributionDescriptor(
                contribution.identity().pluginId(),
                button.contributionId(),
                button.actionId(),
                button.labelKey(),
                button.tooltipKey(),
                button.icons(),
                button.placement(),
                contribution.order()
            );
        }
        if (descriptor instanceof MainToolbarRegistry.MainToolbarContribution compatible) {
            return new MainToolbarContributionDescriptor(
                contribution.identity().pluginId(),
                compatible.contributionId(),
                compatible.actionId(),
                compatible.labelKey(),
                compatible.labelKey(),
                MainToolbarRegistry.IconVariants.normal(compatible.iconResourcePath()),
                compatiblePlacement(compatible.anchor()),
                contribution.order()
            );
        }
        throw new IllegalArgumentException("Unsupported main toolbar contribution descriptor");
    }

    private static MainToolbarRegistry.Placement compatiblePlacement(final String anchor) {
        return switch (requireText(anchor, "anchor")) {
            case "start", "first" -> MainToolbarRegistry.Placement.first();
            case "end", "last" -> MainToolbarRegistry.Placement.last();
            case "before:host-home-entry" -> MainToolbarRegistry.Placement.before(
                MainToolbarRegistry.Anchor.HOST_HOME_ENTRY
            );
            case "after:host-home-entry" -> MainToolbarRegistry.Placement.after(
                MainToolbarRegistry.Anchor.HOST_HOME_ENTRY
            );
            default -> throw new IllegalArgumentException(
                "Unsupported compatible main toolbar anchor: " + anchor
            );
        };
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
