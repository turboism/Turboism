package dev.turboism.sdk.ui.viewcontext;

import dev.turboism.sdk.plugin.Registration;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Contributes a state button into the modeling view's canvas-top GL control
 * strip (the bar hosting the "Lock Drawable Object" toggle and the
 * display-visibility buttons). Contributed buttons mount into the strip's own
 * button group and inherit its per-frame layout, mode visibility and
 * scene-graph hit testing.
 */
public interface ViewContextMenuRegistry {

    /**
     * Adds a single icon toggle button to the strip whose icon cycles through
     * the provided state images on click. The host invokes the click consumer
     * with the clicked state on every click.
     *
     * @param contribution descriptor of the state button
     */
    Registration contributeStateButtons(StateButtonContribution contribution);

    /**

    /** A state-cycling icon button owned by a plugin. */
    record StateButtonContribution(
        String contributionId,
        Map<Integer, BufferedImage> stateIcons,
        int initialState,
        Consumer<Integer> onClick
    ) {
        public StateButtonContribution {
            contributionId = Objects.requireNonNull(contributionId, "contributionId");
            stateIcons = Objects.requireNonNull(stateIcons, "stateIcons");
            Objects.requireNonNull(onClick, "onClick");
            if (stateIcons.isEmpty()) {
                throw new IllegalArgumentException("stateIcons must not be empty");
            }
        }
    }
}
