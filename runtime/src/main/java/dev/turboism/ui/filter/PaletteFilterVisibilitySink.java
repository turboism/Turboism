package dev.turboism.ui.filter;

import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;

import java.util.List;

/**
 * Sink receiving palette filter-box contribution snapshots.
 *
 * <p>The runtime session's palette filter host implements this interface and
 * reconciles attached filter boxes whenever a plugin's snapshot changes.</p>
 */
public interface PaletteFilterVisibilitySink {

    /**
     * Delivers one plugin's current filter contribution snapshot.
     *
     * @param pluginId the plugin whose contributions changed
     * @param contributions the plugin's complete contribution list; empty removes the plugin's
     *        contributions
     */
    void onPaletteFilterVisibilityChanged(
        String pluginId,
        List<PaletteFilterRegistry.PaletteFilterContribution> contributions
    );
}
