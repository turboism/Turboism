package dev.turboism.ui.toolbar;

import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.List;

public interface ToolbarVisibilitySink {

    default void onMainToolbarVisibilityChanged(
        final String pluginId,
        final List<MainToolbarRegistry.MainToolbarContribution> contributions
    ) {
    }

    default void onPaletteToolbarVisibilityChanged(
        final String pluginId,
        final List<PaletteToolbarRegistry.PaletteToolbarContribution> contributions
    ) {
    }
}
