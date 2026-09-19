package dev.turboism.sdk.ui.viewcontext;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Contributes text buttons into the modeling view's canvas-top control strip
 * (the GL-drawn view context menu bar that hosts e.g. the "Lock Drawable
 * Object" toggle). Contributed text buttons mount next to the strip's own
 * buttons and are re-positioned by the hook whenever the strip re-layouts.
 */
public interface ViewContextMenuRegistry {

    /**
     * Adds a text button to the strip. The host invokes the click consumer on
     * every click; the owning plugin updates the button text through
     * {@link #setText(String, String)} to reflect its state.
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
