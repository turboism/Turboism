package dev.turboism.ui.panel;

import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.Objects;

/** Host-neutral descriptor consumed by the embedded-panel provider. */
public record EmbeddedPanelContributionDescriptor(
    String pluginId,
    String contributionId,
    String title,
    String placement,
    int priority,
    PanelView content,
    boolean floatingByDefault
) {

    public EmbeddedPanelContributionDescriptor {
        pluginId = requireText(pluginId, "pluginId");
        contributionId = requireText(contributionId, "contributionId");
        title = requireText(title, "title");
        placement = requireText(placement, "placement");
        content = Objects.requireNonNull(content, "content");
    }

    /**
     * Projects a validated UI contribution into the host-neutral descriptor the embedded-panel
     * provider consumes, dropping the SDK payload type.
     *
     * @param contribution a contribution whose family is {@code PANEL} and whose descriptor is an
     *     {@link EmbeddedPanelContribution}
     * @return the descriptor carrying the contribution's plugin id and panel payload
     * @throws NullPointerException if {@code contribution} is {@code null}
     * @throws IllegalArgumentException if the contribution is not a panel contribution, or if its
     *     identity's contribution id disagrees with the payload's own id
     */
    public static EmbeddedPanelContributionDescriptor from(
        final EditorUiContribution<?> contribution
    ) {
        final EditorUiContribution<?> requested = Objects.requireNonNull(
            contribution,
            "contribution"
        );
        if (requested.identity().family() != EditorUiFamily.PANEL
            || !(requested.descriptor() instanceof EmbeddedPanelContribution panel)) {
            throw new IllegalArgumentException("embedded-panel provider requires PANEL contributions");
        }
        if (!requested.identity().contributionId().equals(panel.id())) {
            throw new IllegalArgumentException("embedded-panel identity does not match payload id");
        }
        return new EmbeddedPanelContributionDescriptor(
            requested.identity().pluginId(),
            panel.id(),
            panel.title(),
            panel.placement(),
            panel.priority(),
            panel.content(),
            panel.floatingByDefault()
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
