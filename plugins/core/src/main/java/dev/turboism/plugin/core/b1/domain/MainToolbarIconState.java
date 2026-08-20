package dev.turboism.plugin.core.b1.domain;

/** The visual states the main-toolbar icon can be rendered in. */
public enum MainToolbarIconState {
    NORMAL,
    HOVER;

    /**
     * @return the render descriptor for this state. Both states currently resolve to the same home
     *         icon asset, tint mode, and message keys, differing only in the {@code state} they
     *         carry; a fresh instance is returned on each call.
     */
    public MainToolbarIconDescriptor descriptor() {
        return new MainToolbarIconDescriptor(
            this,
            "icons/main-toolbar-home.svg",
            IconTintMode.CURRENT_COLOR,
            "main-toolbar.home.aria-label",
            "main-toolbar.home.tooltip"
        );
    }
}
