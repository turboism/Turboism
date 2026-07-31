package dev.turboism.plugin.core.b1.domain;

import java.util.Objects;

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
