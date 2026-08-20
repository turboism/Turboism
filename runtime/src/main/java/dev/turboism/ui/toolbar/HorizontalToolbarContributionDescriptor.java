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

    /**
     * Projects a validated UI contribution into the host-neutral descriptor the
     * horizontal-toolbar provider consumes.
     *
     * @param contribution a contribution whose family is {@code HORIZONTAL_TOOLBAR} and whose
     *     descriptor is a {@link HorizontalToolbarContribution}
     * @return the descriptor pairing the owning plugin id with the strip payload
     * @throws NullPointerException if {@code contribution} is {@code null}
     * @throws IllegalArgumentException if the contribution is not a horizontal-toolbar
     *     contribution, or its identity's contribution id disagrees with the payload's own id
     */
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
