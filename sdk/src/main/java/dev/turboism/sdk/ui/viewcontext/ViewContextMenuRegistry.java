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
 * scene-graph hit testing. Their relative order is fixed by
 * the registration's explicit order and then by contribution id, so it
 * does not depend on plugin load order.
 */
public interface ViewContextMenuRegistry {

    /**
     * Adds a single icon toggle button to the strip whose icon cycles through
     * the provided state images on click. The host invokes the click consumer
     * on every click. The button is placed by contribution order, with ties
     * broken by contribution id, rather than by registration time.
     *
     * @param contribution descriptor of the state button
     */
    Registration contributeStateButtons(StateButtonContribution contribution);

    /**
     * Registers a button at a deterministic relative strip position.
     * Lower orders appear first; ties are resolved by contribution id.
     * Implementations that do not support ordering fail closed for nonzero
     * orders rather than silently changing button placement.
     *
     * @param contribution descriptor of the state button
     * @param order relative strip position (default {@code 0})
     * @return registration whose close removes the button
     */
    default Registration contributeStateButtons(final StateButtonContribution contribution, final int order) {
        if (order == 0) {
            return contributeStateButtons(contribution);
        }
        throw new UnsupportedOperationException("viewContextMenu ordering is not supported");
    }

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

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} when the backend backing this instance is
     *         unavailable, including the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static ViewContextMenuRegistry unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
    enum Unavailable implements ViewContextMenuRegistry {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Registration contributeStateButtons(final StateButtonContribution contribution) {
            Objects.requireNonNull(contribution, "contribution");
            throw new UnsupportedOperationException("viewContextMenu service is not available");
        }

        @Override
        public void updateButtonState(final String contributionId, final int axis) {
            throw new UnsupportedOperationException("viewContextMenu service is not available");
        }
    }

    /** A state-cycling icon button owned by a plugin. */
    record StateButtonContribution(
            String contributionId,
            Map<Integer, UiRasterImage> stateIcons,
            int initialState,
            Consumer<Integer> onClick) {
        /**
         * @param contributionId plugin-scoped identity used at contribute time
         * @param stateIcons state-to-icon map; must not be empty
         * @param initialState state shown first
         * @param onClick invoked with the state of the clicked button
         */
        public StateButtonContribution {
            contributionId = Objects.requireNonNull(contributionId, "contributionId");
            stateIcons = Objects.requireNonNull(stateIcons, "stateIcons");
            Objects.requireNonNull(onClick, "onClick");
            if (stateIcons.isEmpty()) {
                throw new IllegalArgumentException("stateIcons must not be empty");
            }
            final Map<Integer, UiRasterImage> icons = new LinkedHashMap<>();
            stateIcons.forEach((state, image) ->
                    icons.put(Objects.requireNonNull(state, "state"), Objects.requireNonNull(image, "image")));
            stateIcons = Collections.unmodifiableMap(icons);
        }
    }
}
