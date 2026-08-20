package dev.turboism.plugin.core.b1.domain;

import java.util.Objects;

/**
 * Everything needed to render the plugin's main-toolbar icon in one visual state.
 *
 * <p>An immutable value; every component is required and construction throws
 * {@link NullPointerException} for any {@code null}. The label and tooltip are message keys, not
 * display text - resolving them to a locale is the renderer's job.
 *
 * @param state the visual state this descriptor describes
 * @param resourcePath classpath-relative location of the icon asset
 * @param tintMode how the icon takes its colour
 * @param ariaLabelKey message key for the accessible name announced by screen readers
 * @param tooltipKey message key for the hover tooltip text
 */
public record MainToolbarIconDescriptor(
    MainToolbarIconState state,
    String resourcePath,
    IconTintMode tintMode,
    String ariaLabelKey,
    String tooltipKey
) {
    public MainToolbarIconDescriptor {
        state = Objects.requireNonNull(state, "state");
        resourcePath = Objects.requireNonNull(resourcePath, "resourcePath");
        tintMode = Objects.requireNonNull(tintMode, "tintMode");
        ariaLabelKey = Objects.requireNonNull(ariaLabelKey, "ariaLabelKey");
        tooltipKey = Objects.requireNonNull(tooltipKey, "tooltipKey");
    }
}
