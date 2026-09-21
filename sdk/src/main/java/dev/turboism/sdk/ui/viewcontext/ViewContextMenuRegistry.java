package dev.turboism.sdk.ui.viewcontext;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.resource.UiRasterImage;

import java.util.Collections;
import java.util.LinkedHashMap;
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
     * on every click.
     *
     * @param contribution descriptor of the state button
     */
    Registration contributeStateButtons(StateButtonContribution contribution);

    /**
     * Updates the button's visual state to match the armed axis.
     * {@code axis == 0} (off) shows the DISABLED visual (slash icon);
     * {@code axis == 1} (vertical) shows NORMAL with the vertical icon;
     * {@code axis == 2} (horizontal) shows SELECTED with the horizontal icon.
     *
     * @param contributionId plugin-scoped identity used at contribute time
     * @param axis the armed axis (0=off, 1=vertical, 2=horizontal)
     */
    void updateButtonState(String contributionId, int axis);

    /** A state-cycling icon button owned by a plugin. */
    record StateButtonContribution(
        String contributionId,
        Map<Integer, UiRasterImage> stateIcons,
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
            final Map<Integer, UiRasterImage> icons = new LinkedHashMap<>();
            stateIcons.forEach((state, image) -> icons.put(
                Objects.requireNonNull(state, "state"), Objects.requireNonNull(image, "image")
            ));
            stateIcons = Collections.unmodifiableMap(icons);
        }
    }
}
