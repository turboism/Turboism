package dev.turboism.sdk.ui;

import java.util.List;
import java.util.Objects;

/**
 * Photoshop-style vertical icon tool strip attached to the Cubism main frame.
 *
 * <p>Each button routes clicks through the calling plugin's
 * {@code ActionRegistry}; the plugin decides the toggle semantics (e.g. show
 * or hide a floating history pane).</p>
 */
public record VerticalToolbarContribution(
    String contributionId,
    List<ToolButton> buttons,
    CanvasSide side
) {

    public VerticalToolbarContribution {
        if (contributionId == null || contributionId.isBlank()) {
            throw new IllegalArgumentException("contributionId must not be null or blank");
        }
        buttons = List.copyOf(Objects.requireNonNull(buttons, "buttons"));
        if (buttons.isEmpty()) {
            throw new IllegalArgumentException("buttons must not be empty");
        }
        side = Objects.requireNonNull(side, "side");
    }

    public VerticalToolbarContribution(
        final String contributionId,
        final List<ToolButton> buttons
    ) {
        this(contributionId, buttons, CanvasSide.RIGHT);
    }

    /** Where the tool strip attaches relative to the modeling canvas. */
    public enum CanvasSide {
        /** Vertical strip at the canvas right edge (Photoshop-style). */
        RIGHT,
        /** Horizontal strip below the canvas. */
        BOTTOM
    }

    /**
     * One vertical-strip icon button.
     *
     * @param id                unique button id within the contribution
     * @param iconResourcePath  plugin resource path of the icon image
     * @param tooltipKey        localization key or literal tooltip text
     * @param actionId          action routed through the plugin ActionRegistry
     */
    public record ToolButton(
        String id,
        String iconResourcePath,
        String tooltipKey,
        String actionId
    ) {

        public ToolButton {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be null or blank");
            }
            if (iconResourcePath == null || iconResourcePath.isBlank()) {
                throw new IllegalArgumentException("iconResourcePath must not be null or blank");
            }
            if (tooltipKey == null || tooltipKey.isBlank()) {
                throw new IllegalArgumentException("tooltipKey must not be null or blank");
            }
            if (actionId == null || actionId.isBlank()) {
                throw new IllegalArgumentException("actionId must not be null or blank");
            }
        }
    }
}
