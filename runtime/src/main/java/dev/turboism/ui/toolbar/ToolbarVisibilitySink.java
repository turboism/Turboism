package dev.turboism.ui.toolbar;

import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.List;

/**
 * Optional observer told when a plugin's toolbar contributions change visibility.
 *
 * <p>Both methods default to doing nothing, so an implementor subscribes only to the family it
 * cares about. Callbacks run on whichever thread applied the visibility change; an implementation
 * that touches Swing must marshal to the event dispatch thread itself.
 */
public interface ToolbarVisibilitySink {

    /**
     * @param pluginId the plugin whose main-toolbar visibility changed
     * @param contributions the contributions that are visible after the change; empty when the
     *     plugin now contributes nothing visible
     */
    default void onMainToolbarVisibilityChanged(
        final String pluginId,
        final List<MainToolbarRegistry.MainToolbarContribution> contributions
    ) {
    }

    /**
     * @param pluginId the plugin whose palette-toolbar visibility changed
     * @param contributions the contributions that are visible after the change; empty when the
     *     plugin now contributes nothing visible
     */
    default void onPaletteToolbarVisibilityChanged(
        final String pluginId,
        final List<PaletteToolbarRegistry.PaletteToolbarContribution> contributions
    ) {
    }
}
