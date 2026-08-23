package dev.turboism.ui.toolbar;

import dev.turboism.sdk.ui.VerticalToolbarContribution;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.Objects;

/** Host-neutral descriptor consumed by the vertical-toolbar provider. */
public record VerticalToolbarContributionDescriptor(
    String pluginId,
    String contributionId,
    VerticalToolbarContribution contribution
) {

    public VerticalToolbarContributionDescriptor {
        pluginId = requireText(pluginId, "pluginId");
        contributionId = requireText(contributionId, "contributionId");
        contribution = Objects.requireNonNull(contribution, "contribution");
    }

    /**
     * Projects a validated UI contribution into the host-neutral descriptor the vertical-toolbar
     * provider consumes.
     *
     * @param contribution a contribution whose family is {@code VERTICAL_TOOLBAR} and whose
     *     descriptor is a {@link VerticalToolbarContribution}
     * @return the descriptor pairing the owning plugin id with the strip payload
     * @throws NullPointerException if {@code contribution} is {@code null}
     * @throws IllegalArgumentException if the contribution is not a vertical-toolbar
     *     contribution, or its identity's contribution id disagrees with the payload's own id
     */
    public static VerticalToolbarContributionDescriptor from(
        final EditorUiContribution<?> contribution
    ) {
        final EditorUiContribution<?> requested = Objects.requireNonNull(
            contribution,
            "contribution"
        );
        if (requested.identity().family() != EditorUiFamily.VERTICAL_TOOLBAR
            || !(requested.descriptor() instanceof VerticalToolbarContribution strip)) {
            throw new IllegalArgumentException(
                "vertical-toolbar provider requires VERTICAL_TOOLBAR contributions"
            );
        }
        if (!requested.identity().contributionId().equals(strip.contributionId())) {
            throw new IllegalArgumentException(
                "vertical-toolbar identity does not match payload id"
            );
        }
        return new VerticalToolbarContributionDescriptor(
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
