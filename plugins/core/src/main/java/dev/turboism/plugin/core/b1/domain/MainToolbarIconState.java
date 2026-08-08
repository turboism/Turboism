package dev.turboism.plugin.core.b1.domain;

public enum MainToolbarIconState {
    NORMAL,
    HOVER;

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
