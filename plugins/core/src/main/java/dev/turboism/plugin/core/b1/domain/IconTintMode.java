package dev.turboism.plugin.core.b1.domain;

/** How a toolbar icon takes its colour. */
public enum IconTintMode {
    /**
     * The icon inherits the surrounding text colour rather than carrying its own, so it follows the
     * host theme without needing a separate light and dark asset.
     */
    CURRENT_COLOR
}
