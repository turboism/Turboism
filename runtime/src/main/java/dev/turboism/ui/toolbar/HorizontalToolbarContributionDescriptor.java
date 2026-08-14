package dev.turboism.ui.toolbar;

import dev.turboism.sdk.ui.HorizontalToolbarContribution;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.Objects;

/** Host-neutral descriptor consumed by the horizontal-toolbar provider. */
public record HorizontalToolbarContributionDescriptor(
    String pluginId,
    String contributionId,
    HorizontalToolbarContribution contribution
) {

    public HorizontalToolbarContributionDescriptor {
        pluginId = requireText(pluginId, "pluginId");
        contributionId = requireText(contributionId, "contributionId");
        contribution = Objects.requireNonNull(contribution, "contribution");
    }

    public static HorizontalToolbarContributionDescriptor from(
        final EditorUiContribution<?> contribution
    ) {
        final EditorUiContribution<?> requested = Objects.requireNonNull(
            contribution,
            "contribution"
        );
        if (requested.identity().family() != EditorUiFamily.HORIZONTAL_TOOLBAR
            || !(requested.descriptor() instanceof HorizontalToolbarContribution strip)) {
            throw new IllegalArgumentException(
                "horizontal-toolbar provider requires HORIZONTAL_TOOLBAR contributions"
            );
        }
        if (!requested.identity().contributionId().equals(strip.contributionId())) {
            throw new IllegalArgumentException(
                "horizontal-toolbar identity does not match payload id"
            );
        }
        return new HorizontalToolbarContributionDescriptor(
            requested.identity().pluginId(),
            strip.contributionId(),
            strip
        );
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
