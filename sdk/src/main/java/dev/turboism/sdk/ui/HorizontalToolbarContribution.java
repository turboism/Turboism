package dev.turboism.sdk.ui;

import java.util.List;
import java.util.Objects;

/**
 * Horizontal icon tool strip attached above or below the modeling canvas.
 *
 * <p>Each button routes clicks through the calling plugin's
 * {@code ActionRegistry}; the plugin decides the toggle semantics (e.g. show
 * or hide a floating history pane).</p>
 */
public record HorizontalToolbarContribution(
    String contributionId,
    List<VerticalToolbarContribution.ToolButton> buttons,
    HorizontalSide side
) {

    public HorizontalToolbarContribution {
        if (contributionId == null || contributionId.isBlank()) {
            throw new IllegalArgumentException("contributionId must not be null or blank");
        }
        buttons = List.copyOf(Objects.requireNonNull(buttons, "buttons"));
        if (buttons.isEmpty()) {
            throw new IllegalArgumentException("buttons must not be empty");
        }
        side = Objects.requireNonNull(side, "side");
    }

    public HorizontalToolbarContribution(
        final String contributionId,
        final List<VerticalToolbarContribution.ToolButton> buttons
    ) {
        this(contributionId, buttons, HorizontalSide.BOTTOM);
    }

    /** Where the horizontal strip attaches relative to the modeling canvas. */
    public enum HorizontalSide {
        /** Horizontal strip above the canvas. */
        TOP,
        /** Horizontal strip below the canvas. */
        BOTTOM
    }
}
