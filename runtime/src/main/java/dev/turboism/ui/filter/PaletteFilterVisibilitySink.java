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

    void onPaletteFilterVisibilityChanged(
        String pluginId,
        List<PaletteFilterRegistry.PaletteFilterContribution> contributions
    );
}
