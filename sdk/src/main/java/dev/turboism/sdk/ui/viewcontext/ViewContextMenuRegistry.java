package dev.turboism.sdk.ui.viewcontext;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Contributes controls into the modeling view's canvas-top control strip (the
 * GL-drawn view context menu that hosts e.g. the "Lock Drawable Object"
 * toggle). Contributions mount into the same entity group and inherit the
 * strip's per-frame layout, mode visibility and scene-graph hit testing.
 */
public interface ViewContextMenuRegistry {

    /**
     * Adds a text toggle button to the strip. The host invokes the click
     * consumer on every click; the owning plugin updates the displayed text
     * through {@link #setText(String, String)} and any behavioural state.
     *
     * @param contribution descriptor of the button
     */
    Registration contributeButton(ButtonContribution contribution);

    /**
     * Updates the drawn text of a contributed button.
     *
     * @param contributionId plugin-scoped identity used at contribute time
     * @param text the new button text
     */
    void setText(String contributionId, String text);

    /**
     * Updates the drawn selected state of a contributed button.
     *
     * @param contributionId plugin-scoped identity used at contribute time
     * @param selected whether the button renders as selected
     */
    void setSelected(String contributionId, boolean selected);

    /** One canvas-strip text button owned by a plugin. */
    record ButtonContribution(
        String contributionId,
        String text,
        String tooltip,
        Consumer<Void> onClick
    ) {
        public ButtonContribution {
            contributionId = Objects.requireNonNull(contributionId, "contributionId");
            text = Objects.requireNonNull(text, "text");
            tooltip = Objects.requireNonNull(tooltip, "tooltip");
            Objects.requireNonNull(onClick, "onClick");
        }
    }
}
