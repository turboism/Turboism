package dev.turboism.sdk.ui.viewcontext;

import dev.turboism.sdk.plugin.Registration;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Contributes a state button group into the modeling view's canvas-top GL
 * control strip (the bar hosting the "Lock Drawable Object" toggle and the
 * display-visibility buttons). Contributed buttons mount into the strip's own
 * button group and inherit its per-frame layout, mode visibility and
 * scene-graph hit testing.
 */
public interface ViewContextMenuRegistry {

    /**
     * Adds one toggle button per provided state to the strip, joined into an
     * exclusive button group. The host invokes the click consumer with the
     * clicked state on every click; the owning plugin updates behavioural state
     * (and may sync keyboard toggles through {@link #selectState}).
     *
     * @param contribution descriptor of the button group
     */
    Registration contributeStateButtons(StateButtonContribution contribution);

    /**
     * Programmatically selects a contributed state (used for keyboard toggles
     * that must stay in sync with the strip visuals).
     *
     * @param contributionId plugin-scoped identity used at contribute time
     * @param state the state key whose button is drawn as selected
     */
    void selectState(String contributionId, int state);

    /** A per-state button group owned by a plugin. */
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
