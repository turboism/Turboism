package dev.turboism.sdk.ui.viewcontext;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Contributes buttons into the modeling view's canvas-top control strip (the
 * GL-drawn view context menu that hosts e.g. the "Lock Drawable Object"
 * toggle). Contributions mount into the same entity group and inherit the
 * strip's per-frame layout, mode visibility and scene-graph hit testing.
 */
public interface ViewContextMenuRegistry {

    /**
     * Adds a click-cycle button to the strip.
     *
     * @param contribution descriptor of the button; the click consumer fires on
     *     every click and owns the resulting state (the host draws the button's
     *     selected visual from {@link #setSelected})
     */
    Registration contributeButton(ButtonContribution contribution);

    /**
     * Updates the drawn selected state of a contributed button.
     *
     * @param contributionId plugin-scoped identity used at contribute time
     * @param selected whether the button renders as selected
     */
    void setSelected(String contributionId, boolean selected);

    /** One canvas-strip button owned by a plugin. */
    record ButtonContribution(
        String contributionId,
        String name,
        String tooltipTitle,
        String tooltipDescription,
        Consumer<Void> onClick
    ) {
        public ButtonContribution {
            contributionId = Objects.requireNonNull(contributionId, "contributionId");
            name = Objects.requireNonNull(name, "name");
            Objects.requireNonNull(onClick, "onClick");
        }
    }
}
