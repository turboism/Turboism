package dev.turboism.sdk.ui.viewcontext;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Contributes menu items into the modeling view's canvas-top control strip
 * (the view context menu behind the caret button of the bar that hosts e.g.
 * the "Lock Drawable Object" toggle).
 */
public interface ViewContextMenuRegistry {

    /**
     * Adds a text menu item to the strip's view context menu. The host invokes
     * the click consumer on every click; the owning plugin updates the item
     * text through {@link #setText(String, String)} to reflect its state.
     *
     * @param contribution descriptor of the menu item
     */
    Registration contributeMenuItem(MenuItemContribution contribution);

    /**
     * Updates the drawn text of a contributed menu item.
     *
     * @param contributionId plugin-scoped identity used at contribute time
     * @param text the new item text
     */
    void setText(String contributionId, String text);

    /** One canvas-strip menu item owned by a plugin. */
    record MenuItemContribution(
        String contributionId,
        String text,
        Consumer<Void> onClick
    ) {
        public MenuItemContribution {
            contributionId = Objects.requireNonNull(contributionId, "contributionId");
            text = Objects.requireNonNull(text, "text");
            Objects.requireNonNull(onClick, "onClick");
        }
    }
}
